package com.valdroid;

import android.util.Log;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SPIKE — Steam Cloud cross-save, milestone 0: READ-ONLY enumeration.
 *
 * RimWorld DOES sync saves through Steam Cloud (store-page feature; confirmed working on PC),
 * which our June notes wrongly wrote off — see memory saves_settings_backup.md (corrected
 * 2026-07-21). JavaSteam 1.8.0 (already shipped for the in-app downloader) carries the full
 * Cloud service: changelist enumeration, file download, block upload.
 *
 * Grew from a read-only enumeration spike into the Valheim pull/push used by CloudSavesFragment.
 * Valheim writes its cloud files through the Steam Cloud API (remotecache.vdf "root 0"), not
 * Auto-Cloud, so a cloud path is simply the game's relative path: {@code worlds/<file>},
 * {@code worlds/<World>/<file>} (1.0 folder worlds), {@code characters/<file>},
 * {@code serverlist/<file>}. See {@link #WORLDS_DIR} for the full layout on both sides.
 *
 * Auth = the proven credentials + Steam-Mobile-approval flow copied from
 * {@link SteamDownloadSpike} (same constraints: token kept in memory only, never persisted).
 * Run on a background thread (blocks in the callback loop).
 */
public class SteamCloudSpike implements Runnable, Cancellable {

    private static final String TAG = "ValDroid/SteamCloud";

    private static final int MAX_AUTH_ATTEMPTS = 3;

    /** One cloud file, in plain terms the UI can render without touching JavaSteam types. */
    public static final class CloudFile {
        public final String filename;      // e.g. "Vikingworld.fwl" or "Snowhalla/_main.2.fwl2"
        public final long rawSize;         // bytes of the real save file (a world: all its files)
        public final long timestampMs;     // when the cloud copy was written (a world: newest file)
        /** Non-null when this entry stands for a whole 1.0 folder world rather than one file:
         *  a world is confirmed as a unit, never chunk by chunk. */
        public final String worldName;
        public final int fileCount;        // files in the world as it would be sent (1 for a file)
        public final long localTimestampMs; // newest local copy, or 0 when unknown
        CloudFile(String filename, long rawSize, long timestampMs) {
            this(filename, rawSize, timestampMs, null, 1, 0L);
        }
        CloudFile(String filename, long rawSize, long timestampMs, String worldName, int fileCount,
                  long localTimestampMs) {
            this.filename = filename; this.rawSize = rawSize; this.timestampMs = timestampMs;
            this.worldName = worldName; this.fileCount = fileCount;
            this.localTimestampMs = localTimestampMs;
        }
    }

    /** One cloud file with its path already split into Valheim's folder and the path inside it. */
    private static final class Entry {
        final AppFileInfo info;
        final String full;   // exactly what Steam calls it: prefix + filename
        final String cat;    // CAT_WORLDS / CAT_CHARACTERS / CAT_SERVERLIST / CAT_OTHER
        final String rel;    // path below that folder, e.g. "Snowhalla/_main.2.fwl2"
        Entry(AppFileInfo info, String full, String cat, String rel) {
            this.info = info; this.full = full; this.cat = cat; this.rel = rel;
        }
        long ts() { return info.getTimestamp() == null ? 0L : info.getTimestamp().getTime(); }
        /** The 1.0 world this file belongs to, or null for a flat file. */
        String world() {
            int slash = rel.indexOf('/');
            return (CAT_WORLDS.equals(cat) && slash > 0) ? rel.substring(0, slash) : null;
        }
    }

    /** Push decision when the cloud already holds files with the same names. */
    public static final int PUSH_CANCEL = 0, PUSH_REPLACE = 1, PUSH_ONLY_NEW = 2;

    /** Adds the file listing and the push-conflict question to the shared progress/done listener. */
    public interface CloudListener extends SteamDownloadSpike.Listener {
        void onFileList(java.util.List<CloudFile> files);

        /**
         * Sending is about to replace these cloud files (the PC will load our version instead).
         * Answer with PUSH_REPLACE / PUSH_ONLY_NEW / PUSH_CANCEL. Called on the worker thread, so
         * the UI must complete the future from its own thread.
         */
        default CompletableFuture<Integer> resolvePushConflicts(java.util.List<CloudFile> clashes) {
            return CompletableFuture.completedFuture(PUSH_CANCEL);
        }
    }

    private final String username, password;
    private final int appId;                              // which game's cloud to enumerate
    private final String instanceName;                    // null/empty = cache-only test; else pull into
                                                          // instances/<name>/…/Saves/
    private final boolean listOnly;                       // just enumerate and report, download nothing
    private final boolean upload;                         // push local saves TO the cloud instead
    private final java.util.Set<String> selected;         // null = every file; else only these filenames
    private File pullDir;                                 // pull target: a temp dir, NOT the Saves/ folder
    private File compareDir;                              // pull: existing Saves/, to skip identical files
    private final SteamDownloadSpike.Listener listener;   // same shape → same UI wiring

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;

    // In-memory session (never persisted) — lets a transient reconnect skip the re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean doneEmitted;
    private volatile boolean enumerationStarted;
    private volatile boolean enumerationCompleted;
    private int authAttempts;

    private SteamCloudSpike(String username, String password, int appId, String instanceName,
                            boolean listOnly, boolean upload, java.util.Set<String> selected,
                            SteamDownloadSpike.Listener listener) {
        this.username = username;
        this.password = password;
        this.appId = appId;
        this.instanceName = (instanceName == null || instanceName.trim().isEmpty())
                ? null : instanceName.trim();
        this.listOnly = listOnly;
        this.upload = upload;
        this.selected = selected;
        this.listener = listener;
    }

    /** Sign in and report what is in the cloud — downloads nothing. */
    public static SteamCloudSpike forList(String user, String pass, int appId, CloudListener l) {
        return new SteamCloudSpike(user, pass, appId, null, true, false, null, l);
    }

    /**
     * Download EVERY cloud save into {@code tempDir} and then hang up. Nothing is decided about the
     * user's Saves/ folder while the connection is open — placing the files (and asking about clashing
     * names) happens afterwards, offline, with no session to keep alive and no rush.
     */
    public static SteamCloudSpike forPull(String user, String pass, int appId, File tempDir,
                                          File savesDir, SteamDownloadSpike.Listener l) {
        SteamCloudSpike s = new SteamCloudSpike(user, pass, appId, null, false, false, null, l);
        s.pullDir = tempDir;
        s.compareDir = savesDir;
        return s;
    }

    /** Push the instance's saves up: new names go silently, existing ones are asked about once. */
    public static SteamCloudSpike forPush(String user, String pass, int appId, String instanceName,
                                          SteamDownloadSpike.Listener l) {
        return new SteamCloudSpike(user, pass, appId, instanceName, false, true, null, l);
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        enumerationCompleted = true;   // stop the disconnect handler from reconnecting
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);

            running = true;
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamCloudSpike crashed", t);
            done("crash: " + t);
        } finally {
            // Same lesson as the downloader: never leave an orphaned SteamClient whose ktor
            // thread dies uncaught on a later network change and takes the whole app with it.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
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
            details.persistentSession = false;
            details.deviceFriendlyName = "ValDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();
            Log.i(TAG, "AUTH OK: account=" + accountName);
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting, approve again "
                        + "(attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
            } else {
                done("auth error: " + t.getMessage());
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(151);   // distinct from the downloader's 149 so the two can't collide
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        if (cb.isUserInitiated() || enumerationCompleted
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!enumerationCompleted && !cb.isUserInitiated()) done("connection lost.");
            return;
        }
        progress("Connection lost — reconnecting...");
        enumerationStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;
        steamClient.connect();
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        // Start the pull EXACTLY once. onLoggedOn fires again on every reconnect (the CM websocket
        // drops mid-pull), and a plain volatile check-then-set let two threads both pass and run the
        // whole file loop concurrently (bogus "N failed" summary; double writes deduped only by
        // skip-if-exists). Guard the check+set atomically so a reconnect can never spawn a second run.
        synchronized (this) {
            if (enumerationStarted || enumerationCompleted) return;
            enumerationStarted = true;
        }
        new Thread(this::enumerate, "rd-cloud-enum").start();
    }

    /** The read-only payload: fetch the full cloud changelist for RimWorld and log every file. */
    private void enumerate() {
        try {
            progress("Logged on. Requesting cloud file list for app " + appId + "...");
            SteamCloud cloud = steamClient.getHandler(SteamCloud.class);
            // Kotlin default args are not visible from Java — pass the scope explicitly.
            AppFileChangeList list = cloud.getAppFileListChange(
                            appId, 0L,
                            kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.getIO()))
                    .get(60, TimeUnit.SECONDS);

            List<String> prefixes = list.getPathPrefixes();
            List<AppFileInfo> files = list.getFiles();
            progress("Cloud changelist #" + list.getCurrentChangeNumber()
                    + ": " + files.size() + " files, "
                    + prefixes.size() + " path prefixes " + prefixes);

            long totalBytes = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                AppFileInfo f = files.get(i);
                int pi = f.getPathPrefixIndex();
                String prefix = (pi >= 0 && pi < prefixes.size()) ? prefixes.get(pi) : ("prefix#" + pi);
                totalBytes += f.getRawFileSize();
                String line = String.format(java.util.Locale.ROOT, "[%d] %s%s  %,d B  %s",
                        i, prefix, f.getFilename(), f.getRawFileSize(), f.getTimestamp());
                Log.i(TAG, line);
                // The on-screen status view only shows the last line — batch a readable summary.
                if (i < 40) sb.append(line).append('\n');
            }
            if (files.size() > 40) sb.append("… +").append(files.size() - 40).append(" more (full list in logcat)\n");
            progress(sb.toString());

            if (listOnly) {
                // Report the listing to the UI and stop — this mode never downloads anything.
                java.util.List<CloudFile> out = new java.util.ArrayList<>();
                for (AppFileInfo f : files)
                    out.add(new CloudFile(f.getFilename(), f.getRawFileSize(),
                            f.getTimestamp() == null ? 0L : f.getTimestamp().getTime()));
                if (listener instanceof CloudListener) ((CloudListener) listener).onFileList(out);
                enumerationCompleted = true;
                done(files.isEmpty() ? "Nothing in the cloud for this game."
                                     : ("Found " + files.size() + " save(s) in the cloud."));
                return;
            }

            // Split every cloud path into (Valheim folder, path inside it) ONCE, from the full
            // prefix + filename. JavaSteam hands over the protobuf's file_name verbatim and Steam
            // decides how much of the path goes into path_prefixes, so working on the joined string
            // is the only reading that is right however Steam splits "worlds/Snowhalla/_main.2.fwl2".
            java.util.List<Entry> entries = new java.util.ArrayList<>();
            java.util.Map<String, String> roots = new java.util.HashMap<>();
            for (AppFileInfo f : files) {
                String full = prefixOf(f, prefixes) + f.getFilename();
                String[] cr = splitCloudPath(full);
                if (cr == null) {
                    progress("Ignoring a cloud file with an unsafe path: " + full);
                    continue;
                }
                entries.add(new Entry(f, full, cr[0], cr[1]));
                String root = cloudRootOf(full, cr[0]);
                if (root != null && !roots.containsKey(cr[0])) roots.put(cr[0], root);
            }

            if (upload) {
                // The changelist doubles as "what's already up there": saves it doesn't contain are
                // new and go silently; saves it does contain would overwrite what the PC loads, so we
                // stop and ask once, listing them with both dates.
                uploadAll(cloud, entries, roots);
                return;
            }

            if (files.isEmpty()) {
                enumerationCompleted = true;
                done("Cloud enumeration OK: 0 files. Nothing to download.");
                return;
            }

            // PULL: fetch EVERY cloud save into a scratch dir, then hang up. Deciding what goes into
            // the user's Saves/ (and asking about names that clash) happens afterwards with no live
            // session — so a slow human answering a dialog can't cost us the connection, and a failed
            // download never lands anywhere near real saves.
            if (pullDir == null) { done("internal: no pull target"); return; }
            if (!pullDir.isDirectory() && !pullDir.mkdirs()) {
                done("Cannot create the temporary folder: " + pullDir);
                return;
            }
            // Start from a clean scratch. The scratch keeps each file's relative path under its
            // Valheim folder (<pullDir>/worlds/Snowhalla/_main.2.fwl2, <pullDir>/characters/x.fch):
            // writing "Snowhalla/_main.2.fwl2" flat either failed on the missing parent folder or,
            // cut to its last segment, lost which world the chunk belonged to.
            for (File old : orEmpty(pullDir.listFiles())) deleteRecursive(old);
            int ok = 0, failed = 0, same = 0, worldsOk = 0, worldsFailed = 0;

            java.util.List<Entry> flat = new java.util.ArrayList<>();
            java.util.Map<String, java.util.List<Entry>> worlds = new java.util.LinkedHashMap<>();
            for (Entry e : entries) {
                String w = e.world();
                if (w == null) flat.add(e);
                else {
                    java.util.List<Entry> l = worlds.get(w);
                    if (l == null) worlds.put(w, l = new java.util.ArrayList<>());
                    l.add(e);
                }
            }

            // 1.0 folder worlds travel as a unit: the scratch copy must be the COMPLETE cloud
            // folder, because placement installs it whole (mixing files of two save numbers breaks
            // the world). Files that already match the phone are copied from the phone instead of
            // downloaded — same bytes, no bandwidth.
            File worldsLocal = compareDir == null ? null : new File(compareDir, WORLDS_DIR);
            File scratchWorlds = new File(pullDir, CAT_WORLDS);
            for (java.util.Map.Entry<String, java.util.List<Entry>> w : worlds.entrySet()) {
                if (!running) break;
                String name = w.getKey();
                java.util.List<Entry> wf = w.getValue();
                if (worldsLocal != null && worldMatchesLocal(new File(worldsLocal, name), name, wf)) {
                    progress("World " + name + " (" + wf.size() + " files): unchanged, skipping.");
                    same += wf.size();
                    continue;
                }
                progress("World " + name + " (" + wf.size() + " files): downloading…");
                boolean worldOk = true;
                int fetched = 0, reused = 0;
                for (Entry e : wf) {
                    if (!running) { worldOk = false; break; }
                    File dest = new File(scratchWorlds, e.rel);
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) { worldOk = false; break; }
                    File local = worldsLocal == null ? null : new File(worldsLocal, e.rel);
                    if (sameContent(local, e.info.getShaFile())) {
                        if (!copyFile(local, dest)) { worldOk = false; break; }
                        reused++;
                    } else {
                        byte[] raw = fetchAndDecode(cloud, e.full, e.info.getRawFileSize(),
                                e.info.getShaFile(), false);
                        if (raw == null || !writeFile(dest, raw)) { worldOk = false; break; }
                        fetched++;
                    }
                    if (e.ts() > 0) dest.setLastModified(e.ts());
                }
                if (!worldOk) {
                    // Never leave half a world in the scratch: placement would install it.
                    deleteRecursive(new File(scratchWorlds, name));
                    worldsFailed++;
                    failed += wf.size();
                    progress("FAILED world " + name + ": not every file came through, so it will "
                            + "not be installed.");
                    continue;
                }
                worldsOk++;
                ok += fetched;
                same += reused;
                progress("Downloaded world " + name + " (" + wf.size() + " files"
                        + (reused > 0 ? (", " + reused + " already on the phone") : "") + ").");
            }

            for (Entry e : flat) {
                if (!running) break;
                // Already have this exact file? The changelist carries the cloud's SHA-1, so we can
                // tell before spending any bandwidth — and it keeps the "same name" dialog for real
                // differences instead of asking about a file that is identical.
                if (compareDir != null && sameContent(localFileFor(compareDir, e.cat, e.rel),
                        e.info.getShaFile())) {
                    progress("Unchanged, skipping: " + e.rel);
                    same++;
                    continue;
                }
                byte[] raw = fetchAndDecode(cloud, e.full, e.info.getRawFileSize(),
                        e.info.getShaFile(), true);
                if (raw == null) { failed++; continue; }   // fetchAndDecode logged the reason
                File dest = new File(new File(pullDir, e.cat), e.rel);
                File parent = dest.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                if (!writeFile(dest, raw)) { failed++; continue; }
                // Carry the cloud's own timestamp across, so the placement step can compare
                // "mine vs theirs" by date without another round trip.
                if (e.ts() > 0) dest.setLastModified(e.ts());
                ok++;
                progress("Downloaded: " + e.rel + " (" + raw.length + " B)");
            }
            enumerationCompleted = true;
            done("Downloaded " + ok + " file(s)"
                    + (worldsOk > 0 ? (" (" + worldsOk + " world folder(s))") : "")
                    + (same > 0 ? (", " + same + " already up to date") : "")
                    + (failed > 0 ? (", " + failed + " failed") : "")
                    + (worldsFailed > 0 ? (" — " + worldsFailed + " world(s) skipped") : "")
                    + ". Disconnected from Steam.");
            return;
        } catch (Throwable t) {
            Log.e(TAG, "enumeration failed", t);
            done("cloud enumeration failed: " + t);
        } finally {
            running = false;   // one-shot: end the callback loop either way
        }
    }

    /**
     * Valheim's save layout (observed 2026-09-27 on the phone and in a PC's Steam Cloud mirror).
     *
     * On the phone, under {@code <instance>/unity3d/IronGate/Valheim/}:
     * <ul>
     * <li>{@code worlds_local/<World>/} — a Valheim 1.0 world is a FOLDER: {@code *.chunk} files
     *     (names change between saves) plus one save set {@code _main.N.chunks/.db2/.fwl2/.ok}.
     *     Every save writes set N+1 and deletes set N. {@code cacheMinimap*} files in the same
     *     folder are a local cache that the game never puts in the cloud. A world that was created
     *     but never saved may hold only {@code _main.0.fwl2}.</li>
     * <li>{@code worlds_local/<World>_backup_auto-<date>/} — 1.0 backups, same folder shape.</li>
     * <li>{@code worlds_local/<World>.fwl + .db} (+ {@code .old}, {@code _backup_*}) — pre-1.0 flat
     *     worlds. The game converts one on first open (leaving {@code <World>_backup_<date>.db/.fwl})
     *     and from then on writes the folder format.</li>
     * <li>{@code characters_local/<name>.fch} (+ {@code .fch.old}, {@code _backup_*.fch}) —
     *     characters are still flat files.</li>
     * </ul>
     * In Steam Cloud the same tree without the {@code _local} suffix: {@code worlds/<World>/...}
     * (no minimap cache), {@code worlds/<World>.fwl/.db}, {@code characters/*.fch},
     * {@code serverlist/favorite|recent}. The cloud can hold both formats at once — e.g. a world
     * converted on the PC next to one that was never opened in 1.0.
     */
    public static final String WORLDS_DIR = "worlds_local", CHARACTERS_DIR = "characters_local";

    /** The Valheim folders a cloud path can start with (and our bucket for anything else). */
    public static final String CAT_WORLDS = "worlds", CAT_CHARACTERS = "characters",
            CAT_SERVERLIST = "serverlist", CAT_OTHER = "other";

    /** Marker in a world folder's name for the copy a pull moved aside instead of overwriting. */
    public static final String VALDROID_BACKUP_TAG = "_backup_valdroid-";

    /** True for a flat file Valheim itself would load (not a backup, not a 1.0 world folder). */
    public static boolean isSaveFile(String name) {
        return name.endsWith(".fwl") || name.endsWith(".db") || name.endsWith(".fch");
    }

    /** The per-device minimap cache inside a 1.0 world folder — never synced, like the game does. */
    public static boolean isLocalCache(String name) {
        return name.startsWith("cacheMinimap");
    }

    /** A save set's world metadata, {@code _main.<N>.fwl2}: its presence makes a folder a world. */
    private static final java.util.regex.Pattern MAIN_FWL2 =
            java.util.regex.Pattern.compile("_main\\.\\d+\\.fwl2");

    /**
     * Is this a 1.0 world folder we should push? It must hold a {@code _main.N.fwl2} (a stray or
     * emptied folder is not a world), and backup FOLDERS — ours and the game's — stay on the phone:
     * each is a full world copy, and quota is better spent on the live worlds. (Flat
     * {@code *_backup_*.db/.fwl/.fch} files still go up as before — isSaveFile accepts them and
     * this change deliberately leaves the flat path alone.) Hidden folders are skipped
     * too: a pull stages a world as ".<World>.valdroid-partial" before renaming it into place, and
     * a leftover from an interrupted pull must never reach the cloud as a world of its own.
     */
    public static boolean isWorldFolder(File dir) {
        if (dir == null || !dir.isDirectory() || dir.getName().contains("_backup_")
                || dir.getName().startsWith(".")) return false;
        for (File f : orEmpty(dir.listFiles()))
            if (f.isFile() && MAIN_FWL2.matcher(f.getName()).matches()) return true;
        return false;
    }

    /**
     * Every file of a world folder as paths relative to {@code dir} ("/" separated), minus the
     * minimap cache. Recursive only for safety — the observed layout is one level deep.
     */
    public static java.util.List<String> worldFiles(File dir) {
        java.util.List<String> out = new java.util.ArrayList<>();
        collectFiles(dir, "", out);
        java.util.Collections.sort(out);
        return out;
    }

    private static void collectFiles(File dir, String prefix, java.util.List<String> out) {
        for (File f : orEmpty(dir.listFiles())) {
            if (f.isDirectory()) collectFiles(f, prefix + f.getName() + "/", out);
            else if (f.isFile() && !isLocalCache(f.getName())) out.add(prefix + f.getName());
        }
    }

    /** Newest mtime among a world's files (minimap cache excluded), 0 when empty. */
    public static long newestMtime(File dir) {
        long best = 0;
        for (String rel : worldFiles(dir)) best = Math.max(best, new File(dir, rel).lastModified());
        return best;
    }

    /**
     * Split a full cloud path into {Valheim folder, path inside it}, e.g.
     * "worlds/Snowhalla/_main.2.fwl2" -> {"worlds", "Snowhalla/_main.2.fwl2"}. The folder is the
     * FIRST segment named worlds / characters / serverlist, so a %Token%-style prefix in front of it
     * (an Auto-Cloud root, should Steam ever report one) is tolerated. Paths with no such segment
     * go to {@link #CAT_OTHER} under their last segment — where they were placed before 1.0.
     * Returns null for a path we must not write (".." or empty segments).
     */
    static String[] splitCloudPath(String full) {
        String[] segs = full.replace('\\', '/').split("/");
        int start = -1;
        String cat = CAT_OTHER;
        for (int i = 0; i < segs.length - 1; i++) {
            String s = segs[i].toLowerCase(java.util.Locale.ROOT);
            if (s.equals(CAT_WORLDS) || s.equals(CAT_CHARACTERS) || s.equals(CAT_SERVERLIST)) {
                start = i + 1;
                cat = s;
                break;
            }
        }
        if (start < 0) start = segs.length - 1;
        StringBuilder rel = new StringBuilder();
        for (int j = start; j < segs.length; j++) {
            String s = segs[j];
            if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
            if (rel.length() > 0) rel.append('/');
            rel.append(s);
        }
        return rel.length() == 0 ? null : new String[]{cat, rel.toString()};
    }

    /** The part of {@code full} up to and including its {@code cat} folder ("worlds/"), or null. */
    private static String cloudRootOf(String full, String cat) {
        if (CAT_OTHER.equals(cat)) return null;
        String p = full.replace('\\', '/');
        String[] segs = p.split("/");
        StringBuilder root = new StringBuilder();
        for (String s : segs) {
            root.append(s).append('/');
            if (s.equalsIgnoreCase(cat)) return root.toString();
        }
        return null;
    }

    /** Where a cloud file lives (or would live) on the phone. */
    public static File localFileFor(File savesDir, String cat, String rel) {
        if (CAT_WORLDS.equals(cat) && rel.indexOf('/') > 0)
            return new File(new File(savesDir, WORLDS_DIR), rel);   // a 1.0 world folder file
        return new File(new File(savesDir, localDirFor(rel)), rel);
    }

    /**
     * Does the phone already hold exactly this cloud world? Every cloud file must match by SHA-1
     * and the phone must hold no save file the cloud lacks (the cache aside) — otherwise a pull
     * has something to offer and the user gets asked.
     */
    private static boolean worldMatchesLocal(File localWorld, String name, java.util.List<Entry> wf) {
        if (!localWorld.isDirectory()) return false;
        java.util.Set<String> cloudRels = new java.util.HashSet<>();
        for (Entry e : wf) {
            if (!sameContent(new File(localWorld.getParentFile(), e.rel), e.info.getShaFile())) return false;
            cloudRels.add(e.rel);
        }
        for (String rel : worldFiles(localWorld))
            if (!cloudRels.contains(name + "/" + rel)) return false;
        return true;
    }

    /**
     * The save name with Valheim's rolling-backup suffixes peeled off: "wetsnow.fch.old" ->
     * "wetsnow.fch". The game writes a save as {@code X.new}, renames the live {@code X} to
     * {@code X.old} and then {@code X.new} to {@code X}, so either suffix can sit on top of the
     * real extension, and they can nest.
     */
    private static String baseSaveName(String name) {
        String s = name;
        for (;;) {
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (low.endsWith(".old") || low.endsWith(".new")) s = s.substring(0, s.length() - 4);
            else return s;
        }
    }

    /**
     * Which of the two local folders a save belongs in.
     *
     * Route on the BASE name, not the raw one: a plain {@code endsWith(".fch")} sent every
     * character backup to the worlds folder, because "wetsnow.fch.old" ends in ".old". Found on
     * 2026-09-20 in a real cloud pull — wetsnow.fch.old had landed in worlds_local/, where it can
     * no longer serve as the fallback Valheim reads when the live .fch is unreadable.
     *
     * Names carrying no save extension at all (Valheim's "favorite" / "recent" lists, which the
     * cloud keeps under serverlist/) keep the old behaviour and land in the worlds folder;
     * relocating them on a guess about the local layout would be worse than leaving them alone.
     *
     * Only for FLAT files: the files of a 1.0 world folder go to worlds_local/<World>/ — see
     * {@link #localFileFor}.
     */
    public static String localDirFor(String name) {
        return baseSaveName(name).endsWith(".fch") ? CHARACTERS_DIR : WORLDS_DIR;
    }

    /**
     * Move saves that an earlier {@link #localDirFor} filed under the wrong folder. Walks both
     * folders and relocates anything whose name says it belongs in the other one. A file already
     * present at the destination is left untouched — the point is to recover a stranded backup,
     * never to clobber a live save.
     *
     * @return how many files were moved.
     */
    public static int migrateMisplaced(File savesDir) {
        int moved = 0;
        for (String sub : new String[]{ WORLDS_DIR, CHARACTERS_DIR }) {
            for (File f : orEmpty(new File(savesDir, sub).listFiles())) {
                if (!f.isFile()) continue;
                String want = localDirFor(f.getName());
                if (want.equals(sub)) continue;
                File toDir = new File(savesDir, want);
                if (!toDir.isDirectory() && !toDir.mkdirs()) continue;
                File to = new File(toDir, f.getName());
                if (to.exists()) continue;
                if (f.renameTo(to)) {
                    moved++;
                    Log.i(TAG, "migrated misplaced save " + f.getName() + ": " + sub + " -> " + want);
                }
            }
        }
        return moved;
    }

    /**
     * The cloud path a local save goes to: the root Steam itself reported for that folder (taken
     * from the existing files — it is how the PC copy is laid out) + the relative path.
     *
     * For an empty cloud we fall back to the bare folder name. Valheim uses the Steam Cloud API
     * (the PC's remotecache.vdf lists "worlds/Snowhalla/_main.2.fwl2" etc. with "root" 0), not an
     * Auto-Cloud root, so the old "%WinAppDataLocalLow%IronGate/Valheim/worlds/" guess would have
     * put files where the PC game never looks.
     */
    private static String cloudPathFor(String cat, String rel, java.util.Map<String, String> roots) {
        String root = roots.get(cat);
        return (root != null ? root : cat + "/") + rel;
    }

    /** Folder of a flat local save in the cloud: characters for .fch, worlds for the rest. */
    private static String catForFlat(String name) {
        return baseSaveName(name).endsWith(".fch") ? CAT_CHARACTERS : CAT_WORLDS;
    }

    /** One local file queued for upload. */
    private static final class Up {
        final File file;
        final String cat, rel;     // rel is also the CloudSyncState key
        final String world;        // non-null for a file of a 1.0 world folder
        Up(File file, String cat, String rel, String world) {
            this.file = file; this.cat = cat; this.rel = rel; this.world = world;
        }
    }

    /** Upload order inside a world: data first, the save set's .fwl2 and its .ok marker last, so
     *  an interrupted push leaves the cloud's previous set as the newest COMPLETE one. */
    private static int worldFileRank(String rel) {
        if (rel.endsWith(".ok")) return 3;
        if (rel.endsWith(".fwl2")) return 2;
        if (rel.endsWith(".db2") || rel.endsWith(".chunks")) return 1;
        return 0;
    }

    /** A 1.0 world folder's share of a push. */
    private static final class WorldPlan {
        final String name;
        final java.util.List<Up> send = new java.util.ArrayList<>();    // files whose content differs
        final java.util.List<String> stale = new java.util.ArrayList<>();     // cloud paths to delete
        final java.util.List<String> staleKeys = new java.util.ArrayList<>(); // their relative paths
        int total;             // files the world has on the phone (cache excluded)
        boolean inCloud;       // the cloud already has files under worlds/<name>/
        long localMs, cloudMs, cloudBytes;
        int sent;
        WorldPlan(String name) { this.name = name; }
    }

    /**
     * Push the instance's saves up to the cloud.
     *
     * Steam takes an upload as a BATCH: open it, then per file declare name/sizes/SHA and receive a
     * list of blocks to PUT, then commit the file, then close the batch. Each block either carries a
     * slice of our payload ({@code blockOffset}/{@code blockLength}) or a body Steam supplies itself
     * ({@code explicitBodyData}).
     *
     * The payload is built the same way the cloud hands files back: a ZIP with a single entry, where
     * {@code fileSize} is the archive and {@code rawFileSize} the real file — that symmetry is why
     * the download side could be decoded, so we mirror it here.
     *
     * What goes up:
     * <ul>
     * <li>flat saves ({@code .fwl/.db/.fch}) lying directly in worlds_local/ and characters_local/,
     *     file by file, as before 1.0;</li>
     * <li>every 1.0 world folder ({@link #isWorldFolder}) as a UNIT: all its files except the
     *     minimap cache go to {@code worlds/<World>/...}; only files whose content differs are
     *     actually sent. Afterwards the cloud files under {@code worlds/<World>/} the phone no longer
     *     has (the previous {@code _main.N} set, chunk names that changed) are deleted — the same
     *     thing the game does on a PC. One confirmation per world, never per chunk.</li>
     * </ul>
     * Uploading REPLACES the cloud copy, i.e. what the PC will next pick up, so the log states for
     * every save whether it creates or replaces.
     */
    private void uploadAll(SteamCloud cloud, java.util.List<Entry> entries,
                           java.util.Map<String, String> roots) {
        CloudSyncState state = CloudSyncState.load(instanceName);
        File saveDir = new File(AppStorage.requireSingleton().getInstanceDir(instanceName),
                "unity3d/IronGate/Valheim");
        File worldsLocal = new File(saveDir, WORLDS_DIR);

        // What the cloud holds, keyed by Valheim folder + relative path.
        java.util.Map<String, Entry> inCloud = new java.util.HashMap<>();
        for (Entry e : entries) inCloud.put(e.cat + "|" + e.rel, e);

        // ---- flat saves: per file ----
        java.util.List<Up> flatPicked = new java.util.ArrayList<>();
        java.util.Set<String> localFlatNames = new java.util.HashSet<>();
        int unchanged = 0;
        for (String sub : new String[]{ WORLDS_DIR, CHARACTERS_DIR }) {
            for (File f : orEmpty(new File(saveDir, sub).listFiles((d, n) -> isSaveFile(n)))) {
                if (!f.isFile()) continue;
                localFlatNames.add(f.getName());
                String cat = catForFlat(f.getName());
                Entry c = inCloud.get(cat + "|" + f.getName());
                // Identical to what's already up there? Sending it again would burn quota and
                // bandwidth and move the cloud timestamp for nothing — and would make us ask about
                // "replacing" a file with itself, e.g. right after pulling.
                if (c != null && sameContent(f, c.info.getShaFile())) { unchanged++; continue; }
                flatPicked.add(new Up(f, cat, f.getName(), null));
            }
        }

        // Deletions travel too, the way Steam does it — but ONLY for saves this instance is on
        // record as having synced. A cloud file we never synced belongs to another instance or to
        // the PC: treating "not in this folder" as "deleted" would let a sync from an instance
        // holding two saves wipe every other world out of the cloud, and off the PC on its next sync.
        java.util.List<String> toDelete = new java.util.ArrayList<>();   // cloud paths
        java.util.List<String> toForget = new java.util.ArrayList<>();   // their record keys
        java.util.Set<String> knownWorlds = new java.util.TreeSet<>();
        for (String known : state.knownNames()) {
            int slash = known.indexOf('/');
            if (slash > 0) { knownWorlds.add(known.substring(0, slash)); continue; }
            if (localFlatNames.contains(known)) continue;
            Entry c = inCloud.get(catForFlat(known) + "|" + known);
            if (c != null) { toDelete.add(c.full); toForget.add(known); }
        }
        // A whole 1.0 world we synced whose folder is gone from the phone: deleted here, so it
        // goes from the cloud as a whole. If the folder still exists (even oddly shaped) we leave
        // the cloud alone — "gone" must mean gone, never "looks unusual".
        java.util.List<String> deletedWorlds = new java.util.ArrayList<>();
        for (String w : knownWorlds) {
            if (new File(worldsLocal, w).exists()) continue;
            int n = 0;
            for (Entry e : entries) if (w.equals(e.world())) { toDelete.add(e.full); n++; }
            deletedWorlds.add(w);
            if (n > 0) progress("World " + w + " was deleted on this phone since the last sync — "
                    + "removing it from the cloud (" + n + " files).");
        }

        // ---- 1.0 world folders: per world ----
        java.util.List<WorldPlan> plans = new java.util.ArrayList<>();
        int worldsUnchanged = 0;
        File[] dirs = orEmpty(worldsLocal.listFiles());
        java.util.Arrays.sort(dirs);
        for (File dir : dirs) {
            if (!isWorldFolder(dir)) continue;
            WorldPlan p = new WorldPlan(dir.getName());
            java.util.Set<String> localRels = new java.util.HashSet<>();
            for (String r : worldFiles(dir)) {
                String rel = p.name + "/" + r;
                File f = new File(dir, r);
                localRels.add(rel);
                p.total++;
                p.localMs = Math.max(p.localMs, f.lastModified());
                Entry c = inCloud.get(CAT_WORLDS + "|" + rel);
                if (c != null && sameContent(f, c.info.getShaFile())) continue;
                p.send.add(new Up(f, CAT_WORLDS, rel, p.name));
            }
            for (Entry e : entries) {
                if (!p.name.equals(e.world())) continue;
                p.inCloud = true;
                p.cloudMs = Math.max(p.cloudMs, e.ts());
                p.cloudBytes += e.info.getRawFileSize();
                if (!localRels.contains(e.rel)) { p.stale.add(e.full); p.staleKeys.add(e.rel); }
            }
            if (p.send.isEmpty() && p.stale.isEmpty()) {
                worldsUnchanged++;
                // Both sides hold this exact world: record it, so a later deletion of the folder
                // here is recognised as ours to propagate.
                for (String rel : localRels) {
                    Entry c = inCloud.get(CAT_WORLDS + "|" + rel);
                    if (c != null) state.remember(rel, hex(c.info.getShaFile()));
                }
                continue;
            }
            p.send.sort((a, b) -> {
                int d = worldFileRank(a.rel) - worldFileRank(b.rel);
                return d != 0 ? d : a.rel.compareTo(b.rel);
            });
            plans.add(p);
        }

        if (flatPicked.isEmpty() && plans.isEmpty() && toDelete.isEmpty()) {
            state.save();
            enumerationCompleted = true;
            done(unchanged + worldsUnchanged > 0
                    ? ("Nothing to send — every save already matches the cloud ("
                       + (unchanged > 0 ? unchanged + " file(s)" : "")
                       + (unchanged > 0 && worldsUnchanged > 0 ? ", " : "")
                       + (worldsUnchanged > 0 ? worldsUnchanged + " world folder(s)" : "") + ").")
                    : ("Nothing to send: no saves in " + saveDir));
            return;
        }
        if (unchanged > 0) progress(unchanged + " save file(s) already match the cloud — skipping those.");
        if (worldsUnchanged > 0)
            progress(worldsUnchanged + " world folder(s) already match the cloud — skipping those.");

        // Saves the cloud already holds would replace what the PC loads next — ask once before any
        // of them goes up, one line per flat file and ONE line per world. New saves need no
        // question and are uploaded regardless of the answer.
        java.util.List<CloudFile> clashes = new java.util.ArrayList<>();
        for (Up u : flatPicked) {
            Entry c = inCloud.get(u.cat + "|" + u.rel);
            if (c != null) clashes.add(new CloudFile(u.rel, c.info.getRawFileSize(), c.ts(), null, 1,
                    u.file.lastModified()));
        }
        for (WorldPlan p : plans)
            if (p.inCloud) clashes.add(new CloudFile(p.name + "/", p.cloudBytes, p.cloudMs, p.name,
                    p.total, p.localMs));
        if (!clashes.isEmpty() && listener instanceof CloudListener) {
            int answer;
            try {
                answer = ((CloudListener) listener).resolvePushConflicts(clashes).get(10, TimeUnit.MINUTES);
            } catch (Throwable t) {
                answer = PUSH_CANCEL;
            }
            if (answer == PUSH_CANCEL) {
                enumerationCompleted = true;
                done("Cancelled — nothing was sent.");
                return;
            }
            if (answer == PUSH_ONLY_NEW) {
                java.util.List<Up> onlyNew = new java.util.ArrayList<>();
                for (Up u : flatPicked) if (!inCloud.containsKey(u.cat + "|" + u.rel)) onlyNew.add(u);
                flatPicked = onlyNew;
                java.util.List<WorldPlan> newWorlds = new java.util.ArrayList<>();
                for (WorldPlan p : plans) if (!p.inCloud) newWorlds.add(p);
                plans = newWorlds;
                if (flatPicked.isEmpty() && plans.isEmpty()) {
                    enumerationCompleted = true;
                    done("Nothing new to send — every save is already in the cloud.");
                    return;
                }
            }
        }
        if (!toDelete.isEmpty())
            progress("Removing " + toDelete.size() + " file(s) from the cloud (deleted here since the "
                    + "last sync).");
        // Name every file: a deletion is the one step a player cannot undo, so the log must say which.
        for (String d : toDelete) Log.i(TAG, "  delete from cloud: " + d);

        java.util.List<Up> uploads = new java.util.ArrayList<>(flatPicked);
        java.util.Map<String, WorldPlan> planOf = new java.util.HashMap<>();
        for (WorldPlan p : plans) { uploads.addAll(p.send); planOf.put(p.name, p); }

        long batchId = 0;
        boolean loopFinished = false;
        int ok = 0, failed = 0;
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Up u : uploads) names.add(cloudPathFor(u.cat, u.rel, roots));
            progress("Opening upload batch for " + uploads.size() + " file(s)"
                    + (plans.isEmpty() ? "" : (" in " + flatPicked.size() + " save file(s) and "
                    + plans.size() + " world folder(s)")) + "…");
            batchId = cloud.beginAppUploadBatch(
                            appId, "ValDroid", names, toDelete,
                            steamClient.getSteamID().convertToUInt64(),   // clientId (undocumented; SteamID works as an id)
                            0L,                                           // appBuildId — we don't track the game's build
                            ioScope())
                    .get(60, TimeUnit.SECONDS)
                    .getBatchID();
            Log.i(TAG, "upload batch id=" + batchId);

            String currentWorld = null;
            for (Up u : uploads) {
                if (!running) break;
                WorldPlan p = u.world == null ? null : planOf.get(u.world);
                if (p != null && !p.name.equals(currentWorld)) {
                    currentWorld = p.name;
                    progress((p.inCloud ? "Replacing" : "Creating") + " world " + p.name + " ("
                            + p.total + " files, " + p.send.size() + " to send"
                            + (p.stale.isEmpty() ? "" : (", " + p.stale.size() + " old to remove"))
                            + ")…");
                }
                if (p == null)
                    progress((inCloud.containsKey(u.cat + "|" + u.rel) ? "Replacing " : "Creating ")
                            + u.rel + " (" + u.file.length() + " B)…");
                String sha = uploadOne(cloud, u.file, cloudPathFor(u.cat, u.rel, roots), batchId, u.rel);
                if (sha != null) {
                    ok++;
                    // Both sides now hold this exact content — record it, so a later deletion
                    // here can be told apart from a file that was never ours.
                    state.remember(u.rel, sha);
                    if (p == null) progress("Sent: " + u.rel);
                    else if (++p.sent == p.send.size())
                        progress("Sent: world " + p.name + " (" + p.total + " files, "
                                + p.send.size() + " uploaded).");
                } else {
                    failed++;
                }
            }
            loopFinished = running;
        } catch (Throwable t) {
            Log.e(TAG, "upload batch failed", t);
            progress("Upload batch error: " + t);
            failed = uploads.size() - ok;
        } finally {
            if (batchId != 0) {
                try { cloud.completeAppUploadBatch(appId, batchId, EResult.OK, ioScope()).get(60, TimeUnit.SECONDS); }
                catch (Throwable t) { Log.w(TAG, "completeAppUploadBatch: " + t); }
            }
        }
        // Deletions went out with the batch; drop them from the record so we don't try again.
        int removed = 0;
        if (batchId != 0) {
            for (String k : toForget) state.forget(k);
            for (String w : deletedWorlds) state.forgetUnder(w + "/");
            removed += toDelete.size();
        }

        // The previous save set and renamed chunks go in a SECOND batch, and only for worlds whose
        // every file made it up. Deleting them in the first batch would, on a failed upload, leave
        // the cloud with half a new set and no old one — a world the PC cannot load. This way a
        // failure leaves the old, complete set in place next to the partial new one.
        java.util.List<String> staleDel = new java.util.ArrayList<>();
        java.util.List<String> staleKeys = new java.util.ArrayList<>();
        int worldsIncomplete = 0;
        for (WorldPlan p : plans) {
            if (!loopFinished || p.sent < p.send.size()) {
                worldsIncomplete++;
                if (!p.stale.isEmpty())
                    progress("World " + p.name + " did not upload completely — leaving its previous "
                            + "save in the cloud; send again to finish.");
                continue;
            }
            staleDel.addAll(p.stale);
            staleKeys.addAll(p.staleKeys);
        }
        if (!staleDel.isEmpty() && running) {
            long b2 = 0;
            try {
                progress("Removing " + staleDel.size() + " outdated world file(s) from the cloud…");
                for (String d : staleDel) Log.i(TAG, "  delete from cloud: " + d);
                b2 = cloud.beginAppUploadBatch(
                                appId, "ValDroid", java.util.Collections.<String>emptyList(), staleDel,
                                steamClient.getSteamID().convertToUInt64(), 0L, ioScope())
                        .get(60, TimeUnit.SECONDS)
                        .getBatchID();
                Log.i(TAG, "cleanup batch id=" + b2);
            } catch (Throwable t) {
                Log.e(TAG, "cleanup batch failed", t);
                progress("Could not remove the outdated world files: " + t + ". The world itself "
                        + "was sent; sending again retries the cleanup.");
            } finally {
                if (b2 != 0) {
                    try { cloud.completeAppUploadBatch(appId, b2, EResult.OK, ioScope()).get(60, TimeUnit.SECONDS); }
                    catch (Throwable t) { Log.w(TAG, "completeAppUploadBatch (cleanup): " + t); }
                }
            }
            if (b2 != 0) {
                for (String k : staleKeys) state.forget(k);
                removed += staleDel.size();
            }
        }

        state.save();
        enumerationCompleted = true;
        int worldsSent = plans.size() - worldsIncomplete;
        done("Send complete: " + ok + " file(s) uploaded"
                + (worldsSent > 0 ? (" (" + worldsSent + " world folder(s))") : "")
                + (removed > 0 ? (", " + removed + " removed from the cloud") : "")
                + (failed > 0 ? (", " + failed + " failed") : "") + "."
                + (ok > 0 ? " Your PC will pick them up next time Steam syncs Valheim." : ""));
        running = false;
    }

    /**
     * Upload one file inside the open batch. Returns its SHA-1 (hex) once Steam has committed it,
     * else null — failures are reported through {@link #progress} under {@code label}.
     */
    private String uploadOne(SteamCloud cloud, File f, String cloudPath, long batchId, String label) {
        try {
            byte[] raw = readFile(f);
            byte[] zip = zipSingleEntry(raw);
            byte[] sha = java.security.MessageDigest.getInstance("SHA-1").digest(raw);
            Log.i(TAG, "uploading " + cloudPath + " (" + zip.length + " B zipped / " + raw.length + " B raw)");

            // Mobile transfers drop mid-flight (the same "connection abort" the download side
            // hit). Retry the whole file — the block URLs are one-shot, so each attempt asks
            // beginFileUpload for a FRESH set. Commit only once the blocks are all through.
            boolean sent = false;
            final int MAX_UP = 4;
            for (int attempt = 1; attempt <= MAX_UP && !sent && running; attempt++) {
                if (attempt > 1) {
                    progress("Retrying " + label + " (" + attempt + "/" + MAX_UP + ")…");
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                }
                FileUploadInfo up = cloud.beginFileUpload(
                                appId, zip.length, raw.length, sha, new java.util.Date(f.lastModified()),
                                cloudPath,
                                -1,                                   // platformsToSync = all
                                steamClient.getCellID() == null ? 0 : steamClient.getCellID(),
                                false,                                // canEncrypt — keep the payload plain
                                false,                                // isSharedFile
                                null,                                 // deprecatedRealm
                                batchId, ioScope())
                        .get(60, TimeUnit.SECONDS);
                sent = putBlocks(up, zip);
            }
            boolean committed = cloud.commitFileUpload(sent, appId, sha, cloudPath, ioScope())
                    .get(60, TimeUnit.SECONDS);
            if (sent && committed) return hex(sha);
            progress("FAILED " + label + " (sent=" + sent + " committed=" + committed + ")");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "upload failed for " + f, t);
            progress("FAILED " + label + ": " + t);
            return null;
        }
    }

    /** PUT/POST every block Steam asked for. Returns false on the first block that doesn't take. */
    private boolean putBlocks(FileUploadInfo up, byte[] payload) {
        java.util.List<in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails> blocks =
                up.getBlockRequests();
        if (blocks == null || blocks.isEmpty()) {
            Log.w(TAG, "no upload blocks returned");
            return false;
        }
        for (int i = 0; i < blocks.size(); i++) {
            in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails b = blocks.get(i);
            String url = (b.getUseHttps() ? "https://" : "http://") + b.getUrlHost() + b.getUrlPath();
            // Steam either supplies the body itself, or wants a slice of our payload.
            byte[] body = (b.getExplicitBodyData() != null && b.getExplicitBodyData().length > 0)
                    ? b.getExplicitBodyData()
                    : java.util.Arrays.copyOfRange(payload, (int) b.getBlockOffset(),
                            Math.min(payload.length, (int) b.getBlockOffset() + b.getBlockLength()));
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setRequestMethod(httpMethodName(b.getHttpMethod()));
                c.setDoOutput(true);
                c.setConnectTimeout(30000);
                c.setReadTimeout(120000);
                c.setFixedLengthStreamingMode(body.length);
                if (b.getRequestHeaders() != null)
                    for (HttpHeaders h : b.getRequestHeaders()) c.setRequestProperty(h.getName(), h.getValue());
                try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
                int code = c.getResponseCode();
                c.disconnect();
                Log.i(TAG, "block " + (i + 1) + "/" + blocks.size() + " " + body.length + " B -> HTTP " + code);
                if (code / 100 != 2) return false;
            } catch (Throwable t) {
                Log.e(TAG, "block " + (i + 1) + " failed", t);
                return false;
            }
        }
        return true;
    }

    /** EHTTPMethod (SteamKit ordering) → the verb HttpURLConnection needs. */
    private static String httpMethodName(int m) {
        switch (m) {
            case 1: return "GET";
            case 2: return "HEAD";
            case 3: return "POST";
            case 5: return "DELETE";
            case 6: return "OPTIONS";
            default: return "PUT";   // 4, and the sane default for a block upload
        }
    }

    /** Wrap the bytes exactly like the cloud does: one ZIP entry (its name is irrelevant — the
     *  download side sees entries called "z"). */
    private static byte[] zipSingleEntry(byte[] raw) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 8 + 1024);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.setLevel(9);
            zos.putNextEntry(new java.util.zip.ZipEntry("z"));
            zos.write(raw);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        byte[] out = new byte[(int) f.length()];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            in.readFully(out);
        }
        return out;
    }

    private static kotlinx.coroutines.CoroutineScope ioScope() {
        return kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(kotlinx.coroutines.Dispatchers.getIO());
    }

    private static String prefixOf(AppFileInfo f, List<String> prefixes) {
        int i = f.getPathPrefixIndex();
        return (i >= 0 && i < prefixes.size()) ? prefixes.get(i) : "";
    }

    /**
     * Fetch one cloud file, decode it (Steam wraps files in a ZIP), and verify SHA-1 against the
     * cloud's {@code shaFile}. Returns the reconstructed raw bytes, or {@code null} on any failure
     * (network aborts after retries, encrypted-without-key, undecodable, or SHA mismatch — a
     * mismatch is a corruption we must NOT write). Uses {@link #progress} for per-file status; the
     * CALLER decides where to write and emits the terminal {@link #done}. Retries the whole fetch a
     * few times with a FRESH download URL each attempt (the Azure SAS url is short-lived/one-shot and
     * mobile networks drop mid-transfer).
     */
    private byte[] fetchAndDecode(SteamCloud cloud, String cloudPath, int rawFileSize, byte[] expectSha,
                                  boolean chatty) {
        try {
            // The Azure blob URL is SAS-signed and short-lived, and the first run hit a mid-transfer
            // "connection abort" (mobile networks drop). Retry the WHOLE fetch — a FRESH download URL
            // each attempt (an expired/one-shot SAS can't be re-GET'd) — a few times before giving up.
            byte[] wire = null;
            FileDownloadInfo info = null;
            final int MAX = 4;
            for (int attempt = 1; attempt <= MAX && wire == null; attempt++) {
                try {
                    // A 1.0 world is dozens of chunk files: its per-file chatter goes to logcat
                    // only, and the caller reports the world as one line.
                    String req = "Requesting download URL for " + baseName(cloudPath)
                            + " (attempt " + attempt + "/" + MAX + ")…";
                    if (chatty || attempt > 1) progress(req); else Log.i(TAG, req);
                    // Kotlin default args (realm, forceProxy, parentScope) aren't visible from Java — pass all.
                    info = cloud.clientFileDownload(
                                    appId, cloudPath,
                                    in.dragonbra.javasteam.enums.ESteamRealm.SteamGlobal,
                                    false,
                                    kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                                            kotlinx.coroutines.Dispatchers.getIO()))
                            .get(60, TimeUnit.SECONDS);

                    String url = (info.getUseHttps() ? "https://" : "http://")
                            + info.getUrlHost() + info.getUrlPath();
                    Log.i(TAG, "download info: url=" + url + " encrypted=" + info.getEncrypted()
                            + " fileSize=" + info.getFileSize() + " rawFileSize=" + info.getRawFileSize()
                            + " headers=" + (info.getRequestHeaders() == null ? 0 : info.getRequestHeaders().size()));
                    String dl = "Downloading " + String.format(java.util.Locale.ROOT, "%,d", info.getFileSize())
                            + " B (raw " + String.format(java.util.Locale.ROOT, "%,d", info.getRawFileSize())
                            + ", encrypted=" + info.getEncrypted() + ")…";
                    if (chatty) progress(dl); else Log.i(TAG, dl);

                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    if (info.getRequestHeaders() != null)
                        for (HttpHeaders h : info.getRequestHeaders())
                            conn.setRequestProperty(h.getName(), h.getValue());

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        Log.w(TAG, "attempt " + attempt + ": HTTP " + code + " " + conn.getResponseMessage());
                        conn.disconnect();
                        Thread.sleep(1500L);
                        continue;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                            Math.max(1024, info.getFileSize()));
                    try (java.io.InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    } finally {
                        conn.disconnect();
                    }
                    byte[] got = bos.toByteArray();
                    // A truncated transfer (abort) leaves fewer bytes than fileSize — treat as a retry.
                    if (info.getFileSize() > 0 && got.length < info.getFileSize()) {
                        Log.w(TAG, "attempt " + attempt + ": short read " + got.length + "/" + info.getFileSize());
                        Thread.sleep(1500L);
                        continue;
                    }
                    wire = got;
                } catch (Throwable t) {
                    Log.w(TAG, "attempt " + attempt + " failed: " + t);
                    if (attempt < MAX) { try { Thread.sleep(1500L); } catch (InterruptedException ignored) {} }
                }
            }
            if (wire == null) {
                progress("FAILED " + baseName(cloudPath) + ": network aborts after " + MAX + " attempts.");
                return null;
            }
            String magic = wire.length >= 4
                    ? String.format("%02x %02x %02x %02x", wire[0], wire[1], wire[2], wire[3]) : "(short)";
            Log.i(TAG, "downloaded " + wire.length + " bytes, magic=" + magic);

            if (info.getEncrypted()) {
                progress("FAILED " + baseName(cloudPath) + ": ENCRYPTED (magic " + magic
                        + ") — need the cloud key; skipping.");
                return null;
            }

            // Reconstruct the original bytes. Steam Cloud wraps each file in a ZIP (observed magic
            // "PK\3\4" 50 4b 03 04) whose single entry IS the real file; the fileSize/rawFileSize pair
            // is the zip vs contained size. So: sizes-match → plain; PK magic → unzip first entry;
            // else fall back to raw zlib inflate (some files may differ).
            byte[] raw = null;
            String method = null;
            boolean isZip = wire.length >= 4 && wire[0] == 0x50 && wire[1] == 0x4b
                    && wire[2] == 0x03 && wire[3] == 0x04;
            if (wire.length == rawFileSize) {
                raw = wire; method = "plain (no compression)";
            } else if (isZip) {
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(wire))) {
                    java.util.zip.ZipEntry e = zin.getNextEntry();
                    if (e != null) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(rawFileSize);
                        byte[] buf = new byte[1 << 16];
                        int m;
                        while ((m = zin.read(buf)) != -1) out.write(buf, 0, m);
                        raw = out.toByteArray();
                        method = "zip-entry '" + e.getName() + "'";
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "zip extract failed: " + t);
                }
            } else {
                try {
                    java.util.zip.Inflater inf = new java.util.zip.Inflater();   // zlib fallback
                    inf.setInput(wire);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(rawFileSize);
                    byte[] buf = new byte[1 << 16];
                    while (!inf.finished()) {
                        int m = inf.inflate(buf);
                        if (m == 0) { if (inf.finished() || inf.needsInput()) break; }
                        out.write(buf, 0, m);
                    }
                    inf.end();
                    raw = out.toByteArray();
                    method = "zlib-inflate";
                } catch (Throwable t) {
                    Log.w(TAG, "zlib inflate failed: " + t);
                }
            }
            if (raw == null) {
                progress("FAILED " + baseName(cloudPath) + ": undecodable (magic " + magic + ").");
                return null;
            }

            // A SHA mismatch = corruption. Never write a corrupt save into an instance.
            String sha = sha1Hex(raw);
            String expect = expectSha == null ? null : hex(expectSha);
            if (expect != null && !expect.equalsIgnoreCase(sha)) {
                progress("FAILED " + baseName(cloudPath) + ": SHA-1 mismatch (got " + sha + ").");
                return null;
            }
            Log.i(TAG, "decoded " + baseName(cloudPath) + " via " + method + ": " + raw.length
                    + " B, SHA-1 " + (expect == null ? "(no reference)" : "OK"));
            return raw;
        } catch (Throwable t) {
            Log.e(TAG, "fetch/decode failed for " + cloudPath, t);
            progress("FAILED " + baseName(cloudPath) + ": " + t);
            return null;
        }
    }

    /** listFiles() returns null for a missing/unreadable dir — treat that as "nothing there". */
    private static File[] orEmpty(File[] fs) { return fs == null ? new File[0] : fs; }

    /** Delete a file or a whole folder tree. Returns true when nothing is left. */
    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) for (File c : orEmpty(f.listFiles())) deleteRecursive(c);
        return f.delete() || !f.exists();
    }

    /** Copy one file, keeping its mtime (the cloud's date, which later comparisons rely on). */
    public static boolean copyFile(File src, File dest) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (Throwable t) {
            Log.w(TAG, "copy failed " + src + " -> " + dest + ": " + t);
            return false;
        }
        dest.setLastModified(src.lastModified());
        return true;
    }

    private static boolean writeFile(File dest, byte[] data) {
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(dest)) {
            os.write(data);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "write failed for " + dest + ": " + t);
            return false;
        }
    }

    /**
     * Is this local file byte-for-byte what the cloud holds? Compares against the SHA-1 the cloud
     * reports for that name, so "has it changed" is answered by CONTENT rather than by timestamps
     * (which drift across devices and say nothing about the bytes). False whenever we can't be sure —
     * an unreadable file or a missing hash means "treat as different", never skip on a guess.
     */
    private static boolean sameContent(File local, byte[] cloudSha1) {
        if (local == null || !local.isFile() || cloudSha1 == null || cloudSha1.length == 0) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(local)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return java.util.Arrays.equals(md.digest(), cloudSha1);
        } catch (Throwable t) {
            Log.w(TAG, "sameContent check failed for " + local + ": " + t);
            return false;
        }
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Cache-test writer (used only when no instance is targeted). */
    private File writeTestFile(String filename, byte[] data, String suffix) {
        try {
            File dir = new File(AppStorage.requireSingleton().getCachePath(), "cloud_test");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File out = new File(dir, baseName(filename) + suffix);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) { os.write(data); }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "writeTestFile failed: " + t);
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String sha1Hex(byte[] data) throws java.security.NoSuchAlgorithmException {
        return hex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
    }

    /** SHA-1 of a file as hex, or null if it can't be read — the key the sync record is built on. */
    public static String sha1Hex(File f) {
        if (f == null || !f.isFile()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable t) {
            Log.w(TAG, "sha1 failed for " + f + ": " + t);
            return null;
        }
    }

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
