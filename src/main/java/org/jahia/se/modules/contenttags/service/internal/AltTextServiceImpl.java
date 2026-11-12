package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionRequest;
import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ImageInput;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link AltTextService} implementation.
 *
 * <p>For each image it sends one rendition of the picture, with a short instruction, to the model of
 * the genai-connector tier {@code alt.tier}, cleans the answer and saves it in {@code jahia_alt}, in
 * the locale of the caller's session. It never opens a system session: an image the caller cannot
 * read is not listed, and an image the caller cannot edit is reported as failed before any model
 * call.</p>
 */
@Component(service = AltTextService.class, configurationPid = ConfigValues.PID, immediate = true)
public class AltTextServiceImpl implements AltTextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextServiceImpl.class);

    static final String DEFAULT_INSTRUCTIONS = "You write the alternative text (the HTML alt attribute) of images "
            + "published on a website, for readers who cannot see them. Describe what the image shows and what "
            + "matters to understand it, in one short sentence. Do not start with \"Image of\", \"Picture of\" or "
            + "\"Photo of\". Do not use quotes, markdown or emoji. Reply with the alternative text only.";

    private static final String WRITE_PERMISSION = "jcr:write_default";

    // Package-private for the unit tests, which inject a mock.
    @Reference
    GenAiService genAi;

    private volatile Config config = Config.from(ConfigValues.of(Map.of()));

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        config = Config.from(ConfigValues.of(properties));
        LOGGER.info("Automatic Content Tags configured: alternative text on the genai-connector tier '{}' ({}), "
                        + "at most {} images per call", config.tier().id(),
                genAi.isAvailable(config.tier()) ? "available" : "not configured yet", config.maxImages());
    }

    @Override
    public AltTextReport fillAltText(JCRNodeWrapper node, boolean includeSubNodes, boolean overwrite)
            throws RepositoryException {
        Config cfg = config;
        List<AltTextResult> results = new ArrayList<>();
        int budget = cfg.maxImages();
        boolean truncated = false;
        for (JCRNodeWrapper listed : images(node, includeSubNodes)) {
            String path = listed.getPath();
            try {
                JCRNodeWrapper image = listed;
                String existing;
                try {
                    existing = existingAltText(image);
                } catch (InvalidItemStateException e) {
                    image = reload(image);
                    existing = existingAltText(image);
                }
                if (existing != null && !overwrite) {
                    results.add(skipped(path, existing));
                    continue;
                }
                if (budget == 0) {
                    truncated = true;
                    break;
                }
                budget--;
                try {
                    results.add(process(image, existing, overwrite, cfg));
                } catch (InvalidItemStateException e) {
                    // A stale read before the model call: check the fresh copy, then try once more.
                    image = reload(image);
                    String current = existingAltText(image);
                    results.add(current != null && !overwrite ? skipped(path, current)
                            : process(image, current, overwrite, cfg));
                }
            } catch (GenAiQuotaExceededException e) {
                results.add(new AltTextResult(path, AltTextResult.Status.FAILED, null,
                        "The AI rate limit is reached, try again later"));
                truncated = true;
                break;
            } catch (RepositoryException e) {
                LOGGER.warn("Unable to process the image {}", path, e);
                results.add(new AltTextResult(path, AltTextResult.Status.FAILED, null, "The image cannot be read"));
            }
        }
        return new AltTextReport(results, truncated);
    }

    private static AltTextResult skipped(String path, String existing) {
        return new AltTextResult(path, AltTextResult.Status.SKIPPED, existing,
                "The image already has an alternative text");
    }

    /**
     * Drops the session's copy of the items, which another cluster node may have changed after this session
     * read them, and reads the image again.
     */
    private static JCRNodeWrapper reload(JCRNodeWrapper image) throws RepositoryException {
        String path = image.getPath();
        image.getSession().refresh(false);
        return image.getSession().getNode(path);
    }

    @Override
    public int getMaxImages() {
        return config.maxImages();
    }

    /** The node itself when it is an image, then the images below it when asked. */
    private static List<JCRNodeWrapper> images(JCRNodeWrapper node, boolean includeSubNodes)
            throws RepositoryException {
        List<JCRNodeWrapper> images = new ArrayList<>();
        if (node.isNodeType("jmix:image")) {
            images.add(node);
        }
        if (includeSubNodes) {
            String statement = "SELECT * FROM [jmix:image] AS image WHERE ISDESCENDANTNODE(image, ['"
                    + JCRContentUtils.sqlEncode(node.getPath()) + "'])";
            NodeIterator found = node.getSession().getWorkspace().getQueryManager()
                    .createQuery(statement, Query.JCR_SQL2).execute().getNodes();
            while (found.hasNext()) {
                images.add((JCRNodeWrapper) found.nextNode());
            }
        }
        return images;
    }

    private static String existingAltText(JCRNodeWrapper image) throws RepositoryException {
        if (!image.hasProperty(ALT_PROPERTY)) {
            return null;
        }
        String value = image.getProperty(ALT_PROPERTY).getString();
        return value == null || value.isBlank() ? null : value;
    }

    private AltTextResult process(JCRNodeWrapper image, String existing, boolean overwrite, Config cfg)
            throws RepositoryException {
        String path = image.getPath();
        if (!image.hasPermission(WRITE_PERMISSION)) {
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "You cannot edit this image");
        }
        Optional<ImageInput> payload;
        try {
            payload = ImagePayload.select(image, cfg.maxImageBytes());
        } catch (IOException e) {
            LOGGER.warn("Unable to read the image {}", path, e);
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The image cannot be read");
        }
        if (payload.isEmpty()) {
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing,
                    "The image has no JPEG, PNG, GIF or WebP rendition of at most " + cfg.maxImageBytes() + " bytes");
        }

        CompletionResult result;
        try {
            result = genAi.complete(CompletionRequest.builder()
                    .tier(cfg.tier())
                    .instructions(cfg.instructions())
                    .input(prompt(image, cfg))
                    .images(List.of(payload.get()))
                    .maxOutputTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .build());
        } catch (GenAiProviderException e) {
            LOGGER.warn("The model call failed for the image {}: {}", path, e.getMessage());
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The model call failed");
        }
        String altText = AltTextCleaner.clean(result.text(), cfg.maxLength());
        if (altText.isEmpty()) {
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The model returned no text");
        }

        try {
            if (!image.isNodeType(ALT_MIXIN)) {
                image.addMixin(ALT_MIXIN);
            }
            image.setProperty(ALT_PROPERTY, altText);
            image.getSession().save();
        } catch (AccessDeniedException e) {
            image.getSession().refresh(false);
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "You cannot edit this image");
        } catch (InvalidItemStateException e) {
            // Another request, possibly on another cluster node, saved the image after this session read it.
            image.getSession().refresh(false);
            String current = existingAltText(image);
            if (current != null && !overwrite) {
                return new AltTextResult(path, AltTextResult.Status.SKIPPED, current,
                        "Another request gave the image an alternative text meanwhile");
            }
            LOGGER.info("The image {} changed while its alternative text was generated", path);
            return new AltTextResult(path, AltTextResult.Status.FAILED, current,
                    "The image changed meanwhile, try again");
        } catch (RepositoryException e) {
            image.getSession().refresh(false);
            LOGGER.warn("Unable to save the alternative text of {}", path, e);
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The text could not be saved");
        }
        LOGGER.debug("Saved an alternative text of {} characters on {} (model {})", altText.length(), path,
                result.model());
        return new AltTextResult(path, AltTextResult.Status.GENERATED, altText, null);
    }

    /** The user turn: the target language and length, plus the names that can help identify the subject. */
    static String prompt(JCRNodeWrapper image, Config cfg) throws RepositoryException {
        Locale locale = image.getSession().getLocale();
        String language = locale == null ? "English" : locale.getDisplayName(Locale.ENGLISH);
        StringBuilder prompt = new StringBuilder("Write the alternative text of this image in ")
                .append(language).append(", in at most ").append(cfg.maxLength()).append(" characters.");
        prompt.append(" Its file name is \"").append(image.getName()).append("\".");
        if (image.hasProperty("jcr:title")) {
            String title = image.getProperty("jcr:title").getString();
            if (title != null && !title.isBlank() && !title.equals(image.getName())) {
                prompt.append(" Its title is \"").append(title.strip()).append("\".");
            }
        }
        return prompt.toString();
    }

    /**
     * Immutable configuration snapshot.
     */
    record Config(ModelTier tier, String instructions, int maxLength, int maxImages, int maxImageBytes,
                  int maxTokens, Double temperature) {

        static Config from(ConfigValues values) {
            return new Config(
                    values.tier("alt.tier", ModelTier.LITE),
                    values.string("alt.prompt", DEFAULT_INSTRUCTIONS),
                    values.integer("alt.max.length", 150, 20, 1000),
                    values.integer("alt.max.images", 20, 1, 500),
                    values.integer("alt.max.image.bytes", 5 * 1024 * 1024, 1024, 20 * 1024 * 1024),
                    values.integer("alt.max.tokens", 300, 16, 8192),
                    values.decimal("llm.temperature"));
        }
    }
}
