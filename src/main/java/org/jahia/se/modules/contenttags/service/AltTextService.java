package org.jahia.se.modules.contenttags.service;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;

/**
 * Writes the alternative text of images into a configured property ({@code alt.property}, default
 * {@value #DEFAULT_ALT_PROPERTY}), with a vision model reached through genai-connector.
 *
 * <p>Every read and every write goes through the session of the node that the caller passes, so the
 * permissions of the calling user apply. The text is written in the locale of that session.</p>
 */
public interface AltTextService {

    /**
     * The mixin added to an image before the text is written, unless {@code alt.mixin} names another one or
     * none. The module defines it; it extends {@code jmix:image}.
     */
    String DEFAULT_ALT_MIXIN = "jmix:jahiaAlt";

    /** The property that holds the alternative text, unless {@code alt.property} names another one. */
    String DEFAULT_ALT_PROPERTY = "jahia_alt";

    /**
     * Generates and saves the alternative text of {@code node} when it is an image, and of the
     * images below it when {@code includeSubNodes} is true.
     *
     * <p>The configured property must be a single-valued string declared by the image's types or by the
     * configured mixin; otherwise the image fails before any model call. An image that already has a value
     * in that property, in the session locale for an i18n property, is skipped unless
     * {@code overwrite} is true. Each image is saved on its own, so the images done before a failure
     * keep their text. At most {@link #getMaxImages()} images get a model call per invocation; the
     * report says when more were left.</p>
     *
     * @param node            the image or the folder, read through the calling user's session in the
     *                        target locale
     * @param includeSubNodes whether to process the images below {@code node}
     * @param overwrite       whether to replace an existing alternative text
     * @return one result per image visited
     * @throws org.jahia.modules.genai.api.GenAiNotConfiguredException when genai-connector has no
     *         model for the tier {@code vision}
     * @throws RepositoryException when the images cannot be listed
     */
    AltTextReport fillAltText(JCRNodeWrapper node, boolean includeSubNodes, boolean overwrite)
            throws RepositoryException;

    /** @return the most images that one invocation sends to the model ({@code alt.max.images}) */
    int getMaxImages();
}
