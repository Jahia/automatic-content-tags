package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AltTextUploadActionTest {

    private AltTextService altTextService;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Locale> opened = new ArrayList<>();
    private AltTextUploadAction action;

    @BeforeEach
    void setUp() {
        altTextService = mock(AltTextService.class);
        action = new AltTextUploadAction() {
            @Override
            void schedule(Runnable work) {
                scheduled.add(work);
            }

            @Override
            JCRNodeWrapper imageIn(String identifier, Locale language) {
                opened.add(language);
                return mock(JCRNodeWrapper.class);
            }
        };
        action.altTextService = altTextService;
        action.configure(Map.of());
    }

    @Test
    void anUploadInTheEditWorkspaceQueuesTheWork() throws Exception {
        action.executeBackgroundAction(image("default", site("fr", "de", "en", "fr")));

        assertEquals(1, scheduled.size());
    }

    @Test
    void nothingIsQueuedWhenDisabledInLiveOrWithoutUploader() throws Exception {
        action.configure(Map.of("alt.generate.on.upload", "false"));
        action.executeBackgroundAction(image("default", site("en", "en")));
        action.configure(Map.of());
        action.executeBackgroundAction(image("live", site("en", "en")));
        JCRNodeWrapper anonymous = image("default", site("en", "en"));
        when(anonymous.getSession().getUser()).thenReturn(null);
        action.executeBackgroundAction(anonymous);

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void theDefaultLanguageComesFirst() throws Exception {
        assertEquals(List.of(Locale.FRENCH, Locale.GERMAN, Locale.ENGLISH),
                AltTextUploadAction.languages(image("default", site("fr", "en", "de", "fr"))));
    }

    @Test
    void eachLanguageIsFilledWithoutOverwriting() throws Exception {
        when(altTextService.fillAltText(any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new AltTextReport(List.of(), null));

        action.generateAll("id", List.of(Locale.ENGLISH, Locale.FRENCH));

        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH), opened);
        verify(altTextService, times(2)).fillAltText(any(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void aConnectorWithoutModelStopsTheOtherLanguages() throws Exception {
        when(altTextService.fillAltText(any(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new GenAiNotConfiguredException("no lite tier"));

        action.generateAll("id", List.of(Locale.ENGLISH, Locale.FRENCH));

        assertEquals(List.of(Locale.ENGLISH), opened);
    }

    @Test
    void aRunStoppedByTheRateLimitOrTheProviderStopsTheOtherLanguages() throws Exception {
        when(altTextService.fillAltText(any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(new AltTextReport(List.of(), "/sites/s/files/a.png"));

        action.generateAll("id", List.of(Locale.ENGLISH, Locale.FRENCH));

        assertEquals(List.of(Locale.ENGLISH), opened);
    }

    private static JCRSiteNode site(String defaultLanguage, String... languages) {
        JCRSiteNode site = mock(JCRSiteNode.class);
        Set<String> all = new LinkedHashSet<>(List.of(languages));
        when(site.getLanguages()).thenReturn(all);
        when(site.getDefaultLanguage()).thenReturn(defaultLanguage);
        return site;
    }

    private static JCRNodeWrapper image(String workspace, JCRSiteNode site) throws Exception {
        JCRNodeWrapper image = mock(JCRNodeWrapper.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class, RETURNS_DEEP_STUBS);
        when(session.getWorkspace().getName()).thenReturn(workspace);
        when(session.getUser()).thenReturn(mock(JahiaUser.class));
        when(image.getSession()).thenReturn(session);
        when(image.getIdentifier()).thenReturn("id");
        when(image.getPath()).thenReturn("/sites/s/files/a.png");
        when(image.getResolveSite()).thenReturn(site);
        return image;
    }
}
