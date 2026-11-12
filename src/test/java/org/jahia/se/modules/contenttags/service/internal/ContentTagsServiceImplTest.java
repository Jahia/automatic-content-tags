package org.jahia.se.modules.contenttags.service.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTagsServiceImplTest {

    @Test
    void theKeysOfTheRemovedProvidersAreDetected() {
        Map<String, Object> properties = Map.of(
                "llm.provider", "anthropic",
                "anthropic.api.key", "secret",
                "openai.model", "gpt",
                "deepseek.base.url", "https://example.com",
                "llm.tier", "lite",
                "llm.tag.count.max", "20");

        assertEquals(List.of("anthropic.api.key", "deepseek.base.url", "llm.provider", "openai.model"),
                ContentTagsServiceImpl.legacyKeys(properties));
    }

    @Test
    void aCurrentConfigurationHasNoLegacyKey() {
        assertTrue(ContentTagsServiceImpl.legacyKeys(Map.of("llm.tier", "lite", "alt.tier", "lite")).isEmpty());
        assertTrue(ContentTagsServiceImpl.legacyKeys(null).isEmpty());
    }
}
