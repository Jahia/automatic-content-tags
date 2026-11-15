package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.se.modules.contenttags.service.AltTextReport;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.rules.BackgroundAction;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.utils.LanguageCodeConverters;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Generates the alternative text of an image right after it is uploaded, when {@code alt.generate.on.upload} is
 * true (the default).
 *
 * <p>The rule "Set alternative text mixin on image update" of {@code META-INF/rules.drl} calls this action on the
 * same trigger as Jahia's {@code jmix:image}. The rule runs inside the save of the upload, so the action only
 * queues the work and returns: a single background thread generates the text a few seconds later, after the
 * upload's own rules (thumbnails, {@code jmix:image}) have saved. It writes as the uploader, in each language of the
 * image's site, through {@link AltTextService}, and never replaces an existing text.</p>
 */
@Component(service = BackgroundAction.class, configurationPid = ConfigValues.PID, immediate = true)
public class AltTextUploadAction implements BackgroundAction {

    /** The name the rule uses to call this action. */
    static final String NAME = "automaticContentTagsAltTextOnUpload";

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextUploadAction.class);
    private static final long DELAY_SECONDS = 5;

    // Package-private for the unit tests, which inject a mock.
    @Reference
    AltTextService altTextService;

    private volatile boolean enabled = true;
    private ScheduledExecutorService executor;

    @Activate
    void activate(Map<String, Object> properties) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "automatic-content-tags-alt-text");
            thread.setDaemon(true);
            return thread;
        });
        configure(properties);
    }

    @Modified
    void configure(Map<String, Object> properties) {
        enabled = ConfigValues.of(properties).bool("alt.generate.on.upload", true);
        LOGGER.info("Automatic Content Tags: alternative text on upload {}", enabled ? "enabled" : "disabled");
    }

    @Deactivate
    void deactivate() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void executeBackgroundAction(JCRNodeWrapper node) {
        if (!enabled) {
            return;
        }
        try {
            if (!Constants.EDIT_WORKSPACE.equals(node.getSession().getWorkspace().getName())) {
                return;
            }
            String identifier = node.getIdentifier();
            JahiaUser uploader = node.getSession().getUser();
            if (uploader == null) {
                LOGGER.debug("No uploader for the image {}: no alternative text on upload", node.getPath());
                return;
            }
            List<Locale> languages = languages(node);
            if (languages.isEmpty()) {
                LOGGER.debug("No language for the image {}: no alternative text on upload", node.getPath());
                return;
            }
            schedule(() -> generate(identifier, uploader, languages));
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to queue the alternative text of {}", node.getPath(), e);
        }
    }

    /** The site languages, default language first; the session language for an image outside a site. */
    static List<Locale> languages(JCRNodeWrapper node) throws RepositoryException {
        List<Locale> languages = new ArrayList<>();
        JCRSiteNode site = node.getResolveSite();
        if (site != null && site.getLanguages() != null && !site.getLanguages().isEmpty()) {
            String defaultLanguage = site.getDefaultLanguage();
            if (defaultLanguage != null && site.getLanguages().contains(defaultLanguage)) {
                languages.add(LanguageCodeConverters.languageCodeToLocale(defaultLanguage));
            }
            site.getLanguages().stream().sorted().filter(language -> !language.equals(defaultLanguage))
                    .forEach(language -> languages.add(LanguageCodeConverters.languageCodeToLocale(language)));
        } else if (node.getSession().getLocale() != null) {
            languages.add(node.getSession().getLocale());
        }
        return languages;
    }

    /** Package-private so the unit tests can run the work at once. */
    void schedule(Runnable work) {
        executor.schedule(work, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** Generates the text in each language, as the uploader. */
    void generate(String identifier, JahiaUser uploader, List<Locale> languages) {
        JCRSessionFactory sessions = JCRSessionFactory.getInstance();
        JahiaUser previous = sessions.getCurrentUser();
        sessions.setCurrentUser(uploader);
        try {
            generateAll(identifier, languages);
        } finally {
            sessions.closeAllSessions();
            sessions.setCurrentUser(previous);
        }
    }

    void generateAll(String identifier, List<Locale> languages) {
        for (Locale language : languages) {
            try {
                AltTextReport report = altTextService.fillAltText(imageIn(identifier, language), false, false);
                for (AltTextResult result : report.images()) {
                    LOGGER.info("Alternative text on upload of {} in {}: {}{}", result.path(), language,
                            result.status(), result.message() == null ? "" : " (" + result.message() + ")");
                }
                if (report.truncated()) {
                    // The rate limit or a busy provider stopped the run: the other languages would fail too.
                    return;
                }
            } catch (GenAiNotConfiguredException e) {
                LOGGER.info("No alternative text on upload: genai-connector has no model for it ({})", e.getMessage());
                return;
            } catch (Exception e) {
                LOGGER.warn("Alternative text on upload failed for {} in {}", identifier, language, e);
            }
        }
    }

    /** The image in the edit workspace, in a session of the current user. Package-private for the unit tests. */
    JCRNodeWrapper imageIn(String identifier, Locale language) throws RepositoryException {
        return JCRSessionFactory.getInstance().getCurrentUserSession(Constants.EDIT_WORKSPACE, language)
                .getNodeByIdentifier(identifier);
    }
}
