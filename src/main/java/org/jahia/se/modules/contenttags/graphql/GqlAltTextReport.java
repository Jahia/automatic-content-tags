package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDefaultValue;
import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.modules.graphql.provider.dxm.util.GqlUtils;
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
    @GraphQLDescription("True when images that may still need a text were left, because the call reached its "
            + "image limit, its time limit, the AI rate limit, or a busy AI provider. Call again with resumeFrom to "
            + "continue")
    public boolean isTruncated() {
        return report.truncated();
    }

    @GraphQLField
    @GraphQLDescription("The path of the first image left when the call stopped early; pass it as the resumeFrom "
            + "argument of the next call. Null when every image was visited")
    public String getResumeFrom() {
        return report.resumeFrom();
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("One entry per image visited, in path order. The skipped images are counted in skipped "
            + "and listed only with includeSkipped, so that a large folder keeps a small answer")
    public List<GqlAltTextImage> getImages(
            @GraphQLName("includeSkipped") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Also list the images skipped because they already had a text. Default false")
            Boolean includeSkipped) {
        return report.images().stream()
                .filter(image -> Boolean.TRUE.equals(includeSkipped) || image.status() != AltTextResult.Status.SKIPPED)
                .map(GqlAltTextImage::new)
                .toList();
    }
}
