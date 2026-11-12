package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.ModelTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Typed reads of the module configuration (PID {@value #PID}), shared by the services that the
 * configuration drives. A missing, blank or invalid value falls back to the default, and a number is
 * clamped to its range, so a wrong value in the file never disables a feature.
 */
final class ConfigValues {

    /** The PID of the module configuration, {@code karaf/etc/org.jahia.se.modules.contenttags.cfg}. */
    static final String PID = "org.jahia.se.modules.contenttags";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValues.class);

    private final Map<String, ?> properties;

    private ConfigValues(Map<String, ?> properties) {
        this.properties = properties == null ? Map.of() : properties;
    }

    static ConfigValues of(Map<String, ?> properties) {
        return new ConfigValues(properties);
    }

    String string(String key, String defaultValue) {
        Object value = properties.get(key);
        String s = value == null ? null : value.toString().trim();
        return s == null || s.isEmpty() ? defaultValue : s;
    }

    int integer(String key, int defaultValue, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(string(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid integer for configuration key {}; using default {}", key, defaultValue);
            value = defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** @return the decimal value, or {@code null} when absent or invalid, so the provider default applies */
    Double decimal(String key) {
        String value = string(key, "");
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

    ModelTier tier(String key, ModelTier defaultValue) {
        String value = string(key, defaultValue.id());
        return ModelTier.fromId(value).orElseGet(() -> {
            LOGGER.warn("Unknown model tier '{}' for configuration key {}; using {}", value, key, defaultValue.id());
            return defaultValue;
        });
    }
}
