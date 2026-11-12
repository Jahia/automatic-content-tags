package org.jahia.se.modules.contenttags.service;

import org.jahia.services.content.JCRNodeWrapper;

import java.util.List;

/**
 * Generates content tags for a JCR node through genai-connector.
 */
public interface ContentTagsService {

    /**
     * Extracts the internationalized text of the given node, sends it to the model of the configured
     * genai-connector tier and returns the suggested tags.
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
     * @throws org.jahia.modules.genai.api.GenAiNotConfiguredException when genai-connector has no
     *         model for the configured tier ({@code llm.tier})
     * @throws org.jahia.modules.genai.api.GenAiQuotaExceededException when the calling user has
     *         reached the rate limit of genai-connector
     * @throws org.jahia.modules.genai.api.GenAiProviderException when the provider call fails
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
