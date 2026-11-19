package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.se.modules.contenttags.service.AltTextResult;

/** The GraphQL view of an {@link AltTextResult}. */
@GraphQLName("AltTextImage")
@GraphQLDescription("What happened to one image")
public class GqlAltTextImage {

    private final AltTextResult result;

    public GqlAltTextImage(AltTextResult result) {
        this.result = result;
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("The path of the image")
    public String getPath() {
        return result.path();
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("GENERATED, SKIPPED or FAILED")
    public String getStatus() {
        return result.status().name();
    }

    @GraphQLField
    @GraphQLDescription("The alternative text now saved on the image, null when it has none")
    public String getAltText() {
        return result.altText();
    }

    @GraphQLField
    @GraphQLDescription("Why the image was skipped or failed, null for a generated text")
    public String getMessage() {
        return result.message();
    }
}
