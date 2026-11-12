package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.ImageInput;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the bytes of an image node that the model receives.
 *
 * <p>The original file is sent when the model accepts its format and it is not too large. Otherwise
 * the larger thumbnail that Jahia generates for an uploaded image is sent, then the smaller one. A
 * TIFF, an SVG or a photo of 20 MB therefore still gets a text when Jahia made a thumbnail of it.</p>
 */
final class ImagePayload {

    /** The renditions of a {@code jnt:file} image, in the order they are tried. */
    private static final List<String> RENDITIONS = List.of("jcr:content", "thumbnail2", "thumbnail");

    /** The media types that every chat provider of genai-connector accepts. */
    private static final Set<String> SENDABLE = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private ImagePayload() {
    }

    /**
     * @param image    the image node, read through the calling user's session
     * @param maxBytes the largest rendition that may be sent
     * @return the first rendition the model accepts, or empty when the image has none
     */
    static Optional<ImageInput> select(Node image, long maxBytes) throws RepositoryException, IOException {
        for (String rendition : RENDITIONS) {
            if (!image.hasNode(rendition)) {
                continue;
            }
            Node resource = image.getNode(rendition);
            if (!resource.hasProperty("jcr:data") || !resource.hasProperty("jcr:mimeType")) {
                continue;
            }
            Optional<String> mediaType = sendableMediaType(resource.getProperty("jcr:mimeType").getString());
            Property data = resource.getProperty("jcr:data");
            if (mediaType.isEmpty() || data.getLength() <= 0 || data.getLength() > maxBytes) {
                continue;
            }
            Binary binary = data.getBinary();
            try (InputStream stream = binary.getStream()) {
                return Optional.of(ImageInput.of(stream.readNBytes((int) maxBytes), mediaType.get()));
            } finally {
                binary.dispose();
            }
        }
        return Optional.empty();
    }

    /**
     * @param mimeType the {@code jcr:mimeType} of a rendition, possibly with parameters
     * @return the media type to send, or empty when no chat provider accepts it
     */
    static Optional<String> sendableMediaType(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        String type = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(type) || "image/pjpeg".equals(type)) {
            type = "image/jpeg";
        }
        return SENDABLE.contains(type) ? Optional.of(type) : Optional.empty();
    }
}
