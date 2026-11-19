package org.jahia.se.modules.contenttags.service;

import java.util.List;

/**
 * The outcome of one {@link AltTextService#fillAltText} invocation.
 *
 * @param images     one result per image visited, in path order
 * @param resumeFrom the path of the first image left when the invocation stopped early: at its image limit, at
 *                   its time limit, at the rate limit of ai-assistant, or on a busy provider. {@code null}
 *                   when every image was visited. Pass it back to continue from that image
 */
public record AltTextReport(List<AltTextResult> images, String resumeFrom) {

    public AltTextReport {
        images = List.copyOf(images);
    }

    /** @return true when images that may still need a text were left */
    public boolean truncated() {
        return resumeFrom != null;
    }

    /** @return how many images have the given status */
    public long count(AltTextResult.Status status) {
        return images.stream().filter(result -> result.status() == status).count();
    }
}
