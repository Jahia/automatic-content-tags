package org.jahia.se.modules.contenttags.graphql;

import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextResult.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GqlAltTextReportTest {

    private final GqlAltTextReport report = new GqlAltTextReport(new AltTextReport(List.of(
            new AltTextResult("/a.png", Status.SKIPPED, "Done before", "The image already has an alternative text"),
            new AltTextResult("/b.png", Status.GENERATED, "A cat.", null),
            new AltTextResult("/c.svg", Status.FAILED, null, "No rendition")), null));

    @Test
    void theSkippedImagesAreCountedButListedOnlyOnRequest() {
        assertEquals(1, report.getSkipped());
        assertEquals(List.of("/b.png", "/c.svg"), report.getImages(false).stream().map(GqlAltTextImage::getPath).toList());
        assertEquals(3, report.getImages(true).size());
    }
}
