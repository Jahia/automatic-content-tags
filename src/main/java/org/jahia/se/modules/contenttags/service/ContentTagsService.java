package org.jahia.se.modules.contenttags.service;

import org.jahia.services.content.JCRNodeWrapper;

import java.util.List;

/**
 * Generates content tags for a JCR node using the configured LLM provider.
 */
public interface ContentTagsService {

    /**
     * Extracts the internationalized text of the given node, sends it to the configured
     * LLM provider and returns the suggested tags.
     *
     * <p>The node must come from the calling user's session so that read permissions are
     * enforced — implementations never escalate to a system session.</p>
     *
     * @param node         the content node, in the locale whose text should be analysed
     * @param tagLanguage  the language the tags should be generated in (display name or ISO code)
     * @param numberOfTags how many tags to request; values &le; 0 fall back to the configured
     *                     default and the effective count is clamped to a sane range
     * @return the suggested tags (at most {@code numberOfTags}), or an empty list when the node
     *         has no text
     * @throws IllegalStateException when no provider matches the configuration, the provider
     *                               has no API key, or the provider call fails
     */
    List<String> generateTags(JCRNodeWrapper node, String tagLanguage, int numberOfTags);

    /**
     * @return the default number of tags used when a request does not specify one
     */
    int getDefaultTagCount();

    /**
     * @return the maximum number of tags a request may generate; the editor UI uses this as the
     *         input ceiling and the service enforces it on every request
     */
    int getMaxTagCount();
}
