package org.jahia.se.modules.contenttags;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionsTest {

    private static final Pattern PROPERTY = Pattern.compile("^\\s*-\\s+'?([^\\s'(]+)'?");

    /**
     * Jahia stores the CND in its definitions table re-serialized without quotes, and a node that loads it
     * from there reads a hyphen in a name as the start of another property. Quoting the name in this file
     * is therefore not enough: only a name without a hyphen survives on every node of a cluster.
     */
    @Test
    void noPropertyNameHasAHyphen() throws IOException {
        List<String> names = propertyNames();

        assertFalse(names.isEmpty());
        for (String name : names) {
            assertTrue(name.matches("[A-Za-z0-9_:]+"), "Invalid property name for Jahia: " + name);
        }
    }

    private static List<String> propertyNames() throws IOException {
        try (InputStream cnd = DefinitionsTest.class.getResourceAsStream("/META-INF/definitions.cnd")) {
            assertNotNull(cnd);
            return new String(cnd.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(PROPERTY::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .toList();
        }
    }
}
