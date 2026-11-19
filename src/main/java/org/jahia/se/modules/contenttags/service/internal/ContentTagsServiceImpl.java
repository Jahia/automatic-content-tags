package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionRequest;
import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ImageInput;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.se.modules.contenttags.service.TagTarget;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jsoup.Jsoup;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link ContentTagsService} implementation.
 *
 * <p>Extracts the internationalized single-valued string properties of a node (read through
 * the calling user's session — no privilege escalation), strips HTML, and asks ai-assistant for
 * tags on the model tier of {@code llm.tier}. An image ({@code jmix:image}) also sends its picture,
 * on the tier {@code vision}, so its tags describe what it shows. The operator chooses the provider,
 * the model and the key of each tier in the ai-assistant configuration, never here.</p>
 *
 * <p>Configuration is bound to PID {@code org.jahia.se.modules.contenttags}; the parsed snapshot is
 * held in an immutable {@link Config} record swapped atomically, so concurrent calls are safe.</p>
 */
@Component(service = ContentTagsService.class, configurationPid = ConfigValues.PID, immediate = true)
public class ContentTagsServiceImpl implements ContentTagsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentTagsServiceImpl.class);

    // Style-only guidance; the tag count and language are injected authoritatively by the service.
    private static final String DEFAULT_PROMPT =
            "Respond ONLY with a JSON array of concise single- or two-word strings, "
                    + "without markdown, explanations or duplicates.";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final int DEFAULT_MAX_SOURCE_CHARS = 6000;
    private static final int DEFAULT_TAG_COUNT = 5;
    private static final int DEFAULT_TAG_COUNT_MAX = 20;
    private static final int MIN_TAG_COUNT = 1;
    // Hard safety ceiling for the configurable maximum, to protect the token budget.
    private static final int ABSOLUTE_MAX_TAG_COUNT = 50;
    static final String DEFAULT_TAG_PROPERTY = "j:tagList";
    static final String DEFAULT_TAG_MIXIN = "jmix:tagged";

    // Package-private for the unit tests, which inject a mock.
    @Reference
    GenAiService genAi;

    private volatile Config config = Config.from(ConfigValues.of(Map.of()));

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        config = Config.from(ConfigValues.of(properties));
        List<String> legacy = legacyKeys(properties);
        if (!legacy.isEmpty()) {
            LOGGER.warn("Automatic Content Tags ignores the 1.x provider keys {}: configure the provider, the model "
                    + "and the API key in ai-assistant (org.jahia.modules.genai.cfg), then remove these keys", legacy);
        }
        LOGGER.info("Automatic Content Tags configured: tags in {}, on the ai-assistant tier '{}' ({}), and on "
                        + "'{}' for an image ({})", config.tagTarget(), config.tier().id(),
                genAi.isAvailable(config.tier()) ? "available" : "not configured yet", ModelTier.VISION.id(),
                genAi.isAvailable(ModelTier.VISION) ? "available" : "not configured yet");
    }

    /** The keys of the 1.x built-in providers, which no longer have any effect; never their values. */
    static List<String> legacyKeys(Map<String, ?> properties) {
        if (properties == null) {
            return List.of();
        }
        return properties.keySet().stream()
                .filter(key -> key.equals("llm.provider") || key.startsWith("anthropic.") || key.startsWith("openai.")
                        || key.startsWith("deepseek."))
                .sorted()
                .toList();
    }

    @Override
    public List<String> generateTags(JCRNodeWrapper node, String tagLanguage, int numberOfTags) {
        Config cfg = config;
        int count = clampCount(numberOfTags, cfg.tagCountDefault(), cfg.tagCountMax());

        String text = extractText(node, cfg.maxSourceChars());
        Optional<ImageInput> picture = picture(node, cfg);
        if (text.isEmpty() && picture.isEmpty()) {
            LOGGER.debug("No internationalized text and no image found on node {}", node.getPath());
            return Collections.emptyList();
        }

        // The count and language are authoritative here, so the configurable prompt only has to
        // carry style guidance (and must not hardcode a number).
        String ask = "Generate exactly " + count + " relevant tags, in " + tagLanguage;
        CompletionRequest.Builder request = CompletionRequest.builder()
                .maxOutputTokens(cfg.maxTokens())
                .temperature(cfg.temperature());
        if (picture.isPresent()) {
            String prompt = ask + ", for this image. " + cfg.prompt() + " Its file name is \"" + node.getName() + "\"."
                    + (text.isEmpty() ? "" : " Its text: " + text);
            request.tier(ModelTier.VISION).input(prompt).images(List.of(picture.get()));
        } else {
            request.tier(cfg.tier()).input(ask + ", for the following content. " + cfg.prompt() + " Content: " + text);
        }
        CompletionRequest built = request.build();
        LOGGER.debug("Requesting {} tags on tier '{}' for node {}", count, built.tier().id(), node.getPath());
        CompletionResult result = genAi.complete(built);
        List<String> tags = TagResponseParser.parse(result.text(), count);
        LOGGER.debug("Model {} returned {} tags for node {}", result.model(), tags.size(), node.getPath());
        return tags;
    }

    @Override
    public Optional<TagTarget> tagTarget(JCRNodeWrapper node) throws RepositoryException {
        TargetProperty target = config.tagTarget();
        ExtendedPropertyDefinition definition = definitionOf(node, target);
        if (definition == null || !TargetProperty.holdsText(definition)) {
            LOGGER.debug("The tag property {} cannot hold tags on {}", target, node.getPath());
            return Optional.empty();
        }
        String field = definition.getDeclaringNodeType().getName() + "_" + target.property();
        return Optional.of(new TagTarget(target.property(), target.mixin(), field, definition.isMultiple()));
    }

    /** Package-private so the unit tests can stand in for the node type registry. */
    ExtendedPropertyDefinition definitionOf(JCRNodeWrapper node, TargetProperty target) throws RepositoryException {
        return target.definitionOn(node);
    }

    @Override
    public int getDefaultTagCount() {
        return config.tagCountDefault();
    }

    @Override
    public int getMaxTagCount() {
        return config.tagCountMax();
    }

    /**
     * @return the picture of an image node, read through the caller's session; empty for another node, and
     *         for an image with no rendition the model accepts, whose tags then come from its text only
     */
    private static Optional<ImageInput> picture(JCRNodeWrapper node, Config cfg) {
        try {
            if (!node.isNodeType("jmix:image")) {
                return Optional.empty();
            }
            Optional<ImageInput> picture = ImagePayload.select(node, cfg.maxImageBytes());
            if (picture.isEmpty()) {
                LOGGER.debug("The image {} has no rendition the model accepts, so its tags come from its text",
                        node.getPath());
            }
            return picture;
        } catch (RepositoryException | IOException e) {
            LOGGER.warn("Unable to read the image {}, so its tags come from its text", node.getPath(), e);
            return Optional.empty();
        }
    }

    private static int clampCount(int requested, int configuredDefault, int configuredMax) {
        int count = requested > 0 ? requested : configuredDefault;
        return Math.max(MIN_TAG_COUNT, Math.min(configuredMax, count));
    }

    /**
     * Collects the internationalized, single-valued string properties of the node and
     * strips any HTML markup.
     */
    private String extractText(JCRNodeWrapper node, int maxSourceChars) {
        StringBuilder builder = new StringBuilder();
        try {
            PropertyIterator iterator = node.getProperties();
            while (iterator.hasNext()) {
                Property property = iterator.nextProperty();
                if (property.getDefinition() instanceof ExtendedPropertyDefinition definition
                        && definition.isInternationalized()
                        && !definition.isMultiple()
                        && definition.getRequiredType() == PropertyType.STRING) {
                    builder.append(property.getValue().getString()).append(' ');
                }
            }
        } catch (RepositoryException e) {
            LOGGER.error("Unable to extract text from node {}", node.getPath(), e);
            return "";
        }
        String text = Jsoup.parse(builder.toString()).text().trim();
        if (text.length() > maxSourceChars) {
            LOGGER.debug("Truncating source text from {} to {} characters", text.length(), maxSourceChars);
            text = text.substring(0, maxSourceChars);
        }
        return text;
    }

    /**
     * Immutable configuration snapshot.
     */
    record Config(TargetProperty tagTarget, ModelTier tier, String prompt, int maxTokens, Double temperature,
                  int maxSourceChars, int maxImageBytes, int tagCountDefault, int tagCountMax) {

        static Config from(ConfigValues values) {
            // Configurable ceiling, bounded by the absolute safety max; the default count can never exceed it.
            int tagCountMax = values.integer("llm.tag.count.max", DEFAULT_TAG_COUNT_MAX,
                    MIN_TAG_COUNT, ABSOLUTE_MAX_TAG_COUNT);
            return new Config(
                    new TargetProperty(values.string("llm.tag.property", DEFAULT_TAG_PROPERTY),
                            values.optional("llm.tag.mixin", DEFAULT_TAG_MIXIN)),
                    values.tier("llm.tier", ModelTier.LITE),
                    values.string("llm.user.prompt", DEFAULT_PROMPT),
                    values.integer("llm.max.tokens", DEFAULT_MAX_TOKENS, 1, Integer.MAX_VALUE),
                    values.decimal("llm.temperature"),
                    values.integer("llm.max.source.chars", DEFAULT_MAX_SOURCE_CHARS, 1, Integer.MAX_VALUE),
                    ImagePayload.maxBytes(values),
                    values.integer("llm.tag.count.default", DEFAULT_TAG_COUNT, MIN_TAG_COUNT, tagCountMax),
                    tagCountMax);
        }
    }
}
