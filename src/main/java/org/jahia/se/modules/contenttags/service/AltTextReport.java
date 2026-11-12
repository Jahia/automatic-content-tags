package org.jahia.se.modules.contenttags.service;

import java.util.List;

/**
 * The outcome of one {@link AltTextService#fillAltText} invocation.
 *
 * @param images    one result per image visited, in the order they were processed
 * @param truncated true when images that still needed a text were left because the invocation reached
 *                  its image limit or the rate limit of genai-connector; call again to continue
 */
public record AltTextReport(List<AltTextResult> images, boolean truncated) {

    public AltTextReport {
        images = List.copyOf(images);
    }

    /** @return how many images have the given status */
    public long count(AltTextResult.Status status) {
        return images.stream().filter(result -> result.status() == status).count();
    }
}
