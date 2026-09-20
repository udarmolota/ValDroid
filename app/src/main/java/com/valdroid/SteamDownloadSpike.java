package com.valdroid;

import android.util.Log;

import androidx.annotation.NonNull;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.depotdownloader.data.PubFileItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import com.valdroid.game.GameDescriptor;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SPIKE — Milestone 1 of the in-app Steam downloader (memory in_app_game_downloader.md).
 *
 * Proves JavaSteam's high-level {@link DepotDownloader} can fetch the RimWorld (Linux) depot
 * ON THE DEVICE: log in (credentials → Steam-Mobile approval push, same as Milestone 0), receive
 * the account's licenses, then run DepotDownloader for the RimWorld Linux depot. The downloader
 * fetches the manifest, the (ownership-gated) depot key, downloads + decompresses the CDN chunks
 * (VZip via xz / VZstd via zstd-jni) and lays them out under an absolute install dir.
 *
 * NO token storage: the refresh token is kept ONLY in memory (a field) for this run, never written
 * to disk — exactly per the project constraint. The in-memory copy lets us re-logon transparently
 * after a transient CM disconnect WITHOUT another Steam Mobile approval.
 *
 * Resilience: the first on-device test failed because the CM websocket dropped (the app was briefly
 * backgrounded for the Steam Mobile approval) and we gave up on the first disconnect. Now we
 * reconnect + retry the download up to {@link #MAX_DOWNLOAD_ATTEMPTS} times. The download params
 * mirror the user's known-good DepotDownloader command exactly (explicit depot + manifest) to
 * remove the DLC-depot iteration and the manifest-version-resolution round trip.
 *
 * Run on a BACKGROUND thread (blocks in a callback loop + awaitCompletion). Pure network client.
 */
public class SteamDownloadSpike implements Runnable, IDownloadListener, Cancellable {

    private static final String TAG = "ValDroid/SteamDL";

    /** Valheim on Steam: `DepotDownloader -app 892970 -depot 892971`, newest public build. */
    public static final int APP_ID = 892970;
    private static final int LINUX_DEPOT = 892971;

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Re-tries of the INITIAL sign-in if the CM connection drops mid-approval (Steam-Mobile excursion). */
    private static final int MAX_AUTH_ATTEMPTS = 3;

    public interface Listener {
        /** Steam Guard code prompt (only if the account is not approved via the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** Human-readable progress line for an on-screen status view (NOT a toast). */
        void onProgress(String message);
        /** Overall depot progress 0..100 (for a determinate progress bar). Default no-op so older
         *  listeners (e.g. the debug button) need not implement it. */
        default void onPercent(int percent) {}
        /** Terminal message (success or error). */
        void onDone(String message);
    }

    // Concurrency caps for DepotDownloader (library defaults are 8/8). JavaSteam's VZipUtil keeps an
    // 8 MB decompression buffer in a ThreadLocal, which lives as long as the pooled thread does — so
    // every additional worker thread permanently costs 8 MB of Java heap. On budget phones (256 MB
    // heap cap) the defaults walk into OutOfMemoryError partway through a depot download and leave an
    // unlaunchable half-copy of the game (reported twice, 2026-07-25). Fewer workers = fewer live
    // buffers; costs some download speed, which is a good trade for finishing at all.
    private static final int DL_MAX_DOWNLOADS  = 4;   // concurrent chunk downloads
    private static final int DL_MAX_DECOMPRESS = 2;   // concurrent chunk decompressions

    private final String username, password;
    private final String instanceName;   // GAME mode: download lands in instances/<name>
    private final String installDir;      // GAME mode: ABSOLUTE path = AppStorage.getInstanceDir(name)
    private final boolean manifestOnly;
    private final long manifestId;        // 0 = default to the recommended 1.5 build; >0 = pin this build
    private final List<Long> workshopIds; // MODS mode (non-null) → download these Workshop items (logged-in)
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    // In-memory session (never persisted) — reused to re-logon after a reconnect without re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile Thread workerThread;          // the depot-download thread (interrupted on cancel)
    private volatile boolean cancelled;            // user requested cancel
    private volatile boolean doneEmitted;          // ensure exactly one terminal onDone
    private volatile boolean downloadStarted;     // a download attempt is queued/running this connection
    private volatile boolean downloadInProgress;   // a DepotDownloader is actively running
    private volatile boolean downloadCompleted;    // succeeded — stop everything
    private volatile Throwable lastError;          // set by onDownloadFailed; checked after awaitCompletion
    private int downloadAttempts;
    private int authAttempts;                      // initial-sign-in attempts (drops during approval)

    /** GAME mode: download Valheim into instances/&lt;name&gt; and make it launchable. */
    public SteamDownloadSpike(String username, String password, String instanceName,
                              boolean manifestOnly, long manifestId, Listener listener) {
        this.username = username;
        this.password = password;
        this.instanceName = instanceName;
        // Download straight into the instance's game dir; once the game binary lands there,
        // GameInstance.isInstalled() is true and it becomes launchable.
        this.installDir = AppStorage.requireSingleton().getInstanceDir(instanceName).getAbsolutePath();
        this.manifestOnly = manifestOnly;
        this.manifestId = manifestId;
        this.workshopIds = null;
        this.listener = listener;
    }

    /** MODS mode ctor. */
    private SteamDownloadSpike(String username, String password,
                               List<Long> workshopIds, Listener listener) {
        this.username = username;
        this.password = password;
        this.workshopIds = workshopIds;
        this.listener = listener;
        this.instanceName = null;
        this.installDir = null;
        this.manifestOnly = false;
        this.manifestId = 0L;
    }

    /**
     * MODS mode: download public Workshop items by id → zips. Needs a LOGGED-IN account (Workshop UGC
     * content download is not available anonymously in JavaSteam's DepotDownloader — it hangs resolving
     * the UGC; same as `depotdownloader -app 294100 -pubfile <id>`, which runs under your account).
     */
    public static SteamDownloadSpike forMods(String username, String password, List<Long> workshopIds, Listener listener) {
        return new SteamDownloadSpike(username, password, workshopIds, listener);
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;     // exactly one terminal message (cancel + a late retry-finish can race)
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    /** {@link Cancellable}: stop the running download — break the blocking await + the CM loop. */
    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        downloadCompleted = true;            // prevent the disconnect handler from reconnecting/retrying
        Thread w = workerThread;
        if (w != null) w.interrupt();        // break DepotDownloader's awaitCompletion()
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Download cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());

            // Default client (same as the proven Milestone-0 auth spike). Depot CDN chunks are
            // downloaded by DepotDownloader's own ktor client, so no custom HTTP config is needed.
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Tip: stay on ONE network (Wi-Fi or mobile) until the download finishes — "
                    + "switching mid-download can drop the sign-in.");
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            Log.i(TAG, "Spike loop ended");
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without a
            // terminal result (e.g. a disconnect path that set running=false but did not call done()).
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamDownloadSpike crashed", t);
            done("crash: " + t);
        } finally {
            // ALWAYS tear the connection down on the way out. Otherwise a failed/finished session
            // (e.g. auth cancelled when the device switched Wi-Fi↔mobile) leaves an ORPHANED
            // SteamClient whose background ktor/reader thread keeps running; when the network then
            // changes its socket dies and the coroutine throws an UNCAUGHT exception on its own
            // thread — which hard-crashes the whole app (not caught by any try/catch here, never
            // logged). Disconnecting here cancels those coroutines cleanly so nothing is left to die.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            // Reconnect path: reuse the in-memory token, no second Steam Mobile approval.
            if (refreshToken != null) {
                progress("Reconnected. Logging on with cached session...");
                logOnWithToken();
                return;
            }

            progress("Connected. Authenticating '" + username + "'...");
            authAttempts++;
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;          // do NOT persist — ephemeral token, no storage
            details.deviceFriendlyName = "ValDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();       // kept in memory only
            Log.i(TAG, "AUTH OK: account=" + accountName
                    + " tokenLen=" + (refreshToken == null ? 0 : refreshToken.length()));
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            // A CancellationException here = the CM connection DROPPED while we waited for the Steam
            // Mobile approval (e.g. the user briefly switched to the Steam app to approve), NOT a real
            // auth failure (wrong password / declined). Treat it as a transient drop: keep `running`
            // true so onDisconnected reconnects and re-runs the sign-in (a fresh approval push), up to
            // MAX_AUTH_ATTEMPTS. Only give up on a genuine failure or once attempts are exhausted.
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting and asking you to "
                        + "approve again (attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
                // running stays true → onDisconnected reconnects → onConnected re-runs the sign-in.
            } else {
                done("auth error: " + describe(t));
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        // Stop for good on a user-initiated disconnect, on success, or once retries are exhausted.
        if (cb.isUserInitiated() || downloadCompleted || downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!downloadCompleted && !cb.isUserInitiated()) {
                done("connection lost; gave up after " + downloadAttempts + " attempt(s). "
                        + (lastError != null ? describe(lastError) : ""));
            }
            return;
        }
        // Transient drop mid-download — reconnect and let onLicenseList re-trigger the attempt.
        progress("Connection lost — reconnecting (attempt " + (downloadAttempts + 1) + ")...");
        downloadStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;   // cancelled while waiting to reconnect
        steamClient.connect();   // → onConnected reuses the cached token (no re-approval)
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        progress("Logged on. Waiting for license list...");
        // The download starts in onLicenseList (DepotDownloader needs the account's licenses).
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");

        // Start exactly one download attempt per (re)connect; never while one is in flight or done.
        if (downloadStarted || downloadInProgress || downloadCompleted) return;
        downloadStarted = true;
        Runnable job = (workshopIds != null) ? this::downloadMods : this::download;
        Thread t = new Thread(job, "rd-depot-dl");
        workerThread = t;            // tracked so cancel() can interrupt the blocking download
        t.start();
    }

    /**
     * MODS mode: ANONYMOUS download of public Workshop items (by published-file id) into temp work
     * dirs, each packed into /Download/ValDroid/workshop_&lt;id&gt;.zip. No login, no licenses.
     */
    private void downloadMods() {
        downloadInProgress = true;
        downloadCompleted = true;   // single pass; no reconnect-retry
        AppStorage storage = AppStorage.requireSingleton();
        File downloadsDir = storage.getDownloadsDir();
        File tmpRoot = new File(storage.getCachePath(), "mod_work");
        List<License> licenses = licenseList != null ? licenseList : Collections.emptyList();
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                running = false;
                return;
            }
            for (Long id : workshopIds) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                progress("=== Workshop mod " + id + " (app " + APP_ID + ") ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + id + ": cannot create work dir"); skipped++; continue; }
                lastError = null;
                boolean got = false;
                try (DepotDownloader dd = new DepotDownloader(steamClient, licenses, /* debug */ true,
                        /* useLanCache */ false, DL_MAX_DOWNLOADS, DL_MAX_DECOMPRESS)) {
                    dd.addListener(this);
                    PubFileItem item = new PubFileItem(
                            /* appId */ APP_ID,
                            /* pubFile */ id,
                            /* installToGameNameDirectory */ false,
                            /* installDirectory */ work.getAbsolutePath(),
                            /* verify */ false,
                            /* downloadManifestOnly */ false);
                    dd.add(item);
                    dd.finishAdding();
                    dd.getCompletion().get(20, TimeUnit.MINUTES);
                    dd.removeListener(this);
                    got = lastError == null && containsAboutXml(work);
                } catch (java.util.concurrent.TimeoutException te) {
                    progress("✗ " + id + ": timed out (20 min) — skipping");
                    lastError = te;
                } catch (Throwable t) {
                    Log.e(TAG, "mod " + id + " download crashed", t);
                    lastError = t;
                }
                if (got) {
                    try {
                        File zip = new File(downloadsDir, "workshop_" + id + ".zip");
                        ZipUtil.zipDir(work, zip);
                        progress("✓ mod " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "zip failed for mod " + id, t);
                        progress("✗ " + id + ": zip failed — " + describe(t));
                        skipped++;
                    }
                } else {
                    progress("✗ mod " + id + " — failed"
                            + (lastError != null ? (": " + describe(lastError)) : ""));
                    skipped++;
                }
                deleteRecursive(work);
            }
            done("Mods done: " + ok + " packed, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadMods crashed", t);
            done("Mods error: " + describe(t));
        } finally {
            running = false;
            downloadInProgress = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    /** True if {@code dir} contains an About/About.xml anywhere — proof a real mod/DLC was downloaded. */
    private static boolean containsAboutXml(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.getName().equals(".DepotDownloader")) continue;
            if (f.isDirectory()) {
                File about = new File(f, "About/About.xml");
                if (about.exists()) return true;
                if (containsAboutXml(f)) return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String sanitizeName(String s) {
        if (s == null) return "dlc";
        String r = s.replaceAll("[^A-Za-z0-9 _.\\-()]", "").trim();
        return r.isEmpty() ? "dlc" : r;
    }

    private void download() {
        downloadInProgress = true;
        downloadAttempts++;
        lastError = null;
        // RESUME-FRIENDLY: we deliberately do NOT wipe installDir/.DepotDownloader. DepotDownloader
        // uses that cache + the partial files to skip already-downloaded chunks, so re-tapping
        // "Download" for the SAME instance + manifest CONTINUES a half-finished download instead of
        // restarting from zero — essential for big multi-GB downloads (start at home, finish later
        // on another network). The earlier hard-crash was the orphaned SteamClient (fixed in run()'s
        // finally), not this cache, so wiping here would only cost the user their progress.
        if (downloadAttempts == 1) {
            File state = new File(installDir, ".DepotDownloader");
            if (state.exists()) progress("Found partial download — resuming where it stopped…");
        }
        // Manifest: an explicit user build wins, otherwise an empty list = the newest build of the
        // public branch. (RimDroid pinned a frozen 1.5 manifest here; Valheim just follows public.)
        List<Long> manifests = (manifestId > 0) ? List.of(manifestId) : List.of();
        String verLabel = (manifestId > 0) ? ("manifest " + manifestId) : "newest public build";
        progress((manifestOnly ? "[manifest-only] " : "")
                + "[attempt " + downloadAttempts + "] " + verLabel + " → " + installDir);
        try (DepotDownloader dd = new DepotDownloader(steamClient, licenseList, /* debug */ true,
                        /* useLanCache */ false, DL_MAX_DOWNLOADS, DL_MAX_DECOMPRESS)) {
            dd.addListener(this);

            AppItem game = new AppItem(
                    /* appId */ APP_ID,
                    /* installToGameNameDirectory */ false,    // land directly in installDir
                    /* installDirectory */ installDir,          // ABSOLUTE — required on Android
                    /* branch */ "public",
                    /* branchPassword */ "",
                    /* downloadAllPlatforms */ false,
                    /* os */ "linux",
                    /* downloadAllArchs */ false,
                    /* osArch */ "64",
                    /* downloadAllLanguages */ false,
                    /* language */ "english",
                    /* lowViolence */ false,
                    /* depot */ List.of(LINUX_DEPOT),           // explicit → no depot iteration
                    /* manifest */ manifests,                   // empty = latest; pinned = a specific version
                    /* verify */ false,
                    /* downloadManifestOnly */ manifestOnly);

            dd.add(game);
            dd.finishAdding();
            dd.awaitCompletion();           // blocks until the queue drains (or fails)
            dd.removeListener(this);

            if (lastError == null) {
                downloadCompleted = true;
                if (!manifestOnly) finalizeInstance();
                done(manifestOnly ? "Manifest fetched OK (no content downloaded)."
                        : "Instance '" + instanceName + "' downloaded — ready to launch.");
                running = false;
                try { steamUser.logOff(); } catch (Throwable ignored) {}
            } else {
                // Failed — most likely the CM dropped. onDisconnected will reconnect + retry (until
                // MAX_DOWNLOAD_ATTEMPTS). If we're still connected (a non-disconnect error) and out
                // of attempts, give up cleanly.
                progress("attempt " + downloadAttempts + " failed: " + describe(lastError));
                if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                    done("download FAILED after " + downloadAttempts + " attempts: " + describe(lastError));
                    running = false;
                    try { steamUser.logOff(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Download attempt crashed", t);
            lastError = t;
            if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                done("download error: " + describe(t));
                running = false;
                try { steamUser.logOff(); } catch (Throwable ignored) {}
            }
        } finally {
            downloadInProgress = false;
        }
    }

    // ---- IDownloadListener ----
    @Override public void onItemAdded(@NonNull DownloadItem item) { Log.i(TAG, "queued app " + item.getAppId()); }
    @Override public void onDownloadStarted(@NonNull DownloadItem item) { progress("Download started (app " + item.getAppId() + ")"); }
    @Override public void onDownloadCompleted(@NonNull DownloadItem item) { progress("Download completed (app " + item.getAppId() + ")"); }
    @Override public void onDownloadFailed(@NonNull DownloadItem item, @NonNull Throwable error) {
        Log.e(TAG, "download failed app " + item.getAppId(), error);
        lastError = error;
        progress("FAILED: " + describe(error));
    }
    @Override public void onStatusUpdate(@NonNull String message) { progress(message); }
    @Override public void onFileCompleted(int depotId, @NonNull String fileName, float depotPercentComplete) {
        progress(String.format("depot %d: %.1f%%  (%s)", depotId, depotPercentComplete * 100f, fileName));
    }
    @Override public void onChunkCompleted(int depotId, float depotPercentComplete, long compressedBytes, long uncompressedBytes) {
        progress(String.format("depot %d: %.1f%%  (%d MB)", depotId, depotPercentComplete * 100f,
                uncompressedBytes / (1024 * 1024)));
        if (listener != null) listener.onPercent(Math.round(depotPercentComplete * 100f));
    }
    @Override public void onDepotCompleted(int depotId, long compressedBytes, long uncompressedBytes) {
        progress("depot " + depotId + " done: " + uncompressedBytes + " bytes (" + compressedBytes + " compressed)");
    }

    /**
     * Make the freshly-downloaded instance launchable: mark the binary executable, set it as the
     * active instance, and refresh the instance list so the launcher picks it up. Mirrors the tail
     * of InstallerService.installInstance (minus the zip-flatten, which depot layout doesn't need).
     */
    private void finalizeInstance() {
        try {
            File dir = new File(installDir);
            File bin = new File(dir, GameDescriptor.VALHEIM.executable());
            if (bin.exists()) bin.setExecutable(true, false);
            // Same setup the zip installer runs: Goldberg shim in place of Valve's libsteam_api,
            // steam_settings for offline single player, and the auto-login pref off. Without it
            // Valheim quits at startup.
            String warning = ValheimInstanceSetup.apply(dir, this::progress);
            if (warning != null) progress(warning);
            // The install-time save fix (Assembly-CSharp bspatch) used to run here. Removed
            // entirely 2026-08-28 — root-fixed in box64 (see InstallerService for the history).
            LauncherPreferences.requireSingleton().setLastInstanceName(instanceName);
            GameInstanceManager.requireSingleton().reload();
            progress("Instance '" + instanceName + "' is now installed (" + APP_ID + ").");
            backupInstanceZip(new File(installDir));
        } catch (Throwable t) {
            Log.e(TAG, "finalizeInstance failed", t);
        }
    }

    /**
     * Copy the freshly-installed instance into a version-tagged zip under /Download/ValDroid,
     * same folder DLC/mod archives already land in. Purely a safety net for a botched or
     * corrupted app-private install (uninstall/reinstall wipes files/instances/, but the public
     * Downloads copy survives) — never fatal if it fails, and never blocks re-download: a fresh
     * download's finalizeInstance() always overwrites the previous backup for that version.
     */
    private void backupInstanceZip(File instanceDir) {
        try {
            AppStorage storage = AppStorage.requireSingleton();
            String versionTag = readVersionTag(instanceDir);
            File zip = new File(storage.getDownloadsDir(), "Valheim_" + versionTag + ".zip");
            progress("Backing up install to " + zip.getAbsolutePath() + "...");
            ZipUtil.zipDir(instanceDir, zip);
            progress("Backup saved: " + zip.getName());
        } catch (Throwable t) {
            Log.e(TAG, "backupInstanceZip failed (non-fatal)", t);
            progress("Backup copy skipped: " + describe(t));
        }
    }

    /** Version.txt's own content (e.g. "0.220.3") if readable, else a neutral tag. */
    private String readVersionTag(File instanceDir) {
        File versionFile = new File(instanceDir, "Version.txt");
        if (versionFile.isFile()) {
            try {
                String raw = new String(java.nio.file.Files.readAllBytes(versionFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!raw.isEmpty()) return sanitizeName(raw);
            } catch (java.io.IOException ignored) {}
        }
        return "latest";
    }

    /** Exception class + message + first useful cause/frame — getMessage() alone is often null. */
    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) sb.append("  @ ").append(st[0]);
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            Log.i(TAG, "acceptDeviceConfirmation -> true (Steam Mobile approval push)");
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
