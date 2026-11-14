package org.jahia.se.modules.contenttags;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBundlesTest {

    /**
     * Jahia reads a module resource bundle as ISO-8859-1, so a raw UTF-8 accent shows as "Ã©" in the Content
     * Editor. Write accents as unicode escapes, such as {@code é}.
     */
    @Test
    void resourceBundlesAreAscii() throws IOException {
        List<Path> bundles;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/resources"))) {
            bundles = files.filter(file -> file.toString().endsWith(".properties")).toList();
        }

        assertFalse(bundles.isEmpty());
        for (Path bundle : bundles) {
            byte[] bytes = Files.readAllBytes(bundle);
            for (byte b : bytes) {
                assertTrue(b >= 0, "Non-ASCII byte in " + bundle.getFileName() + ": use a unicode escape");
            }
        }
    }
}
