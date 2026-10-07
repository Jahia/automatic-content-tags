package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.se.modules.contenttags.service.spi.LlmProvider;
import org.jahia.se.modules.contenttags.service.spi.LlmSettings;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jsoup.Jsoup;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ContentTagsService} implementation.
 *
 * <p>Extracts the internationalized single-valued string properties of a node (read through
 * the calling user's session — no privilege escalation), strips HTML, and asks the configured
 * {@link LlmProvider} for tags.</p>
 *
 * <p>Configuration is bound to PID {@code org.jahia.se.modules.contenttags} via
 * {@link ManagedService}; the parsed snapshot is held in an immutable {@link Config} record
 * swapped atomically, so concurrent calls are safe.</p>
 */
@Component(service = {ContentTagsService.class, ManagedService.class},
        property = "service.pid=org.jahia.se.modules.contenttags", immediate = true)
public class ContentTagsServiceImpl implements ContentTagsService, ManagedService {

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

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();
    private volatile Config config = Config.empty();

    @Reference(service = LlmProvider.class, cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC, unbind = "removeProvider")
    public void addProvider(LlmProvider provider) {
        providers.put(provider.getName(), provider);
    }

    public void removeProvider(LlmProvider provider) {
        providers.remove(provider.getName(), provider);
    }

    @Override
    public List<String> generateTags(JCRNodeWrapper node, String tagLanguage, int numberOfTags) {
        Config cfg = config;
        LlmProvider provider = providers.get(cfg.providerName());
        if (provider == null) {
            throw new IllegalStateException("No LLM provider registered under name '" + cfg.providerName()
                    + "'. Available providers: " + providers.keySet());
        }
        LlmSettings settings = cfg.settings().get(cfg.providerName());
        if (settings == null || !settings.hasApiKey()) {
            throw new IllegalStateException("LLM provider '" + cfg.providerName() + "' has no API key configured. Set "
                    + cfg.providerName() + ".api.key in org.jahia.se.modules.contenttags.cfg");
        }

        int count = clampCount(numberOfTags, cfg.tagCountDefault(), cfg.tagCountMax());

        String text = extractText(node, cfg.maxSourceChars());
        if (text.isEmpty()) {
            LOGGER.debug("No internationalized text found on node {}", node.getPath());
            return Collections.emptyList();
        }

        // The count and language are authoritative here, so the configurable prompt only has to
        // carry style guidance (and must not hardcode a number).
        String prompt = "Generate exactly " + count + " relevant tags, in " + tagLanguage
                + ", for the following content. " + cfg.prompt() + " Content: " + text;
        LOGGER.debug("Requesting {} tags from provider '{}' (model {}) for node {}",
                count, provider.getName(), settings.model(), node.getPath());
        try {
            String rawResponse = provider.complete(prompt, settings);
            List<String> tags = TagResponseParser.parse(rawResponse, count);
            LOGGER.debug("Provider '{}' returned {} tags for node {}", provider.getName(), tags.size(), node.getPath());
            return tags;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling LLM provider '" + provider.getName() + "'", e);
        } catch (Exception e) {
            throw new IllegalStateException("Call to LLM provider '" + provider.getName() + "' failed for node "
                    + node.getPath(), e);
        }
    }

    @Override
    public int getDefaultTagCount() {
        return config.tagCountDefault();
    }

    @Override
    public int getMaxTagCount() {
        return config.tagCountMax();
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

    @Override
    public void updated(Dictionary<String, ?> properties) {
        if (properties == null) {
            config = Config.empty();
            LOGGER.warn("Automatic Content Tags configuration removed; tag generation is disabled "
                    + "until org.jahia.se.modules.contenttags.cfg is provided");
            return;
        }
        String providerName = string(properties, "llm.provider", "anthropic").toLowerCase();
        String prompt = string(properties, "llm.user.prompt", DEFAULT_PROMPT);
        int maxTokens = integer(properties, "llm.max.tokens", DEFAULT_MAX_TOKENS);
        int maxSourceChars = integer(properties, "llm.max.source.chars", DEFAULT_MAX_SOURCE_CHARS);
        // Configurable ceiling, bounded by the absolute safety max; the default count can never exceed it.
        int tagCountMax = Math.max(MIN_TAG_COUNT, Math.min(ABSOLUTE_MAX_TAG_COUNT,
                integer(properties, "llm.tag.count.max", DEFAULT_TAG_COUNT_MAX)));
        int tagCountDefault = Math.max(MIN_TAG_COUNT, Math.min(tagCountMax,
                integer(properties, "llm.tag.count.default", DEFAULT_TAG_COUNT)));
        Double temperature = decimal(properties, "llm.temperature");

        Map<String, LlmSettings> settings = Map.of(
                "anthropic", providerSettings(properties, "anthropic", "https://api.anthropic.com",
                        "claude-sonnet-4-6", maxTokens, temperature),
                "openai", providerSettings(properties, "openai", "https://api.openai.com",
                        "gpt-5-mini", maxTokens, temperature),
                "deepseek", providerSettings(properties, "deepseek", "https://api.deepseek.com",
                        "deepseek-chat", maxTokens, temperature));

        config = new Config(providerName, prompt, maxSourceChars, tagCountDefault, tagCountMax, settings);

        LlmSettings active = settings.get(providerName);
        if (active == null) {
            LOGGER.error("Unknown llm.provider '{}'. Supported values: {}", providerName, settings.keySet());
        } else if (!active.hasApiKey()) {
            LOGGER.error("LLM provider '{}' selected but {}.api.key is not configured", providerName, providerName);
        } else {
            LOGGER.info("Automatic Content Tags configured: provider={} model={} baseUrl={}",
                    providerName, active.model(), active.baseUrl());
        }
    }

    private static LlmSettings providerSettings(Dictionary<String, ?> properties, String prefix,
            String defaultBaseUrl, String defaultModel, int maxTokens, Double temperature) {
        String baseUrl = string(properties, prefix + ".base.url", defaultBaseUrl);
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return new LlmSettings(
                string(properties, prefix + ".api.key", ""),
                baseUrl,
                string(properties, prefix + ".model", defaultModel),
                maxTokens,
                temperature);
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
     * Immutable configuration snapshot.
     */
    private record Config(String providerName, String prompt, int maxSourceChars, int tagCountDefault,
                          int tagCountMax, Map<String, LlmSettings> settings) {
        static Config empty() {
            return new Config("anthropic", DEFAULT_PROMPT, DEFAULT_MAX_SOURCE_CHARS,
                    DEFAULT_TAG_COUNT, DEFAULT_TAG_COUNT_MAX, Map.of());
        }
    }
}
