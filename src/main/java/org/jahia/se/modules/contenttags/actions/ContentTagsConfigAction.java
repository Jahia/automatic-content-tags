package org.jahia.se.modules.contenttags.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.se.modules.contenttags.service.TagTarget;
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
import java.util.Optional;

/**
 * Read-only action exposing the tag configuration to the Content Editor dialog: the configured maximum and
 * default number of tags, and the form field that receives them ({@code llm.tag.property} and
 * {@code llm.tag.mixin}, resolved on the current node).
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
        Optional<TagTarget> target = contentTagsService.tagTarget(resource.getNode());
        if (target.isPresent()) {
            result.put("tagProperty", target.get().property())
                    .put("tagMixin", target.get().mixin() == null ? JSONObject.NULL : target.get().mixin())
                    .put("tagField", target.get().field())
                    .put("tagMultiple", target.get().multiple());
        } else {
            result.put("tagError", "The configured tag property cannot hold tags on this content");
        }
        return new ActionResult(HttpServletResponse.SC_OK, null, result);
    }
}
