package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionRequest;
import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.modules.genai.api.GenAiException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jsoup.Jsoup;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;

/**
 * Default {@link ContentTagsService} implementation.
 *
 * <p>Extracts the internationalized single-valued string properties of a node (read through
 * the calling user's session — no privilege escalation), strips HTML, and asks the platform's
 * {@link GenAiService} (genai-connector module) for tags. Provider selection, API keys and
 * transport concerns live in the connector's central configuration
 * ({@code org.jahia.modules.genai}); this module keeps only its own prompt and sizing knobs.</p>
 *
 * <p>Configuration is bound to PID {@code org.jahia.se.modules.contenttags} via
 * {@link ManagedService}; the parsed snapshot is held in an immutable {@link Config} record
 * swapped atomically, so concurrent calls are safe.</p>
 */
@Component(service = {ContentTagsService.class, ManagedService.class},
        property = "service.pid=org.jahia.se.modules.contenttags", immediate = true)
public class ContentTagsServiceImpl implements ContentTagsService, ManagedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentTagsServiceImpl.class);

    private static final String DEFAULT_PROMPT =
            "Generate between 5 and 10 relevant tags for the following text. "
                    + "Respond ONLY with a JSON array of strings, without markdown or explanations.";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final int DEFAULT_MAX_SOURCE_CHARS = 6000;

    private volatile Config config = Config.empty();

    @Reference
    private GenAiService genAiService;

    @Override
    public List<String> generateTags(JCRNodeWrapper node, String tagLanguage) {
        Config cfg = config;
        if (!genAiService.isAvailable()) {
            throw new IllegalStateException("The GenAI connector is not configured. Set provider, api.key and "
                    + "model in org.jahia.modules.genai.cfg (genai-connector module).");
        }

        String text = extractText(node, cfg.maxSourceChars());
        if (text.isEmpty()) {
            LOGGER.debug("No internationalized text found on node {}", node.getPath());
            return Collections.emptyList();
        }

        String prompt = cfg.prompt() + " Generate the tags in " + tagLanguage + ". Text: " + text;
        LOGGER.debug("Requesting tags from the GenAI connector ({}) for node {}",
                genAiService.info().map(Object::toString).orElse("?"), node.getPath());
        try {
            CompletionRequest.Builder request = CompletionRequest.builder()
                    .input(prompt)
                    .maxOutputTokens(cfg.maxTokens());
            if (cfg.temperature() != null) {
                request.temperature(cfg.temperature());
            }
            CompletionResult result = genAiService.complete(request.build());
            List<String> tags = TagResponseParser.parse(result.text());
            LOGGER.debug("GenAI connector returned {} tags for node {} ({} output tokens)",
                    tags.size(), node.getPath(), result.usage().outputTokens());
            return tags;
        } catch (GenAiException e) {
            throw new IllegalStateException("GenAI call failed for node " + node.getPath() + ": "
                    + e.getMessage(), e);
        }
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

    @Override
    public void updated(Dictionary<String, ?> properties) {
        if (properties == null) {
            config = Config.empty();
            LOGGER.info("Automatic Content Tags configuration removed; using built-in defaults "
                    + "(prompt, sizing). Provider configuration lives in the genai-connector module.");
            return;
        }
        String prompt = string(properties, "llm.user.prompt", DEFAULT_PROMPT);
        int maxTokens = integer(properties, "llm.max.tokens", DEFAULT_MAX_TOKENS);
        int maxSourceChars = integer(properties, "llm.max.source.chars", DEFAULT_MAX_SOURCE_CHARS);
        Double temperature = decimal(properties, "llm.temperature");

        if (properties.get("llm.provider") != null || properties.get("anthropic.api.key") != null
                || properties.get("openai.api.key") != null || properties.get("deepseek.api.key") != null) {
            LOGGER.warn("Provider/API-key keys found in org.jahia.se.modules.contenttags.cfg are IGNORED since "
                    + "the migration to genai-connector — configure the provider in org.jahia.modules.genai.cfg");
        }

        config = new Config(prompt, maxTokens, maxSourceChars, temperature);
        LOGGER.info("Automatic Content Tags configured: maxTokens={} maxSourceChars={} temperature={}",
                maxTokens, maxSourceChars, temperature);
    }

    private static String string(Dictionary<String, ?> properties, String key, String defaultValue) {
        Object value = properties.get(key);
        String s = value == null ? null : value.toString().trim();
        return s == null || s.isEmpty() ? defaultValue : s;
    }

    private static int integer(Dictionary<String, ?> properties, String key, int defaultValue) {
        try {
            return Integer.parseInt(string(properties, key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid integer for configuration key {}; using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    private static Double decimal(Dictionary<String, ?> properties, String key) {
        String value = string(properties, key, "");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid decimal for configuration key {}; ignoring it", key);
            return null;
        }
    }

    /**
     * Immutable configuration snapshot (module-owned knobs only).
     */
    private record Config(String prompt, int maxTokens, int maxSourceChars, Double temperature) {
        static Config empty() {
            return new Config(DEFAULT_PROMPT, DEFAULT_MAX_TOKENS, DEFAULT_MAX_SOURCE_CHARS, null);
        }
    }
}
