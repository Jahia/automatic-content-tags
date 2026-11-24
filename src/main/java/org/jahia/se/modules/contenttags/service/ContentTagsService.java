package org.jahia.se.modules.contenttags.service;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Optional;

/**
 * Generates content tags for a JCR node through ai-assistant.
 */
public interface ContentTagsService {

    /**
     * Extracts the internationalized text of the given node, sends it to the model of the configured
     * ai-assistant tier and returns the suggested tags. An image also sends its picture, on the
     * tier {@code vision}.
     *
     * <p>The node must come from the calling user's session so that read permissions are
     * enforced — implementations never escalate to a system session.</p>
     *
     * @param node         the content node, in the locale whose text should be analysed
     * @param tagLanguage  the language the tags should be generated in (display name or ISO code)
     * @param numberOfTags how many tags to request; values &le; 0 fall back to the configured
     *                     default and the effective count is clamped to a sane range
     * @return the suggested tags (at most {@code numberOfTags}), or an empty list when the node
     *         has no text and no picture
     * @throws org.jahia.modules.genai.api.GenAiNotConfiguredException when ai-assistant has no
     *         model for the tier of the call ({@code llm.tier}, or {@code vision} for an image)
     * @throws org.jahia.modules.genai.api.GenAiQuotaExceededException when the calling user has
     *         reached the rate limit of ai-assistant
     * @throws org.jahia.modules.genai.api.GenAiAccessDeniedException when the calling user does not hold
     *         the permission {@code genaiAccess} on the site of the node
     * @throws org.jahia.modules.genai.api.GenAiProviderException when the provider call fails
     */
    List<String> generateTags(JCRNodeWrapper node, String tagLanguage, int numberOfTags);

    /**
     * Tells the editor form where to put the generated tags on this node. The server never writes them: the
     * form does, and the editor saves.
     *
     * @param node the content node, read through the calling user's session
     * @return the configured target, or empty when the configured property is not a string property that this
     *         node or the configured mixin declares
     */
    Optional<TagTarget> tagTarget(JCRNodeWrapper node) throws RepositoryException;

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
