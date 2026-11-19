package org.jahia.se.modules.contenttags.graphql;

import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Registers the GraphQL extensions of this module. The provider scans the bundle for the classes
 * annotated with {@code @GraphQLTypeExtension}, such as {@link AltTextMutationExtension}.
 */
@Component(service = DXGraphQLExtensionsProvider.class, immediate = true)
public class ContentTagsGraphQLExtensionsProvider implements DXGraphQLExtensionsProvider {
}
