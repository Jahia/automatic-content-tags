package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.se.modules.contenttags.service.internal.AltTextUploadAction.Upload;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AltTextUploadActionTest {

    private static final String PATH = "/sites/s/files/a.png";

    private AltTextService altTextService;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Locale> opened = new ArrayList<>();
    private long now = 1_000_000_000L;
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
        action.clock = () -> now;
        action.configure(Map.of());
    }

    @Test
    void anUploadInTheEditWorkspaceQueuesTheWork() throws Exception {
        action.executeBackgroundAction(image("default", PATH, site("fr", "de", "en", "fr")));

        assertEquals(1, scheduled.size());
    }

    @Test
    void nothingIsQueuedWhenDisabledInLiveOrWithoutUploader() throws Exception {
        action.configure(Map.of("alt.generate.on.upload", "false"));
        action.executeBackgroundAction(image("default", PATH, site("en", "en")));
        action.configure(Map.of());
        action.executeBackgroundAction(image("live", PATH, site("en", "en")));
        JCRNodeWrapper anonymous = image("default", PATH, site("en", "en"));
        when(anonymous.getSession().getUser()).thenReturn(null);
        action.executeBackgroundAction(anonymous);

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void nothingIsQueuedOutsideASiteOrOnASiteWithoutTheModule() throws Exception {
        // Jahia resolves the system site for a file of a user, and that site may have the module.
        action.executeBackgroundAction(image("default", "/users/ab/cd/ef/jane/files/me.png", site("en", "en")));
        JCRSiteNode siteWithoutTheModule = site("en", "en");
        when(siteWithoutTheModule.getInstalledModulesWithAllDependencies()).thenReturn(Set.of("default"));
        action.executeBackgroundAction(image("default", PATH, siteWithoutTheModule));

        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aFullQueueRefusesTheNextUpload() throws Exception {
        action.configure(Map.of("alt.upload.max.queue", "1"));

        action.executeBackgroundAction(image("default", PATH, site("en", "en")));
        action.executeBackgroundAction(image("default", "/sites/s/files/b.png", site("en", "en")));

        assertEquals(1, scheduled.size());
    }

    @Test
    void theDefaultLanguageComesFirst() throws Exception {
        assertEquals(List.of(Locale.FRENCH, Locale.GERMAN, Locale.ENGLISH),
                AltTextUploadAction.languages(image("default", PATH, site("fr", "en", "de", "fr"))));
    }

    @Test
    void aFileThatExistedBeforeItsContentWasWrittenIsAReplacement() throws Exception {
        JCRNodeWrapper image = image("default", PATH, site("en", "en"));
        assertFalse(AltTextUploadAction.replaced(image), "no creation date");

        // A new file and a copy: the content is written when the file is created, whenever the rule runs.
        dates(image, now - 3_600_000, now - 3_600_000 + 100);
        assertFalse(AltTextUploadAction.replaced(image), "content written with the file");

        dates(image, now - 3_600_000, now);
        assertTrue(AltTextUploadAction.replaced(image));
    }

    @Test
    void eachLanguageOfAFirstUploadIsFilledWithoutOverwriting() throws Exception {
        answer(new AltTextReport(List.of(), null));

        action.generateAll(upload(false, Locale.ENGLISH, Locale.FRENCH));

        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH), opened);
        verify(altTextService, times(2)).fillAltText(any(), eq(false), eq(false), isNull());
    }

    @Test
    void aPictureThatReplacesAnotherGetsANewText() throws Exception {
        answer(new AltTextReport(List.of(), null));

        action.generateAll(upload(true, Locale.ENGLISH));

        verify(altTextService).fillAltText(any(), eq(false), eq(true), isNull());
    }

    @Test
    void aConnectorWithoutModelStopsTheOtherLanguages() throws Exception {
        when(altTextService.fillAltText(any(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new GenAiNotConfiguredException("no lite tier"));

        action.generateAll(upload(false, Locale.ENGLISH, Locale.FRENCH));

        assertEquals(List.of(Locale.ENGLISH), opened);
    }

    @Test
    void aRunStoppedByTheRateLimitOrTheProviderPausesTheQueue() throws Exception {
        answer(new AltTextReport(List.of(), PATH));

        action.generateAll(upload(false, Locale.ENGLISH, Locale.FRENCH));
        assertEquals(List.of(Locale.ENGLISH), opened);

        // The next upload of that user comes during the pause, and gets nothing.
        now += 30_000;
        action.generateAll(upload(false, Locale.ENGLISH));
        assertEquals(List.of(Locale.ENGLISH), opened);

        // Another user has a rate limit of their own, and is not paused.
        answer(new AltTextReport(List.of(), null));
        JahiaUser other = mock(JahiaUser.class);
        when(other.getUserKey()).thenReturn("/users/john");
        action.generateAll(new Upload("id", PATH, other, List.of(Locale.ITALIAN), false));
        assertEquals(List.of(Locale.ENGLISH, Locale.ITALIAN), opened);

        // After the pause, the queue works again.
        now += 31_000;
        answer(new AltTextReport(List.of(), null));
        action.generateAll(upload(false, Locale.GERMAN));
        assertEquals(List.of(Locale.ENGLISH, Locale.ITALIAN, Locale.GERMAN), opened);
    }

    @Test
    void noLimitOnTheCallsOfAUserWhenTheKeyIsZero() throws Exception {
        action.configure(Map.of("alt.upload.max.calls", "0"));
        answer(new AltTextReport(List.of(new AltTextResult(PATH, AltTextResult.Status.GENERATED, "A cat.", null)),
                null));

        action.generateAll(upload(false, Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN));

        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN), opened);
    }

    @Test
    void theUploadsOfOneUserStopAtTheirShareOfModelCalls() throws Exception {
        action.configure(Map.of("alt.upload.max.calls", "2", "alt.upload.window.seconds", "600"));
        answer(new AltTextReport(List.of(new AltTextResult(PATH, AltTextResult.Status.GENERATED, "A cat.", null)),
                null));

        action.generateAll(upload(false, Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN));
        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH), opened);

        // A skipped image costs no call, and the window forgets the old calls.
        now += 601_000;
        action.generateAll(upload(false, Locale.GERMAN));
        assertEquals(List.of(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN), opened);
    }

    private void answer(AltTextReport report) throws Exception {
        when(altTextService.fillAltText(any(), anyBoolean(), anyBoolean(), any())).thenReturn(report);
    }

    private static Upload upload(boolean replaced, Locale... languages) {
        JahiaUser uploader = mock(JahiaUser.class);
        when(uploader.getUserKey()).thenReturn("/users/jane");
        return new Upload("id", PATH, uploader, List.of(languages), replaced);
    }

    /** A site with the module enabled, its default language, and its languages. */
    private static JCRSiteNode site(String defaultLanguage, String... languages) {
        JCRSiteNode site = mock(JCRSiteNode.class);
        Set<String> all = new LinkedHashSet<>(List.of(languages));
        when(site.getLanguages()).thenReturn(all);
        when(site.getDefaultLanguage()).thenReturn(defaultLanguage);
        when(site.getInstalledModulesWithAllDependencies()).thenReturn(Set.of("default", AltTextUploadAction.MODULE));
        return site;
    }

    private static JCRNodeWrapper image(String workspace, String path, JCRSiteNode site) throws Exception {
        JCRNodeWrapper image = mock(JCRNodeWrapper.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class, RETURNS_DEEP_STUBS);
        when(session.getWorkspace().getName()).thenReturn(workspace);
        when(session.getUser()).thenReturn(mock(JahiaUser.class));
        when(image.getSession()).thenReturn(session);
        when(image.getIdentifier()).thenReturn("id");
        when(image.getPath()).thenReturn(path);
        when(image.getResolveSite()).thenReturn(site);
        return image;
    }

    /** The date the file was created, and the date its content was last written. */
    private static void dates(JCRNodeWrapper image, long created, long contentWritten) throws Exception {
        JCRPropertyWrapper createdProperty = date(created);
        when(image.hasProperty("jcr:created")).thenReturn(true);
        when(image.getProperty("jcr:created")).thenReturn(createdProperty);
        JCRNodeWrapper content = mock(JCRNodeWrapper.class);
        JCRPropertyWrapper writtenProperty = date(contentWritten);
        when(content.hasProperty("jcr:lastModified")).thenReturn(true);
        when(content.getProperty("jcr:lastModified")).thenReturn(writtenProperty);
        when(image.hasNode("jcr:content")).thenReturn(true);
        when(image.getNode("jcr:content")).thenReturn(content);
    }

    private static JCRPropertyWrapper date(long millis) throws Exception {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getDate()).thenReturn(calendar);
        return property;
    }
}
