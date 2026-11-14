package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.GenAiService;
import org.jahia.se.modules.contenttags.service.TagTarget;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jcr.PropertyType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentTagsServiceImplTest {

    private ContentTagsServiceImpl service;
    /** What the node type registry answers for the configured property; {@code null} when none is declared. */
    private ExtendedPropertyDefinition definition;
    private TargetProperty resolvedTarget;

    @BeforeEach
    void setUp() {
        service = new ContentTagsServiceImpl() {
            @Override
            ExtendedPropertyDefinition definitionOf(JCRNodeWrapper node, TargetProperty target) {
                resolvedTarget = target;
                return definition;
            }
        };
        service.genAi = mock(GenAiService.class);
        service.configure(Map.of());
    }

    @Test
    void theDefaultTargetIsTheTagListOfJmixTagged() throws Exception {
        definition = definition("jmix:tagged", PropertyType.STRING, true);

        Optional<TagTarget> target = service.tagTarget(mock(JCRNodeWrapper.class));

        assertEquals(new TargetProperty("j:tagList", "jmix:tagged"), resolvedTarget);
        assertEquals(Optional.of(new TagTarget("j:tagList", "jmix:tagged", "jmix:tagged_j:tagList", true)), target);
    }

    @Test
    void aConfiguredTargetGivesTheFieldOfItsDeclaringType() throws Exception {
        service.configure(Map.of("llm.tag.property", "j:keywords", "llm.tag.mixin", "jmix:keywords"));
        definition = definition("jmix:keywords", PropertyType.STRING, true);

        assertEquals(Optional.of(new TagTarget("j:keywords", "jmix:keywords", "jmix:keywords_j:keywords", true)),
                service.tagTarget(mock(JCRNodeWrapper.class)));
    }

    @Test
    void aSingleValuedTargetWithoutMixinIsAccepted() throws Exception {
        service.configure(Map.of("llm.tag.property", "seoKeywords", "llm.tag.mixin", ""));
        definition = definition("jnt:article", PropertyType.STRING, false);

        assertEquals(Optional.of(new TagTarget("seoKeywords", null, "jnt:article_seoKeywords", false)),
                service.tagTarget(mock(JCRNodeWrapper.class)));
    }

    @Test
    void aMissingOrNonTextPropertyIsNoTarget() throws Exception {
        definition = null;
        assertTrue(service.tagTarget(mock(JCRNodeWrapper.class)).isEmpty());

        definition = definition("jmix:tagged", PropertyType.WEAKREFERENCE, true);
        assertTrue(service.tagTarget(mock(JCRNodeWrapper.class)).isEmpty());
    }

    @Test
    void theKeysOfTheRemovedProvidersAreDetected() {
        Map<String, Object> properties = Map.of(
                "llm.provider", "anthropic",
                "anthropic.api.key", "secret",
                "openai.model", "gpt",
                "deepseek.base.url", "https://example.com",
                "llm.tier", "lite",
                "llm.tag.count.max", "20");

        assertEquals(List.of("anthropic.api.key", "deepseek.base.url", "llm.provider", "openai.model"),
                ContentTagsServiceImpl.legacyKeys(properties));
    }

    @Test
    void aCurrentConfigurationHasNoLegacyKey() {
        assertTrue(ContentTagsServiceImpl.legacyKeys(Map.of("llm.tier", "lite", "alt.tier", "lite")).isEmpty());
        assertTrue(ContentTagsServiceImpl.legacyKeys(null).isEmpty());
    }

    private static ExtendedPropertyDefinition definition(String declaringType, int type, boolean multiple) {
        ExtendedNodeType declaring = mock(ExtendedNodeType.class);
        when(declaring.getName()).thenReturn(declaringType);
        ExtendedPropertyDefinition result = mock(ExtendedPropertyDefinition.class);
        when(result.getDeclaringNodeType()).thenReturn(declaring);
        when(result.getRequiredType()).thenReturn(type);
        when(result.isMultiple()).thenReturn(multiple);
        return result;
    }
}
