package org.jahia.se.modules.contenttags.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
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
            JSONObject error = new JSONObject().put("error", "Missing required parameter: tagLanguage");
            return new ActionResult(HttpServletResponse.SC_BAD_REQUEST, null, error);
        }

        return tags(resource.getNode(), tagLanguage, parseInt(getParameter(parameters, "numberOfTags")));
    }

    /** The tags of the node, or the error to show in the dialog. Package-private for the unit tests. */
    ActionResult tags(JCRNodeWrapper node, String tagLanguage, int numberOfTags) throws Exception {
        try {
            List<String> tags = contentTagsService.generateTags(node, tagLanguage, numberOfTags);
            JSONObject result = new JSONObject().put("tags", new JSONArray(tags));
            return new ActionResult(HttpServletResponse.SC_OK, null, result);
        } catch (GenAiNotConfiguredException e) {
            LOGGER.warn("Tag generation is not available: {}", e.getMessage());
            JSONObject error = new JSONObject().put("error",
                    "Tag generation is not available: ai-assistant has no model configured for it");
            return new ActionResult(HttpServletResponse.SC_SERVICE_UNAVAILABLE, null, error);
        } catch (GenAiQuotaExceededException e) {
            JSONObject error = new JSONObject().put("error", "Too many AI requests, try again later");
            return new ActionResult(429, null, error);
        } catch (GenAiProviderException e) {
            if (!e.isRetryable()) {
                return failed(node, e);
            }
            LOGGER.warn("The AI provider is busy or unavailable for node {}: {}", node.getPath(), e.getMessage());
            JSONObject error = new JSONObject().put("error", "The AI provider is busy or unavailable, try again later");
            return new ActionResult(e.statusCode().filter(status -> status == 429).isPresent()
                    ? 429 : HttpServletResponse.SC_SERVICE_UNAVAILABLE, null, error);
        } catch (Exception e) {
            return failed(node, e);
        }
    }

    private static ActionResult failed(JCRNodeWrapper node, Exception e) throws Exception {
        LOGGER.error("Tag generation failed for node {}", node.getPath(), e);
        JSONObject error = new JSONObject().put("error", "Tag generation failed, see server log");
        return new ActionResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null, error);
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
