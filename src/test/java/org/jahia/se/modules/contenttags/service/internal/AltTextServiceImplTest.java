package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionRequest;
import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.modules.genai.api.StopReason;
import org.jahia.modules.genai.api.Usage;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult.Status;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.jahia.services.query.QueryResultWrapper;
import javax.jcr.InvalidItemStateException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AltTextServiceImplTest {

    private GenAiService genAi;
    private JCRSessionWrapper session;
    private AltTextServiceImpl service;

    @BeforeEach
    void setUp() {
        genAi = mock(GenAiService.class);
        session = mock(JCRSessionWrapper.class, RETURNS_DEEP_STUBS);
        when(session.getLocale()).thenReturn(Locale.FRENCH);
        service = new AltTextServiceImpl();
        service.genAi = genAi;
        service.configure(Map.of());
    }

    @Test
    void anImageGetsATextFromTheModelAndItIsSaved() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", null, true);
        answer("\"A grey cat asleep on a sofa.\"");

        AltTextReport report = service.fillAltText(image, false, false);

        assertEquals(1, report.count(Status.GENERATED));
        assertFalse(report.truncated());
        assertEquals("A grey cat asleep on a sofa.", report.images().get(0).altText());
        verify(image).addMixin(AltTextService.ALT_MIXIN);
        verify(image).setProperty(AltTextService.ALT_PROPERTY, "A grey cat asleep on a sofa.");
        verify(session).save();

        ArgumentCaptor<CompletionRequest> request = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(genAi).complete(request.capture());
        assertEquals(ModelTier.LITE, request.getValue().tier());
        assertEquals(1, request.getValue().images().size());
        assertEquals("image/png", request.getValue().images().get(0).mediaType());
        assertTrue(request.getValue().input().contains("French"));
        assertTrue(request.getValue().input().contains("cat.png"));
    }

    @Test
    void anExistingTextIsKeptUnlessOverwriteIsSet() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", "Already written", true);
        answer("A grey cat.");

        AltTextReport kept = service.fillAltText(image, false, false);
        assertEquals(1, kept.count(Status.SKIPPED));
        assertEquals("Already written", kept.images().get(0).altText());
        verify(genAi, never()).complete(any());

        AltTextReport replaced = service.fillAltText(image, false, true);
        assertEquals(1, replaced.count(Status.GENERATED));
        verify(image).setProperty(AltTextService.ALT_PROPERTY, "A grey cat.");
    }

    @Test
    void anImageTheUserCannotEditCostsNoModelCall() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", null, false);

        AltTextReport report = service.fillAltText(image, false, false);

        assertEquals(1, report.count(Status.FAILED));
        verify(genAi, never()).complete(any());
        verify(image, never()).setProperty(anyString(), anyString());
    }

    @Test
    void subNodesAreProcessedUpToTheImageLimit() throws Exception {
        service.configure(Map.of("alt.max.images", "2"));
        JCRNodeWrapper folder = folder("/sites/s/files/album",
                image("/sites/s/files/album/a.png", null, true),
                image("/sites/s/files/album/b.png", "Done before", true),
                image("/sites/s/files/album/c.png", null, true),
                image("/sites/s/files/album/d.png", null, true));
        answer("A photo.");

        AltTextReport report = service.fillAltText(folder, true, false);

        assertEquals(2, report.count(Status.GENERATED));
        assertEquals(1, report.count(Status.SKIPPED));
        assertTrue(report.truncated());
        verify(genAi, times(2)).complete(any());
    }

    @Test
    void theRateLimitStopsTheRunAndKeepsWhatWasDone() throws Exception {
        JCRNodeWrapper folder = folder("/sites/s/files/album",
                image("/sites/s/files/album/a.png", null, true),
                image("/sites/s/files/album/b.png", null, true),
                image("/sites/s/files/album/c.png", null, true));
        when(genAi.complete(any()))
                .thenReturn(result("A photo."))
                .thenThrow(new GenAiQuotaExceededException("limit"));

        AltTextReport report = service.fillAltText(folder, true, false);

        assertEquals(1, report.count(Status.GENERATED));
        assertEquals(1, report.count(Status.FAILED));
        assertTrue(report.truncated());
        verify(genAi, times(2)).complete(any());
    }

    @Test
    void aProviderFailureFailsOneImageAndTheRunGoesOn() throws Exception {
        JCRNodeWrapper folder = folder("/sites/s/files/album",
                image("/sites/s/files/album/a.png", null, true),
                image("/sites/s/files/album/b.png", null, true));
        when(genAi.complete(any()))
                .thenThrow(new GenAiProviderException("boom", false, 500))
                .thenReturn(result("A photo."));

        AltTextReport report = service.fillAltText(folder, true, false);

        assertEquals(1, report.count(Status.FAILED));
        assertEquals(1, report.count(Status.GENERATED));
        assertFalse(report.truncated());
    }

    @Test
    void aTextSavedMeanwhileByAnotherNodeIsKept() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", null, true);
        answer("A grey cat.");
        doThrow(new InvalidItemStateException("modified externally")).when(session).save();
        // After the refresh, the session sees the text that the other node saved.
        JCRPropertyWrapper saved = mock(JCRPropertyWrapper.class);
        when(saved.getString()).thenReturn("Written elsewhere");
        doAnswer(invocation -> {
            when(image.hasProperty(AltTextService.ALT_PROPERTY)).thenReturn(true);
            when(image.getProperty(AltTextService.ALT_PROPERTY)).thenReturn(saved);
            return null;
        }).when(session).refresh(false);

        AltTextReport report = service.fillAltText(image, false, false);

        assertEquals(1, report.count(Status.SKIPPED));
        assertEquals("Written elsewhere", report.images().get(0).altText());
    }

    @Test
    void aConflictWithNoTextSavedFailsTheImage() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", null, true);
        answer("A grey cat.");
        doThrow(new InvalidItemStateException("modified externally")).when(session).save();

        AltTextReport report = service.fillAltText(image, false, false);

        assertEquals(1, report.count(Status.FAILED));
        verify(session).refresh(false);
    }

    @Test
    void aStaleReadIsRetriedOnAFreshCopy() throws Exception {
        JCRNodeWrapper stale = image("/sites/s/files/cat.png", null, true);
        JCRNodeWrapper fresh = image("/sites/s/files/cat.png", null, true);
        when(stale.hasProperty(AltTextService.ALT_PROPERTY)).thenThrow(new InvalidItemStateException("gone"));
        when(session.getNode("/sites/s/files/cat.png")).thenReturn(fresh);
        answer("A grey cat.");

        AltTextReport report = service.fillAltText(stale, false, false);

        assertEquals(1, report.count(Status.GENERATED));
        verify(fresh).setProperty(AltTextService.ALT_PROPERTY, "A grey cat.");
    }

    @Test
    void anUnreadableImageFailsAloneAndTheRunGoesOn() throws Exception {
        JCRNodeWrapper broken = image("/sites/s/files/album/a.png", null, true);
        when(broken.hasProperty(AltTextService.ALT_PROPERTY)).thenThrow(new javax.jcr.RepositoryException("broken"));
        JCRNodeWrapper folder = folder("/sites/s/files/album", broken, image("/sites/s/files/album/b.png", null, true));
        answer("A photo.");

        AltTextReport report = service.fillAltText(folder, true, false);

        assertEquals(1, report.count(Status.FAILED));
        assertEquals(1, report.count(Status.GENERATED));
    }

    @Test
    void aConnectorWithoutModelIsAnErrorOfTheCall() throws Exception {
        JCRNodeWrapper image = image("/sites/s/files/cat.png", null, true);
        when(genAi.complete(any())).thenThrow(new GenAiNotConfiguredException("no lite tier"));

        assertThrows(GenAiNotConfiguredException.class, () -> service.fillAltText(image, false, false));
    }

    private void answer(String text) {
        when(genAi.complete(any())).thenReturn(result(text));
    }

    private static CompletionResult result(String text) {
        return new CompletionResult(text, StopReason.COMPLETE, Usage.UNKNOWN, ModelTier.LITE, "fake", "m");
    }

    private JCRNodeWrapper image(String path, String existingAlt, boolean canEdit) throws Exception {
        JCRNodeWrapper image = mock(JCRNodeWrapper.class);
        when(image.getPath()).thenReturn(path);
        when(image.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(image.getSession()).thenReturn(session);
        when(image.isNodeType("jmix:image")).thenReturn(true);
        when(image.hasPermission("jcr:write_default")).thenReturn(canEdit);
        if (existingAlt != null) {
            JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
            when(property.getString()).thenReturn(existingAlt);
            when(image.hasProperty(AltTextService.ALT_PROPERTY)).thenReturn(true);
            when(image.getProperty(AltTextService.ALT_PROPERTY)).thenReturn(property);
        }
        ImagePayloadTest.resource(image, "jcr:content", "image/png", new byte[] {1, 2, 3});
        return image;
    }

    private JCRNodeWrapper folder(String path, JCRNodeWrapper... images) throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        when(folder.getPath()).thenReturn(path);
        when(folder.getSession()).thenReturn(session);
        Iterator<JCRNodeWrapper> found = List.of(images).iterator();
        JCRNodeIteratorWrapper nodes = mock(JCRNodeIteratorWrapper.class);
        when(nodes.hasNext()).thenAnswer(invocation -> found.hasNext());
        when(nodes.nextNode()).thenAnswer(invocation -> found.next());
        QueryResultWrapper result = mock(QueryResultWrapper.class);
        when(result.getNodes()).thenReturn(nodes);
        when(session.getWorkspace().getQueryManager().createQuery(anyString(), anyString()).execute())
                .thenReturn(result);
        return folder;
    }
}
