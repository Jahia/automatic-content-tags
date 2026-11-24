package org.jahia.se.modules.contenttags.service;

/**
 * Where the Content Editor form puts the generated tags of one node, from the configuration
 * ({@code llm.tag.property} and {@code llm.tag.mixin}).
 *
 * @param property the property that receives the tags, such as {@code j:tagList}
 * @param mixin    the mixin the form activates first, such as {@code jmix:tagged}, or {@code null}
 * @param field    the form field of the property, {@code <declaring type>_<property>}, such as
 *                 {@code jmix:tagged_j:tagList}
 * @param multiple whether the property holds one value per tag; otherwise the tags are joined with ", "
 */
public record TagTarget(String property, String mixin, String field, boolean multiple) {
}
