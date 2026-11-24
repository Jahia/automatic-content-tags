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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Generates the alternative text of an image right after it is uploaded, when {@code alt.generate.on.upload} is
 * true (the default) and the module is enabled on the site of the image.
 *
 * <p>The rule "Set alternative text mixin on image update" of {@code META-INF/rules.drl} calls this action on the
 * same trigger as Jahia's {@code jmix:image}, for every image of the instance. The rule runs inside the save of the
 * upload, so the action only queues the work and returns: a single background thread generates the text a few
 * seconds later, after the upload's own rules (thumbnails, {@code jmix:image}) have saved. It writes as the
 * uploader, in each language of the image's site, through {@link AltTextService}.</p>
 *
 * <p>An image outside {@code /sites}, or on a site where the module is not enabled, gets no text here. A first
 * upload never replaces an existing text; a picture that replaces an earlier one gets a new text, because the old
 * text describes the old picture.</p>
 *
 * <p>The work is bounded: the queue holds at most {@code alt.upload.max.queue} images, the uploads of one user
 * make at most {@code alt.upload.max.calls} model calls per {@code alt.upload.window.seconds}, and a stop at the
 * rate limit or on a busy provider pauses the uploads of that user for a minute. An image left without a text is
 * named in the log; the mutation {@code generateImageAltText} writes it later.</p>
 */
@Component(service = BackgroundAction.class, configurationPid = ConfigValues.PID, immediate = true)
public class AltTextUploadAction implements BackgroundAction {

    /** The name the rule uses to call this action. */
    static final String NAME = "automaticContentTagsAltTextOnUpload";

    /** The identifier of this module, as a site lists the modules enabled on it. */
    static final String MODULE = "automatic-content-tags";

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextUploadAction.class);
    private static final String SITES = "/sites/";
    private static final String CREATED = "jcr:created";
    private static final String CONTENT = "jcr:content";
    private static final String LAST_MODIFIED = "jcr:lastModified";
    private static final long DELAY_SECONDS = 5;
    /** A picture written this long after its file was created replaces an earlier picture. */
    private static final long REPLACED_AFTER_MILLIS = 10_000;
    /** How long the uploads of a user rest after a stop at the rate limit or on a busy provider. */
    private static final long PAUSE_MILLIS = 60_000;
    private static final long STOP_WAIT_SECONDS = 5;

    // Package-private for the unit tests, which inject a mock.
    @Reference
    AltTextService altTextService;

    // Package-private for the unit tests, which move the clock.
    LongSupplier clock = System::currentTimeMillis;

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.from(ConfigValues.of(Map.of())));
    private final AtomicInteger queued = new AtomicInteger();
    // The two maps below belong to the worker thread, which runs one upload at a time.
    private final Map<String, Deque<Long>> callsByUser = new HashMap<>();
    private final Map<String, Long> pausedUntilByUser = new HashMap<>();
    private ScheduledThreadPoolExecutor executor;

    /** One uploaded image, as the rule saw it. */
    record Upload(String identifier, String path, JahiaUser uploader, List<Locale> languages, boolean replaced) {
    }

    /** Immutable configuration snapshot. */
    record Settings(boolean enabled, int maxQueue, int maxCalls, int windowSeconds) {

        static Settings from(ConfigValues values) {
            return new Settings(
                    values.bool("alt.generate.on.upload", true),
                    values.integer("alt.upload.max.queue", 200, 1, 10_000),
                    values.integer("alt.upload.max.calls", 30, 0, 100_000),
                    values.integer("alt.upload.window.seconds", 600, 10, 86_400));
        }
    }

    @Activate
    void activate(Map<String, Object> properties) {
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "automatic-content-tags-alt-text");
            thread.setDaemon(true);
            return thread;
        });
        // A stop drops the uploads that still wait, and lets the one that runs finish its save.
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        configure(properties);
    }

    @Modified
    void configure(Map<String, Object> properties) {
        Settings cfg = Settings.from(ConfigValues.of(properties));
        settings.set(cfg);
        LOGGER.info("Automatic Content Tags: alternative text on upload {}, at most {} queued images and {} model "
                        + "calls per user in {} seconds", cfg.enabled() ? "enabled" : "disabled", cfg.maxQueue(),
                cfg.maxCalls(), cfg.windowSeconds());
    }

    @Deactivate
    void deactivate() {
        if (executor == null) {
            return;
        }
        int waiting = executor.getQueue().size();
        executor.shutdown();
        if (waiting > 0) {
            LOGGER.warn("The module stops with {} uploaded images still queued: they get no alternative text. "
                    + "Generate it with the mutation generateImageAltText", waiting);
        }
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("The alternative text of one uploaded image was still being generated after {} seconds: "
                        + "it is interrupted", STOP_WAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void executeBackgroundAction(JCRNodeWrapper node) {
        Settings cfg = settings.get();
        if (!cfg.enabled()) {
            return;
        }
        try {
            if (!Constants.EDIT_WORKSPACE.equals(node.getSession().getWorkspace().getName())
                    || !onASiteWithTheModule(node)) {
                return;
            }
            JahiaUser uploader = node.getSession().getUser();
            List<Locale> languages = languages(node);
            if (uploader == null || languages.isEmpty()) {
                LOGGER.debug("No uploader or no language for the image {}: no alternative text on upload",
                        node.getPath());
                return;
            }
            queue(new Upload(node.getIdentifier(), node.getPath(), uploader, languages, replaced(node)), cfg);
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to queue the alternative text of {}", node.getPath(), e);
        }
    }

    /**
     * @return whether the image belongs to a site where this module is enabled; an image outside {@code /sites}
     *         (a file of a user, a mount) belongs to none, although Jahia resolves the system site for it
     */
    static boolean onASiteWithTheModule(JCRNodeWrapper node) throws RepositoryException {
        if (!node.getPath().startsWith(SITES)) {
            return false;
        }
        JCRSiteNode site = node.getResolveSite();
        return site != null && site.getInstalledModulesWithAllDependencies().contains(MODULE);
    }

    /**
     * @return whether this picture replaces an earlier one: its content was written a while after the file was
     *         created. A new file and a copy get their content when they are created, however long their save
     *         and the rules before this one take
     */
    static boolean replaced(JCRNodeWrapper node) throws RepositoryException {
        if (!node.hasProperty(CREATED) || !node.hasNode(CONTENT)) {
            return false;
        }
        JCRNodeWrapper content = node.getNode(CONTENT);
        if (!content.hasProperty(LAST_MODIFIED)) {
            return false;
        }
        long created = node.getProperty(CREATED).getDate().getTimeInMillis();
        long written = content.getProperty(LAST_MODIFIED).getDate().getTimeInMillis();
        return written - created > REPLACED_AFTER_MILLIS;
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

    /** Queues one upload, unless the queue is full or the module is stopping. */
    private void queue(Upload upload, Settings cfg) {
        if (queued.incrementAndGet() > cfg.maxQueue()) {
            queued.decrementAndGet();
            LOGGER.warn("The queue of the alternative text on upload is full ({} images): no text for {}. "
                    + "Generate it with the mutation generateImageAltText", cfg.maxQueue(), upload.path());
            return;
        }
        try {
            schedule(() -> {
                try {
                    run(upload);
                } finally {
                    queued.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            LOGGER.info("The module is stopping: no alternative text on upload for {}", upload.path());
        }
    }

    /** Package-private so the unit tests can run the work at once. */
    void schedule(Runnable work) {
        executor.schedule(work, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** Generates the text of one upload in each language, as the uploader. Runs on the worker thread. */
    private void run(Upload upload) {
        JCRSessionFactory sessions = JCRSessionFactory.getInstance();
        JahiaUser previous = sessions.getCurrentUser();
        sessions.setCurrentUser(upload.uploader());
        try {
            generateAll(upload);
        } finally {
            sessions.closeAllSessions();
            sessions.setCurrentUser(previous);
        }
    }

    /** The languages of one upload, until one of them says that the next ones would get nothing either. */
    void generateAll(Upload upload) {
        String user = upload.uploader().getUserKey();
        Long pausedUntil = pausedUntilByUser.get(user);
        if (pausedUntil != null && clock.getAsLong() < pausedUntil) {
            LOGGER.info("The alternative text on upload is paused for {} after a busy provider or a rate limit: "
                    + "no text for {}", user, upload.path());
            return;
        }
        pausedUntilByUser.remove(user);
        for (Locale language : upload.languages()) {
            if (!generateIn(upload, language)) {
                return;
            }
        }
    }

    /** @return whether the next language of the upload is worth a try */
    private boolean generateIn(Upload upload, Locale language) {
        Settings cfg = settings.get();
        String user = upload.uploader().getUserKey();
        if (!mayCall(user, cfg)) {
            LOGGER.warn("The uploads of {} used the {} model calls they may make in {} seconds: no alternative text "
                    + "in {} for {}", user, cfg.maxCalls(), cfg.windowSeconds(), language, upload.path());
            return false;
        }
        try {
            AltTextReport report = altTextService.fillAltText(imageIn(upload.identifier(), language), false,
                    upload.replaced(), null);
            for (AltTextResult result : report.images()) {
                LOGGER.info("Alternative text on upload of {} in {}: {}{}", result.path(), language,
                        result.status(), result.message() == null ? "" : " (" + result.message() + ")");
                if (cfg.maxCalls() > 0 && result.status() != AltTextResult.Status.SKIPPED) {
                    callsByUser.computeIfAbsent(user, key -> new ArrayDeque<>()).add(clock.getAsLong());
                }
            }
            if (report.truncated()) {
                // The rate limit of this user or a busy provider stopped the run: the other languages and the
                // next images of this user would fail too. Another user has a rate limit of their own.
                pausedUntilByUser.put(user, clock.getAsLong() + PAUSE_MILLIS);
                LOGGER.warn("The alternative text on upload pauses for {} seconds for {} after {}: the images this "
                        + "user uploads meanwhile get no text", PAUSE_MILLIS / 1000, user, upload.path());
                return false;
            }
            return true;
        } catch (GenAiNotConfiguredException e) {
            LOGGER.info("No alternative text on upload: ai-assistant has no model for it ({})", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.warn("Alternative text on upload failed for {} in {}", upload.path(), language, e);
            return true;
        }
    }

    /** Whether the uploads of this user may make one more model call in the current window. */
    private boolean mayCall(String user, Settings cfg) {
        if (cfg.maxCalls() == 0) {
            return true;
        }
        Deque<Long> calls = callsByUser.get(user);
        if (calls == null) {
            return true;
        }
        long oldest = clock.getAsLong() - TimeUnit.SECONDS.toMillis(cfg.windowSeconds());
        while (!calls.isEmpty() && calls.peekFirst() < oldest) {
            calls.removeFirst();
        }
        if (calls.isEmpty()) {
            callsByUser.remove(user);
        }
        return calls.size() < cfg.maxCalls();
    }

    /** The image in the edit workspace, in a session of the current user. Package-private for the unit tests. */
    JCRNodeWrapper imageIn(String identifier, Locale language) throws RepositoryException {
        return JCRSessionFactory.getInstance().getCurrentUserSession(Constants.EDIT_WORKSPACE, language)
                .getNodeByIdentifier(identifier);
    }
}
