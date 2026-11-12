package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult;

import java.util.List;

/** The GraphQL view of an {@link AltTextReport}. */
@GraphQLName("AltTextReport")
@GraphQLDescription("The outcome of a generateImageAltText call")
public class GqlAltTextReport {

    private final AltTextReport report;

    public GqlAltTextReport(AltTextReport report) {
        this.report = report;
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("How many images got a new alternative text")
    public int getGenerated() {
        return (int) report.count(AltTextResult.Status.GENERATED);
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("How many images were skipped because they already had an alternative text")
    public int getSkipped() {
        return (int) report.count(AltTextResult.Status.SKIPPED);
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("How many images got no text; their message says why")
    public int getFailed() {
        return (int) report.count(AltTextResult.Status.FAILED);
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("True when images that still need a text were left, because the call reached its image "
            + "limit or the AI rate limit. Call again to continue: the images done are skipped")
    public boolean isTruncated() {
        return report.truncated();
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("One entry per image visited")
    public List<GqlAltTextImage> getImages() {
        return report.images().stream().map(GqlAltTextImage::new).toList();
    }
}
