package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.ImageInput;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImagePayloadTest {

    @Test
    void mediaTypesAreNormalized() {
        assertEquals(Optional.of("image/jpeg"), ImagePayload.sendableMediaType("image/jpg"));
        assertEquals(Optional.of("image/jpeg"), ImagePayload.sendableMediaType("IMAGE/JPEG; charset=binary"));
        assertEquals(Optional.of("image/webp"), ImagePayload.sendableMediaType("image/webp"));
        assertTrue(ImagePayload.sendableMediaType("image/tiff").isEmpty());
        assertTrue(ImagePayload.sendableMediaType("image/svg+xml").isEmpty());
        assertTrue(ImagePayload.sendableMediaType(null).isEmpty());
    }

    @Test
    void theOriginalIsSentWhenTheModelAcceptsIt() throws Exception {
        Node image = mock(Node.class);
        resource(image, "jcr:content", "image/png", new byte[] {1, 2, 3});
        resource(image, "thumbnail2", "image/jpeg", new byte[] {9});

        ImageInput input = ImagePayload.select(image, 1024).orElseThrow();

        assertEquals("image/png", input.mediaType());
        assertArrayEquals(new byte[] {1, 2, 3}, input.data());
    }

    @Test
    void anOriginalTooLargeOrNotAcceptedFallsBackToTheThumbnail() throws Exception {
        Node tooLarge = mock(Node.class);
        resource(tooLarge, "jcr:content", "image/png", new byte[2048]);
        resource(tooLarge, "thumbnail2", "image/jpeg", new byte[] {9});
        Node tiff = mock(Node.class);
        resource(tiff, "jcr:content", "image/tiff", new byte[] {1});
        resource(tiff, "thumbnail2", "image/jpeg", new byte[] {8});

        assertArrayEquals(new byte[] {9}, ImagePayload.select(tooLarge, 1024).orElseThrow().data());
        assertArrayEquals(new byte[] {8}, ImagePayload.select(tiff, 1024).orElseThrow().data());
    }

    @Test
    void anImageWithNoAcceptedRenditionGivesNothing() throws Exception {
        Node svg = mock(Node.class);
        resource(svg, "jcr:content", "image/svg+xml", new byte[] {1});

        assertTrue(ImagePayload.select(svg, 1024).isEmpty());
    }

    /** Adds a rendition to the image mock, with the wrapper types when the image is a Jahia node. */
    static void resource(Node image, String name, String mimeType, byte[] bytes) throws Exception {
        boolean jahia = image instanceof JCRNodeWrapper;
        Node resource = jahia ? mock(JCRNodeWrapper.class) : mock(Node.class);
        Property mime = jahia ? mock(JCRPropertyWrapper.class) : mock(Property.class);
        Property data = jahia ? mock(JCRPropertyWrapper.class) : mock(Property.class);
        Binary binary = mock(Binary.class);
        when(image.hasNode(name)).thenReturn(true);
        if (jahia) {
            when(((JCRNodeWrapper) image).getNode(name)).thenReturn((JCRNodeWrapper) resource);
            when(((JCRNodeWrapper) resource).getProperty("jcr:mimeType")).thenReturn((JCRPropertyWrapper) mime);
            when(((JCRNodeWrapper) resource).getProperty("jcr:data")).thenReturn((JCRPropertyWrapper) data);
        } else {
            when(image.getNode(name)).thenReturn(resource);
            when(resource.getProperty("jcr:mimeType")).thenReturn(mime);
            when(resource.getProperty("jcr:data")).thenReturn(data);
        }
        when(resource.hasProperty("jcr:data")).thenReturn(true);
        when(resource.hasProperty("jcr:mimeType")).thenReturn(true);
        when(mime.getString()).thenReturn(mimeType);
        when(data.getLength()).thenReturn((long) bytes.length);
        when(data.getBinary()).thenReturn(binary);
        when(binary.getStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
    }
}
