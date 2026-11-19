package org.jahia.se.modules.contenttags.service.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigValuesTest {

    @Test
    void anAbsentOptionalKeyGivesTheDefaultAndAnEmptyOneGivesNone() {
        ConfigValues values = ConfigValues.of(Map.of("alt.mixin", " ", "llm.tag.mixin", "jmix:keywords"));

        assertEquals("jmix:jahiaAlt", ConfigValues.of(Map.of()).optional("alt.mixin", "jmix:jahiaAlt"));
        assertNull(values.optional("alt.mixin", "jmix:jahiaAlt"));
        assertEquals("jmix:keywords", values.optional("llm.tag.mixin", "jmix:tagged"));
    }
}
