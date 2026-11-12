package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDefaultValue;
import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.node.GqlJcrWrongInputException;
import org.jahia.modules.graphql.provider.dxm.util.GqlUtils;
import org.jahia.osgi.BundleUtils;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.utils.LanguageCodeConverters;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.util.Locale;

/**
 * The GraphQL entry point of the alternative-text generation:
 * {@code mutation { generateImageAltText(path: "...", language: "en", includeSubNodes: true) { ... } }}.
 *
 * <p>The mutation runs in the edit workspace, under the session of the calling user, so it lists only
 * the images that user can read and writes only where that user can edit. A guest is refused.</p>
 */
@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
public final class AltTextMutationExtension {

    private AltTextMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("generateImageAltText")
    @GraphQLDescription("Generates the alternative text of an image, or of the images below a folder, with the "
            + "vision model of genai-connector, and saves it in the i18n property jahia_alt (mixin jmix:jahiaAlt) "
            + "in the edit workspace. Each image is saved on its own; nothing is published.")
    public static GqlAltTextReport generateImageAltText(
            @GraphQLName("path") @GraphQLNonNull
            @GraphQLDescription("The path of an image (jmix:image) or of a node above images") String path,
            @GraphQLName("language") @GraphQLNonNull
            @GraphQLDescription("The language code of the text to write, such as en or fr") String language,
            @GraphQLName("includeSubNodes") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Also process the images below the node. Default false") Boolean includeSubNodes,
            @GraphQLName("overwrite") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Replace an existing alternative text. Default false: such an image is skipped")
            Boolean overwrite) {
        JahiaUser user = JCRSessionFactory.getInstance().getCurrentUser();
        if (user == null || JahiaUserManagerService.isGuest(user)) {
            throw new DataFetchingException("Generating alternative text requires an authenticated user");
        }
        Locale locale = LanguageCodeConverters.languageCodeToLocale(language);
        if (locale == null || language.isBlank()) {
            throw new GqlJcrWrongInputException("Unknown language: " + language);
        }
        boolean subNodes = Boolean.TRUE.equals(includeSubNodes);
        try {
            JCRSessionWrapper session = JCRSessionFactory.getInstance()
                    .getCurrentUserSession(Constants.EDIT_WORKSPACE, locale);
            JCRNodeWrapper node = session.getNode(path);
            checkSiteLanguage(node, language);
            if (!subNodes && !node.isNodeType("jmix:image")) {
                throw new GqlJcrWrongInputException(path + " is not an image (jmix:image); set includeSubNodes "
                        + "to true to process the images below it");
            }
            return new GqlAltTextReport(service().fillAltText(node, subNodes, Boolean.TRUE.equals(overwrite)));
        } catch (PathNotFoundException e) {
            throw new GqlJcrWrongInputException("No node at " + path + " that the current user can read", e);
        } catch (GenAiNotConfiguredException e) {
            throw new DataFetchingException("Alternative text is not available: genai-connector has no model "
                    + "configured for it");
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
    }

    /** A site only gets text in one of its own languages. */
    private static void checkSiteLanguage(JCRNodeWrapper node, String language) throws RepositoryException {
        JCRSiteNode site = node.getResolveSite();
        if (site != null && site.getLanguages() != null && !site.getLanguages().isEmpty()
                && !site.getLanguages().contains(language)) {
            throw new GqlJcrWrongInputException("The site " + site.getSiteKey() + " has no language " + language);
        }
    }

    private static AltTextService service() {
        AltTextService service = BundleUtils.getOsgiService(AltTextService.class, null);
        if (service == null) {
            throw new DataFetchingException("The alternative text service is not started");
        }
        return service;
    }
}
