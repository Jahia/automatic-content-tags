package org.jahia.se.modules.contenttags.service;

/**
 * What happened to one image.
 *
 * @param path    the path of the image node
 * @param status  the outcome
 * @param altText the alternative text now saved on the image, or {@code null} when it has none
 * @param message why the image was skipped or failed, or {@code null} for a generated text
 */
public record AltTextResult(String path, Status status, String altText, String message) {

    /** The outcome of one image. */
    public enum Status {
        /** A new alternative text was generated and saved. */
        GENERATED,
        /** The image already had an alternative text and the call did not ask to overwrite it. */
        SKIPPED,
        /** No text was saved; {@link AltTextResult#message()} says why. */
        FAILED
    }
}
