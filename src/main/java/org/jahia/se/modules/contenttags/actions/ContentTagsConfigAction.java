package org.jahia.se.modules.contenttags.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Read-only action exposing the tag-count configuration to the Content Editor dialog so the
 * "number of tags" input can reflect the administrator-configured maximum and default.
 *
 * <p>Endpoint: {@code POST /cms/editframe/default/{lang}{path}.contentTagsConfig.do} (POST because
 * Jahia gates GET render actions; it is CSRF-whitelisted alongside the generation action). Requires
 * an authenticated user with read permission on the node; it performs no mutation and no LLM call.</p>
 */
@Component(service = Action.class, immediate = true)
public class ContentTagsConfigAction extends Action {

    private ContentTagsService contentTagsService;

    @Activate
    public void activate() {
        setName("contentTagsConfig");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jcr:read_default");
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
        JSONObject result = new JSONObject()
                .put("defaultTags", contentTagsService.getDefaultTagCount())
                .put("maxTags", contentTagsService.getMaxTagCount());
        return new ActionResult(HttpServletResponse.SC_OK, null, result);
    }
}
