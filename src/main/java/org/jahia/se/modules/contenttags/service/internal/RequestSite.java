package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;

/**
 * The site that a model call works for. ai-assistant checks that the calling user holds the permission
 * {@code genaiAccess} on that site before it sends anything to a model, and names the site in its audit log.
 */
final class RequestSite {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestSite.class);

    private RequestSite() {
    }

    /**
     * @param node the content or the image that the call is about, read through the calling user's session
     * @return the key of the site Jahia resolves for the node, which is the system site for a node outside
     *         {@code /sites}; {@code null} when the site cannot be resolved, and the call then names no site
     */
    static String of(JCRNodeWrapper node) {
        try {
            JCRSiteNode site = node.getResolveSite();
            return site == null ? null : site.getSiteKey();
        } catch (RepositoryException e) {
            LOGGER.debug("Unable to resolve the site of {}", node.getPath(), e);
            return null;
        }
    }
}
