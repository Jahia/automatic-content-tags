package org.jahia.se.modules.contenttags.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.modules.genai.api.GenAiAccessDeniedException;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Render action called by the Content Editor dialog to generate tags for the current node.
 *
 * <p>Endpoint: {@code POST /cms/editframe/default/{lang}{path}.generateContentTagsAction.do}
 * with a required {@code tagLanguage} parameter and an optional {@code numberOfTags} parameter.
 * Requires an authenticated user with write permission on the target node; the node is read
 * through the caller's session.</p>
 */
@Component(service = Action.class, immediate = true)
public class GenerateContentTagsAction extends Action {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateContentTagsAction.class);

    private ContentTagsService contentTagsService;

    @Activate
    public void activate() {
        setName("generateContentTagsAction");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jcr:write_default");
        setRequiredWorkspace("default");
        setRequiredMethods("POST");
    }

    @Reference(service = ContentTagsService.class)
    public void setContentTagsService(ContentTagsService contentTagsService) {
        this.contentTagsService = contentTagsService;
    }

    @Override
    public ActionResult doExecute(HttpServletRequest request, RenderContext renderContext,
            Resource resource, JCRSessionWrapper session, Map<String, List<String>> parameters,
            URLResolver urlResolver) throws Exception {
        String tagLanguage = getParameter(parameters, "tagLanguage");
        if (tagLanguage == null || tagLanguage.isBlank() || "void".equals(tagLanguage)) {
            return error(HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: tagLanguage");
        }

        return tags(resource.getNode(), tagLanguage, parseInt(getParameter(parameters, "numberOfTags")));
    }

    /** The tags of the node, or the error to show in the dialog. Package-private for the unit tests. */
    ActionResult tags(JCRNodeWrapper node, String tagLanguage, int numberOfTags) throws JSONException {
        try {
            List<String> tags = contentTagsService.generateTags(node, tagLanguage, numberOfTags);
            JSONObject result = new JSONObject().put("tags", new JSONArray(tags));
            return new ActionResult(HttpServletResponse.SC_OK, null, result);
        } catch (GenAiNotConfiguredException e) {
            LOGGER.warn("Tag generation is not available: {}", e.getMessage());
            return error(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Tag generation is not available: ai-assistant has no model configured for it");
        } catch (GenAiAccessDeniedException e) {
            LOGGER.info("Tag generation refused for node {}: {}", node.getPath(), e.getMessage());
            return error(HttpServletResponse.SC_FORBIDDEN,
                    "The permission genaiAccess is required on this site to generate tags");
        } catch (GenAiQuotaExceededException e) {
            return error(429, "Too many AI requests, try again later");
        } catch (GenAiProviderException e) {
            if (!e.isRetryable()) {
                return failed(node, e);
            }
            LOGGER.warn("The AI provider is busy or unavailable for node {}: {}", node.getPath(), e.getMessage());
            return error(e.statusCode().filter(status -> status == 429).isPresent()
                    ? 429 : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The AI provider is busy or unavailable, try again later");
        } catch (Exception e) {
            return failed(node, e);
        }
    }

    private static ActionResult failed(JCRNodeWrapper node, Exception e) throws JSONException {
        LOGGER.error("Tag generation failed for node {}", node.getPath(), e);
        return error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Tag generation failed, see server log");
    }

    /** The answer the dialog shows as an error: the HTTP status, and the message under the key {@code error}. */
    private static ActionResult error(int status, String message) throws JSONException {
        return new ActionResult(status, null, new JSONObject().put("error", message));
    }

    /**
     * @param value the raw parameter value
     * @return the parsed positive integer, or {@code 0} when absent or not a number — the service
     *         treats {@code 0} as "use the configured default"
     */
    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
