package com.valdroid.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.valdroid.AppStorage;
import com.valdroid.R;
import com.valdroid.SteamCloudSpike;
import com.valdroid.SteamDownloadSpike;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Steam Cloud saves — move saves between an instance on the phone and the copy Steam keeps in sync
 * with a PC. Sits beside "Steam Downloads" in the drawer and shares its session model: credentials
 * plus a Steam-Mobile approval, token in memory only, never stored.
 *
 * Deliberately NO per-file picking. Getting saves fetches everything in one connection into a scratch
 * folder and then hangs up; only afterwards — offline, unhurried — are the saves moved into place,
 * asking only where one already exists. Flat saves (pre-1.0 .fwl/.db worlds, .fch characters,
 * backups, server lists) are placed file by file; a Valheim 1.0 world FOLDER (worlds/<World>/…) is
 * placed and asked about as one unit. Sending mirrors that: new saves go up silently, existing ones
 * are asked about once (one line per world, not per chunk). Choosing files up front would mean holding the Steam session open while
 * a human deliberates (the connection dies if the app is backgrounded) and a second sign-in approval.
 */
public class CloudSavesFragment extends Fragment {

    /** RimWorld's Steam app id — the only game this launcher manages. */
    private static final int APP_ID = com.valdroid.SteamDownloadSpike.APP_ID;   // Valheim

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextInputEditText etUser, etPass;
    private Spinner spInstance;
    private Button btnGo;
    private TextView goNote, log;
    private ProgressBar progress;

    private List<GameInstance> instances;
    private boolean pullDirection = true;   // true = from cloud, false = to cloud

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cloud_saves, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etUser = view.findViewById(R.id.et_cloud_user);
        etPass = view.findViewById(R.id.et_cloud_pass);
        spInstance = view.findViewById(R.id.sp_cloud_instance);
        btnGo = view.findViewById(R.id.btn_cloud_go);
        goNote = view.findViewById(R.id.tv_cloud_go_note);
        progress = view.findViewById(R.id.pb_cloud);
        log = view.findViewById(R.id.tv_cloud_log);
        log.setMovementMethod(new ScrollingMovementMethod());

        // Position 0 is a PROMPT, never a real instance: with several instances a pre-selected
        // default would let the user move saves in or out of the wrong game without ever touching
        // the spinner.
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();
        names.add(getString(instances.isEmpty()
                ? R.string.cloud_saves_no_instances : R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        spInstance.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names));
        // With exactly one instance there is no wrong game to pick, so select it up front.
        if (instances.size() == 1) spInstance.setSelection(1);

        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggle_cloud_dir);
        toggle.check(R.id.btn_dir_pull);
        toggle.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            pullDirection = (checkedId == R.id.btn_dir_pull);
            btnGo.setText(pullDirection ? R.string.cloud_saves_pull : R.string.cloud_saves_push);
            goNote.setText(pullDirection ? R.string.cloud_saves_pull_note : R.string.cloud_saves_push_note);
        });
        btnGo.setEnabled(true);
        btnGo.setOnClickListener(v -> onGo());
    }

    private GameInstance chosenInstance() {
        int pos = spInstance.getSelectedItemPosition();
        return (pos >= 1 && pos <= instances.size()) ? instances.get(pos - 1) : null;
    }

    /** The folder holding Valheim's {@code worlds_local/} and {@code characters_local/}. */
    private File savesDirOf(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getInstanceDir(gi.getName()),
                "unity3d/IronGate/Valheim");
    }

    /** Where one pulled file belongs: worlds and characters live in separate folders. */
    private File destDirFor(File savesDir, String name) {
        File dir = new File(savesDir, SteamCloudSpike.localDirFor(name));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Scratch folder the cloud copy lands in before anything touches the real saves. */
    private File pullTempDir(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getCachePath(), "cloud_pull/" + gi.getName());
    }

    private void onGo() {
        GameInstance gi = chosenInstance();
        if (gi == null) {
            Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String u = text(etUser), p = text(etPass);
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_need_login, Toast.LENGTH_SHORT).show();
            return;
        }
        busy(true);
        log.setText("");
        if (pullDirection) {
            final File temp = pullTempDir(gi);
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPull(u, p, APP_ID, temp, savesDirOf(gi), new Callbacks() {
                @Override public void onDone(String message) {
                    ui.post(() -> {
                        appendLog("— " + message);
                        // Connection is closed by now: place the files at leisure.
                        placeAll(temp, savesDirOf(gi));
                    });
                }
            }), "CloudSavesPull").start();
        } else {
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPush(u, p, APP_ID, gi.getName(), new Callbacks()),
                    "CloudSavesPush").start();
        }
    }

    // ===== placement: runs offline, after the Steam session is gone =====

    /** A whole Valheim 1.0 world folder waiting in the scratch dir — placed as one unit. */
    private static final class WorldUnit {
        final File dir;
        WorldUnit(File dir) { this.dir = dir; }
    }

    /**
     * What the pull left in the scratch dir, as placement units. The scratch mirrors the cloud:
     * {@code worlds/<World>/} folders (1.0 worlds, one unit each), and flat files under
     * {@code worlds/}, {@code characters/}, {@code serverlist/} or {@code other/} (one unit per
     * file, placed by name exactly as before 1.0).
     */
    private static List<Object> collectPulled(File temp) {
        List<Object> units = new ArrayList<>();
        List<Object> flat = new ArrayList<>();
        File[] cats = temp.listFiles();
        if (cats == null) return units;
        java.util.Arrays.sort(cats);
        for (File cat : cats) {
            if (cat.isFile()) { flat.add(cat); continue; }
            File[] fs = cat.listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs);
            for (File f : fs) {
                if (f.isFile()) flat.add(f);
                else if (f.isDirectory() && SteamCloudSpike.CAT_WORLDS.equals(cat.getName()))
                    units.add(new WorldUnit(f));
            }
        }
        units.addAll(flat);   // worlds first: they are what a 1.0 player came for
        return units;
    }

    /** Move every downloaded save into place, asking only where it already exists. */
    private void placeAll(File temp, File savesDir) {
        List<Object> pending = collectPulled(temp);
        if (pending.isEmpty()) { busy(false); return; }
        if (!savesDir.isDirectory() && !savesDir.mkdirs()) {
            appendLog("Cannot create " + savesDir);
            busy(false);
            return;
        }
        // Repair earlier pulls before adding to them: character backups ("*.fch.old") used to be
        // filed under worlds_local/, where Valheim cannot use them as the fallback for an
        // unreadable live save. Cheap, idempotent, and it only moves files whose name is free at
        // the destination.
        int migrated = SteamCloudSpike.migrateMisplaced(savesDir);
        if (migrated > 0) appendLog(getString(R.string.cloud_saves_migrated, migrated));
        // Worlds and characters go to different folders; destDirFor creates whichever is needed.
        GameInstance gi = chosenInstance();
        placeNext(pending, 0, savesDir,
                gi == null ? null : com.valdroid.CloudSyncState.load(gi.getName()),
                new int[]{0, 0});   // {copied, kept}
    }

    /** One unit at a time, because a clash needs an answer before the next one is touched. */
    private void placeNext(List<Object> pending, int i, File savesDir,
                           com.valdroid.CloudSyncState state, int[] tally) {
        if (!isAdded()) return;
        if (i >= pending.size()) {
            if (state != null) state.save();   // record what now matches the cloud
            appendLog(getString(R.string.cloud_saves_place_done, tally[0], tally[1]));
            busy(false);
            return;
        }
        if (pending.get(i) instanceof WorldUnit) {
            placeWorld((WorldUnit) pending.get(i), savesDir, state, tally,
                    () -> placeNext(pending, i + 1, savesDir, state, tally));
            return;
        }
        File src = (File) pending.get(i);
        File dest = new File(destDirFor(savesDir, src.getName()), src.getName());
        if (!dest.exists()) {
            copyInto(src, dest, tally, state);
            placeNext(pending, i + 1, savesDir, state, tally);
            return;
        }
        // Same name on both sides — show both dates and let the user decide, like Steam does.
        boolean cloudNewer = src.lastModified() > dest.lastModified();
        String msg = getString(R.string.cloud_saves_clash_msg,
                fmtDate(dest.lastModified()), fmtDate(src.lastModified()),
                getString(cloudNewer ? R.string.cloud_saves_clash_cloud_newer
                                     : R.string.cloud_saves_clash_local_newer));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.cloud_saves_clash_title, src.getName()))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.cloud_saves_clash_replace, (d, w) -> {
                    copyInto(src, dest, tally, state);
                    placeNext(pending, i + 1, savesDir, state, tally);
                })
                .setNegativeButton(R.string.cloud_saves_clash_keep, (d, w) -> {
                    // Sides deliberately left different — drop any record, so this name is not
                    // mistaken later for "the user deleted it" and removed from the cloud.
                    if (state != null) state.forget(src.getName());
                    appendLog(getString(R.string.cloud_saves_log_kept, src.getName()));
                    tally[1]++;
                    placeNext(pending, i + 1, savesDir, state, tally);
                })
                .setNeutralButton(R.string.cloud_saves_clash_both, (d, w) -> {
                    // The cloud copy lands under a new name, so neither name matches the cloud now.
                    if (state != null) state.forget(src.getName());
                    copyInto(src, uniqueName(destDirFor(savesDir, src.getName()), src.getName()), tally, null);
                    placeNext(pending, i + 1, savesDir, state, tally);
                })
                .show();
    }

    /**
     * Place one Valheim 1.0 world folder. Not on the phone yet → copied in. Already here → asked
     * ONCE for the whole world, showing the newest file on each side like the per-file dialog.
     * "Use the cloud one" moves this phone's folder aside to
     * {@code <World>_backup_valdroid-<yyyyMMdd-HHmmss>/} and then installs the cloud folder whole.
     *
     * Never file by file: a world is one {@code _main.N} save set plus the chunk files it names, and
     * mixing files of two save numbers produces a world whose metadata and chunks disagree.
     * Moving the old folder aside (rather than deleting it) keeps the phone's progress recoverable —
     * the game lists "_backup_" folders as backups, and the push never uploads them.
     */
    private void placeWorld(WorldUnit unit, File savesDir, com.valdroid.CloudSyncState state,
                            int[] tally, Runnable next) {
        File worldsDir = new File(savesDir, SteamCloudSpike.WORLDS_DIR);
        //noinspection ResultOfMethodCallIgnored
        worldsDir.mkdirs();
        String name = unit.dir.getName();
        File dest = new File(worldsDir, name);
        if (!dest.exists()) {
            installWorld(unit.dir, dest, tally, state);
            next.run();
            return;
        }
        long localMs = SteamCloudSpike.newestMtime(dest);
        long cloudMs = SteamCloudSpike.newestMtime(unit.dir);
        String aside = name + SteamCloudSpike.VALDROID_BACKUP_TAG
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new Date());
        String msg = getString(R.string.cloud_saves_clash_msg,
                fmtDate(localMs), fmtDate(cloudMs),
                getString(cloudMs > localMs ? R.string.cloud_saves_clash_cloud_newer
                                            : R.string.cloud_saves_clash_local_newer))
                + "\n\n" + getString(R.string.cloud_saves_world_clash_note, aside);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.cloud_saves_world_clash_title, name))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.cloud_saves_clash_replace, (d, w) -> {
                    File asideDir = new File(worldsDir, aside);
                    if (!dest.renameTo(asideDir)) {
                        appendLog(getString(R.string.cloud_saves_log_world_failed, name));
                        tally[1]++;
                        next.run();
                        return;
                    }
                    appendLog(getString(R.string.cloud_saves_log_world_moved_aside, name, aside));
                    if (!installWorld(unit.dir, dest, tally, state)) {
                        // Put the phone's world back where the game looks for it: a failed install
                        // must not leave the player with neither copy under the world's name.
                        SteamCloudSpike.deleteRecursive(dest);
                        if (asideDir.renameTo(dest))
                            appendLog(getString(R.string.cloud_saves_log_world_restored, name));
                        tally[1]++;
                    }
                    next.run();
                })
                .setNegativeButton(R.string.cloud_saves_clash_keep, (d, w) -> {
                    // Sides deliberately left different — drop the world's records, so none of its
                    // files is mistaken later for "the user deleted it" and removed from the cloud.
                    if (state != null) state.forgetUnder(name + "/");
                    appendLog(getString(R.string.cloud_saves_log_world_kept, name));
                    tally[1]++;
                    next.run();
                })
                .show();
    }

    /**
     * Copy a pulled world folder in as a whole: into a hidden sibling first, then one rename, so the
     * game never sees a half-copied world under the real name. Returns false (nothing left behind)
     * on any failure.
     */
    private boolean installWorld(File src, File dest, int[] tally, com.valdroid.CloudSyncState state) {
        String name = dest.getName();
        File tmp = new File(dest.getParentFile(), "." + name + ".valdroid-partial");
        SteamCloudSpike.deleteRecursive(tmp);
        List<String> rels = SteamCloudSpike.worldFiles(src);
        boolean ok = tmp.mkdirs();
        for (String rel : rels) {
            if (!ok) break;
            File to = new File(tmp, rel);
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            ok = SteamCloudSpike.copyFile(new File(src, rel), to);
        }
        if (ok) ok = tmp.renameTo(dest);
        if (!ok) {
            SteamCloudSpike.deleteRecursive(tmp);
            appendLog(getString(R.string.cloud_saves_log_world_failed, name));
            return false;
        }
        if (state != null) {
            // Exactly this world's files now match the cloud; records of an older save set go.
            state.forgetUnder(name + "/");
            for (String rel : rels)
                state.remember(name + "/" + rel, SteamCloudSpike.sha1Hex(new File(dest, rel)));
        }
        tally[0]++;
        appendLog(getString(R.string.cloud_saves_log_world_placed, name, rels.size()));
        SteamCloudSpike.deleteRecursive(src);
        return true;
    }

    /** "Vikingworld.fwl" -> "Vikingworld (from cloud).fwl", and "… 2" etc. if that is taken too.
     *  A world is a .fwl + .db pair: renaming only one of them would leave the pair broken, so the
     *  "keep both" option is best used on a whole world (both files) or a character. */
    private File uniqueName(File dir, String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        String suffix = getString(R.string.cloud_saves_from_cloud_suffix);
        File f = new File(dir, base + " " + suffix + ext);
        for (int n = 2; f.exists(); n++) f = new File(dir, base + " " + suffix + " " + n + ext);
        return f;
    }

    /** Copy one downloaded save into place. Passing {@code state} records that this name now holds
     *  exactly what the cloud holds — the fact a later deletion here is judged against. */
    private void copyInto(File src, File dest, int[] tally, com.valdroid.CloudSyncState state) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            dest.setLastModified(src.lastModified());   // keep the cloud's date, for later comparisons
            tally[0]++;
            appendLog(getString(R.string.cloud_saves_log_placed, dest.getName()));
            if (state != null) state.remember(dest.getName(), SteamCloudSpike.sha1Hex(dest));
            src.delete();
        } catch (Throwable t) {
            appendLog("FAILED " + dest.getName() + ": " + t);
        }
    }

    // ===== shared Steam plumbing =====

    private class Callbacks implements SteamCloudSpike.CloudListener {
        @Override public void onFileList(List<SteamCloudSpike.CloudFile> files) { /* unused */ }

        /** Sending would overwrite these on the PC — ask once, naming them with both dates. */
        @Override
        public CompletableFuture<Integer> resolvePushConflicts(List<SteamCloudSpike.CloudFile> clashes) {
            final CompletableFuture<Integer> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(SteamCloudSpike.PUSH_CANCEL); return; }
                StringBuilder sb = new StringBuilder();
                for (SteamCloudSpike.CloudFile cf : clashes) {
                    // A 1.0 world is one line for the whole folder, never a list of its chunks.
                    String label = cf.worldName != null
                            ? getString(R.string.cloud_saves_world_label, cf.worldName, cf.fileCount)
                            : cf.filename;
                    sb.append("\n• ").append(label)
                      .append("\n   ").append(getString(R.string.cloud_saves_clash_in_cloud,
                              fmtDate(cf.timestampMs)));
                    // The worker knows where the local copy lives (a world folder, or a file in
                    // worlds_local/ or characters_local/), so it hands us the date directly.
                    if (cf.localTimestampMs > 0)
                        sb.append("\n   ").append(getString(R.string.cloud_saves_clash_on_phone,
                                fmtDate(cf.localTimestampMs)));
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.cloud_saves_push_confirm_title)
                        .setMessage(getString(R.string.cloud_saves_push_confirm_replace, sb.toString()))
                        .setCancelable(false)
                        .setPositiveButton(R.string.cloud_saves_push_replace,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_REPLACE))
                        .setNeutralButton(R.string.cloud_saves_push_only_new,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_ONLY_NEW))
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_CANCEL))
                        .show();
            });
            return fut;
        }

        @Override
        public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
            final CompletableFuture<String> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(""); return; }
                final android.widget.EditText et = new android.widget.EditText(requireContext());
                et.setHint(email != null ? getString(R.string.cloud_saves_code_email, email)
                                         : getString(R.string.cloud_saves_code));
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(prevWrong ? R.string.cloud_saves_code_wrong : R.string.cloud_saves_code_title)
                        .setView(et)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> fut.complete(et.getText().toString().trim()))
                        .show();
            });
            return fut;
        }

        @Override public void onProgress(String message) { ui.post(() -> appendLog(message)); }

        @Override public void onDone(String message) {
            ui.post(() -> { appendLog("— " + message); busy(false); });
        }
    }

    private void busy(boolean b) {
        if (!isAdded()) return;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnGo.setEnabled(!b);
        // Hold the process at foreground-service priority for the whole operation. Android freezes a
        // backgrounded app and kills its Steam connection mid-transfer — the failure the game
        // downloader already hit — and signing in deliberately sends the user to Steam Mobile.
        android.content.Context appCtx = requireContext().getApplicationContext();
        if (b) com.valdroid.DownloadKeepAliveService.start(appCtx, getString(R.string.cloud_saves_keepalive));
        else com.valdroid.DownloadKeepAliveService.stop(appCtx);
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private String fmtDate(long ms) {
        if (ms <= 0) return "?";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    /** Append a line and keep the console scrolled to the bottom (so it never looks frozen). */
    private void appendLog(String line) {
        if (!isAdded() || log == null) return;
        log.append(line + "\n");
        final int scroll = log.getLayout() == null ? 0
                : log.getLayout().getLineTop(log.getLineCount()) - log.getHeight();
        if (scroll > 0) log.scrollTo(0, scroll);
    }
}
