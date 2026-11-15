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
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
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
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Default {@link AltTextService} implementation.
 *
 * <p>For each image it sends one rendition of the picture, with a short instruction, to the model of
 * the genai-connector tier {@code vision}, cleans the answer and saves it in the property of
 * {@code alt.property} (default {@code jahia_ai_alt}), in the locale of the caller's session. It never opens a system session: an image the caller cannot
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

    // Package-private for the unit tests, which move the clock.
    LongSupplier nanoTime = System::nanoTime;

    private volatile Config config = Config.from(ConfigValues.of(Map.of()));

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        config = Config.from(ConfigValues.of(properties));
        LOGGER.info("Automatic Content Tags configured: alternative text in {}, on the genai-connector tier '{}' ({}), "
                        + "at most {} images and {} seconds per call", config.target(), ModelTier.VISION.id(),
                genAi.isAvailable(ModelTier.VISION) ? "available" : "not configured yet", config.maxImages(),
                config.maxSeconds());
    }

    @Override
    public AltTextReport fillAltText(JCRNodeWrapper node, boolean includeSubNodes, boolean overwrite,
                                     String resumeFrom) throws RepositoryException {
        Config cfg = config;
        long deadline = nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(cfg.maxSeconds());
        List<AltTextResult> results = new ArrayList<>();
        // Only the images sent to the model count: a skipped or refused image costs nothing.
        int modelCalls = 0;
        NavigableMap<String, JCRNodeWrapper> images = images(node, includeSubNodes);
        if (resumeFrom != null) {
            images = images.tailMap(resumeFrom, true);
        }
        for (JCRNodeWrapper listed : images.values()) {
            String path = listed.getPath();
            try {
                JCRNodeWrapper image = listed;
                Prepared prepared;
                try {
                    prepared = prepare(image, overwrite, cfg);
                } catch (InvalidItemStateException e) {
                    // A stale read before the model call: check the fresh copy once more.
                    image = reload(image);
                    prepared = prepare(image, overwrite, cfg);
                }
                if (prepared.result() != null) {
                    results.add(prepared.result());
                    continue;
                }
                // The time limit leaves the first call alone, so that every invocation moves forward.
                if (modelCalls == cfg.maxImages() || modelCalls > 0 && nanoTime.getAsLong() - deadline >= 0) {
                    return new AltTextReport(results, path);
                }
                modelCalls++;
                results.add(generate(image, prepared, overwrite, cfg));
            } catch (GenAiQuotaExceededException e) {
                results.add(new AltTextResult(path, AltTextResult.Status.FAILED, null,
                        "The AI rate limit is reached, try again later"));
                return new AltTextReport(results, path);
            } catch (GenAiProviderException e) {
                // Only a retryable failure gets here, and genai-connector already retried it: the next
                // image would meet the same busy or broken provider.
                LOGGER.warn("The model call failed for the image {}, the run stops: {}", path, e.getMessage());
                results.add(new AltTextResult(path, AltTextResult.Status.FAILED, null,
                        "The AI provider is busy or unavailable, try again later"));
                return new AltTextReport(results, path);
            } catch (RepositoryException e) {
                LOGGER.warn("Unable to process the image {}", path, e);
                results.add(new AltTextResult(path, AltTextResult.Status.FAILED, null, "The image cannot be read"));
            }
        }
        return new AltTextReport(results, null);
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

    /**
     * The node itself when it is an image, then the images below it when asked, by path. The path order is the
     * same on every invocation, so a later invocation can resume where an earlier one stopped.
     */
    private static NavigableMap<String, JCRNodeWrapper> images(JCRNodeWrapper node, boolean includeSubNodes)
            throws RepositoryException {
        NavigableMap<String, JCRNodeWrapper> images = new TreeMap<>();
        if (node.isNodeType("jmix:image")) {
            images.put(node.getPath(), node);
        }
        if (includeSubNodes) {
            String statement = "SELECT * FROM [jmix:image] AS image WHERE ISDESCENDANTNODE(image, ['"
                    + JCRContentUtils.sqlEncode(node.getPath()) + "'])";
            NodeIterator found = node.getSession().getWorkspace().getQueryManager()
                    .createQuery(statement, Query.JCR_SQL2).execute().getNodes();
            while (found.hasNext()) {
                JCRNodeWrapper image = (JCRNodeWrapper) found.nextNode();
                images.put(image.getPath(), image);
            }
        }
        return images;
    }

    private static String existingAltText(JCRNodeWrapper image, Config cfg) throws RepositoryException {
        String property = cfg.target().property();
        if (!image.hasProperty(property)) {
            return null;
        }
        String value = image.getProperty(property).getString();
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @return the definition that would hold the text on this image, or {@code null}; package-private so the
     *         unit tests can stand in for the node type registry
     */
    ExtendedPropertyDefinition definitionOf(JCRNodeWrapper image, Config cfg) throws RepositoryException {
        return cfg.target().definitionOn(image);
    }

    /**
     * What one image needs before its model call: either the result that ends its turn without a call
     * ({@code result} set), or its current text, picture and prompt.
     */
    private record Prepared(AltTextResult result, String existing, ImageInput picture, String prompt) {

        static Prepared done(AltTextResult result) {
            return new Prepared(result, null, null, null);
        }
    }

    /** Every read and every check of the image that comes before its model call. */
    private Prepared prepare(JCRNodeWrapper image, boolean overwrite, Config cfg) throws RepositoryException {
        String path = image.getPath();
        String existing = existingAltText(image, cfg);
        if (existing != null && !overwrite) {
            return Prepared.done(skipped(path, existing));
        }
        if (!image.hasPermission(WRITE_PERMISSION)) {
            return Prepared.done(new AltTextResult(path, AltTextResult.Status.FAILED, existing,
                    "You cannot edit this image"));
        }
        ExtendedPropertyDefinition definition = definitionOf(image, cfg);
        if (definition == null || definition.isMultiple() || !TargetProperty.holdsText(definition)) {
            return Prepared.done(new AltTextResult(path, AltTextResult.Status.FAILED, existing,
                    "The configured property " + cfg.target() + " is not a single-valued string property on this image"));
        }
        Optional<ImageInput> payload;
        try {
            payload = ImagePayload.select(image, cfg.maxImageBytes());
        } catch (IOException e) {
            LOGGER.warn("Unable to read the image {}", path, e);
            return Prepared.done(new AltTextResult(path, AltTextResult.Status.FAILED, existing,
                    "The image cannot be read"));
        }
        if (payload.isEmpty()) {
            return Prepared.done(new AltTextResult(path, AltTextResult.Status.FAILED, existing,
                    "The image has no JPEG, PNG, GIF or WebP rendition of at most " + cfg.maxImageBytes() + " bytes"));
        }
        return new Prepared(null, existing, payload.get(), prompt(image, cfg));
    }

    private AltTextResult generate(JCRNodeWrapper image, Prepared prepared, boolean overwrite, Config cfg)
            throws RepositoryException {
        String path = image.getPath();
        String existing = prepared.existing();
        CompletionResult result;
        try {
            result = genAi.complete(CompletionRequest.builder()
                    .tier(ModelTier.VISION)
                    .instructions(cfg.instructions())
                    .input(prepared.prompt())
                    .images(List.of(prepared.picture()))
                    .maxOutputTokens(cfg.maxTokens())
                    .temperature(cfg.temperature())
                    .build());
        } catch (GenAiProviderException e) {
            if (e.isRetryable()) {
                throw e;
            }
            LOGGER.warn("The model call failed for the image {}: {}", path, e.getMessage());
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The model call failed");
        }
        String altText = AltTextCleaner.clean(result.text(), cfg.maxLength());
        if (altText.isEmpty()) {
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "The model returned no text");
        }

        try {
            String mixin = cfg.target().mixin();
            if (mixin != null && !image.isNodeType(mixin)) {
                image.addMixin(mixin);
            }
            image.setProperty(cfg.target().property(), altText);
            image.getSession().save();
        } catch (AccessDeniedException e) {
            image.getSession().refresh(false);
            return new AltTextResult(path, AltTextResult.Status.FAILED, existing, "You cannot edit this image");
        } catch (InvalidItemStateException e) {
            // Another request, possibly on another cluster node, saved the image after this session read it.
            image.getSession().refresh(false);
            String current = existingAltText(image, cfg);
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
    record Config(TargetProperty target, String instructions, int maxLength, int maxImages, int maxSeconds,
                  int maxImageBytes, int maxTokens, Double temperature) {

        static Config from(ConfigValues values) {
            return new Config(
                    new TargetProperty(values.string("alt.property", DEFAULT_ALT_PROPERTY),
                            values.optional("alt.mixin", DEFAULT_ALT_MIXIN)),
                    values.string("alt.prompt", DEFAULT_INSTRUCTIONS),
                    values.integer("alt.max.length", 150, 20, 1000),
                    values.integer("alt.max.images", 20, 1, 500),
                    values.integer("alt.max.seconds", 60, 5, 600),
                    ImagePayload.maxBytes(values),
                    values.integer("alt.max.tokens", 300, 16, 8192),
                    values.decimal("llm.temperature"));
        }
    }
}
