package com.valdroid.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.valdroid.InstallerService;
import com.valdroid.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class NewInstanceFragment extends Fragment {

    // The instance name is a directory name inside the built-in X server's Unix-socket path
    // (<home>/instances/<name>/tmp/.X11-unix/X0). Android's sun_path is only 108 bytes and our
    // native binder silently truncates an over-long path, so a long name makes the X server fail
    // to bind and the whole launch crashes with "Failed to allocate XConnectorEpoll" — the game
    // never starts (seen on a Mi 10T Pro, 2026-07-23, whose name was auto-filled from a long zip
    // filename). Cap the name well under the byte budget: the fixed prefix+suffix take ~59 bytes,
    // leaving ~48; 40 keeps a margin for work-profile/cloned-app user dirs (/data/user/<n>/...).
    private static final int MAX_NAME_LEN = 40;
    /** The first install phase happens here, before InstallerService: copying the picked archive. */
    private static final String PHASE_COPY = "copy";

    /**
     * Optional navigation argument: the absolute path of an archive to install, so the screen opens
     * with the file already chosen and the picker is never needed. Used by the GOG downloader,
     * which hands over the installer it just fetched.
     */
    public static final String ARG_PRESELECTED_FILE = "preselected_file";

    /**
     * Optional navigation argument, alongside {@link #ARG_PRESELECTED_FILE}: absolute paths of GOG
     * expansion installers to extract into the new instance after the game, so it comes up with its
     * expansions in one step. Dropped if the user picks a different archive — they belong to the
     * game that was handed over.
     */
    public static final String ARG_EXTRA_FILES = "extra_files";

    private EditText etInstanceName;
    private Button   btnPickZip;
    private Button   btnInstall;
    private TextView tvSelectedZip;

    private Uri selectedZipUri;
    /** Expansion installers riding along with the handed-over game (see ARG_EXTRA_FILES). */
    private ArrayList<String> extraInstallers = new ArrayList<>();
    private String lastInstanceName;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Install progress dialog — the same one Zomdroid shows, so a multi-minute install never looks
    // like a hang: copying the archive, extracting it (both with a real percentage and a time
    // estimate), then a short setup step without a measurable size.
    private androidx.appcompat.app.AlertDialog progressDialog;
    private com.valdroid.databinding.TaskProgressDialogBinding pdb;
    private String curPhase;
    private long phaseStartMs, phaseStartDone;

    private final BroadcastReceiver installerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (InstallerService.BROADCAST_PROGRESS.equals(action)) {
                String phase = intent.getStringExtra(InstallerService.EXTRA_PHASE);
                if (phase == null) return;   // a plain log line, not for the dialog
                int label = InstallerService.PHASE_EXTRACT.equals(phase)
                        ? R.string.install_phase_extract : R.string.install_phase_setup;
                showProgress(phase, label, intent.getLongExtra(InstallerService.EXTRA_DONE, -1),
                        intent.getLongExtra(InstallerService.EXTRA_TOTAL, -1));
            } else if (InstallerService.BROADCAST_DONE.equals(action)) {
                if (progressDialog != null) progressDialog.dismiss();
                adviseDriverThenLeave();
            } else if (InstallerService.BROADCAST_ERROR.equals(action)) {
                mainHandler.post(() -> {
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                    String msg = intent.getStringExtra(InstallerService.EXTRA_MESSAGE);
                    etInstanceName.setError(msg != null ? msg : getString(R.string.error_name_required));
                    showProgressError(msg);
                });
            }
        }
    };

    private void ensureProgressDialog() {
        if (progressDialog != null) return;
        pdb = com.valdroid.databinding.TaskProgressDialogBinding.inflate(getLayoutInflater());
        progressDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setView(pdb.getRoot())
                .setCancelable(false)   // the work goes on in the service either way
                .create();
        pdb.progressDialogOkMb.setOnClickListener(v -> progressDialog.dismiss());
    }

    /** One progress update; {@code done} or {@code total} < 0 = no percentage, the bar just runs. */
    private void showProgress(String phase, int labelRes, long done, long total) {
        if (!isAdded()) return;
        ensureProgressDialog();
        long now = android.os.SystemClock.elapsedRealtime();
        if (!phase.equals(curPhase)) { curPhase = phase; phaseStartMs = now; phaseStartDone = Math.max(0, done); }

        pdb.progressDialogTitleTv.setText(R.string.install_progress_title);
        StringBuilder msg = new StringBuilder(getString(labelRes));
        if (done >= 0 && total > 0) {
            int permille = (int) Math.min(1000, done * 1000 / total);
            msg.append(" — ").append(permille / 10).append('%');
            String eta = eta(now, done, total);
            if (eta != null) msg.append("  ·  ").append(eta);
            pdb.progressDialogProgressLpi.setIndeterminate(false);
            pdb.progressDialogProgressLpi.setMax(1000);   // permille: byte counts overflow an int
            pdb.progressDialogProgressLpi.setProgress(permille);
        } else {
            pdb.progressDialogProgressLpi.setIndeterminate(true);
        }
        pdb.progressDialogMessageTv.setText(msg);
        pdb.progressDialogProgressLpi.setVisibility(View.VISIBLE);
        pdb.progressDialogOkMb.setVisibility(View.GONE);
        if (!progressDialog.isShowing()) progressDialog.show();
    }

    /** Time left from this phase's measured rate; null until there are a few seconds to go on. */
    private String eta(long now, long done, long total) {
        long elapsedMs = now - phaseStartMs;
        if (elapsedMs < 3000 || done <= phaseStartDone) return null;
        double rate = (done - phaseStartDone) / (elapsedMs / 1000.0);   // bytes per second
        long secs = (long) ((total - done) / rate);
        if (secs < 60) return getString(R.string.install_eta_under_minute);
        return getString(R.string.install_eta_minutes, (int) Math.ceil(secs / 60.0));
    }

    private void showProgressError(String msg) {
        if (!isAdded()) return;
        ensureProgressDialog();
        pdb.progressDialogTitleTv.setText(R.string.install_failed_title);
        pdb.progressDialogMessageTv.setText(msg != null ? msg : "");
        pdb.progressDialogProgressLpi.setVisibility(View.GONE);
        pdb.progressDialogOkMb.setVisibility(View.VISIBLE);
        curPhase = null;
        if (!progressDialog.isShowing()) progressDialog.show();
    }

    private final ActivityResultLauncher<String[]> zipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedZipUri = uri;
                extraInstallers = new ArrayList<>();   // they belonged to the handed-over game
                tvSelectedZip.setText(uri.getLastPathSegment());
                // Deliberately do NOT auto-fill the name from the zip filename: repack zips carry
                // very long names that blow the X-socket path budget (see MAX_NAME_LEN). The field
                // is pre-filled with a short free default in onViewCreated; the user can still edit
                // it, and startInstall enforces the length + collision checks.
                if (etInstanceName.getText().toString().trim().isEmpty()) {
                    etInstanceName.setText(freeDefaultName());
                }
                btnInstall.setEnabled(true);
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_new_instance, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etInstanceName = view.findViewById(R.id.et_instance_name);
        btnPickZip     = view.findViewById(R.id.btn_pick_zip);
        btnInstall     = view.findViewById(R.id.btn_install);
        tvSelectedZip  = view.findViewById(R.id.tv_selected_zip);

        btnInstall.setEnabled(false);

        // Opened with the file already chosen (see ARG_PRESELECTED_FILE). The picker below is
        // deliberately ZIP-only because most file explorers will not hand over a bare GOG .sh —
        // but a path we produced ourselves never goes through the picker, so that restriction does
        // not apply here, and InstallerService sniffs content rather than the extension anyway.
        String preselected = getArguments() == null
                ? null : getArguments().getString(ARG_PRESELECTED_FILE);
        if (preselected != null && !preselected.isEmpty()) {
            File chosen = new File(preselected);
            if (chosen.isFile()) {
                selectedZipUri = Uri.fromFile(chosen);
                ArrayList<String> extras = getArguments().getStringArrayList(ARG_EXTRA_FILES);
                if (extras != null) extraInstallers = extras;
                tvSelectedZip.setText(extraInstallers.isEmpty() ? chosen.getName()
                        : getString(R.string.new_instance_with_dlc,
                                chosen.getName(), extraInstallers.size()));
                btnInstall.setEnabled(true);
            }
        }

        // Pre-fill a short, always-fits default so most users just tap Install and never hit the
        // name-length limit; the field stays editable for anyone who wants a custom name.
        if (etInstanceName.getText().toString().trim().isEmpty()) {
            etInstanceName.setText(freeDefaultName());
        }

        // The game's Linux .zip.
        // sniffs the content and unpacks the .sh files found inside.
        btnPickZip.setOnClickListener(v -> zipPicker.launch(com.valdroid.C.mime.GAME_ARCHIVE));

        btnInstall.setOnClickListener(v -> startInstall());

        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_PROGRESS);
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(installerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        requireContext().unregisterReceiver(installerReceiver);
        if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; pdb = null; }
    }

    /** On install success: detect the GPU, set the recommended driver on the new instance, show a
     *  one-time dialog, then return to the launcher. */
    private void adviseDriverThenLeave() {
        final String inst = lastInstanceName;
        if (inst == null) { Navigation.findNavController(requireView()).popBackStack(); return; }
        new Thread(() -> {
            final com.valdroid.GpuDriverAdvisor.Result r =
                    com.valdroid.GpuDriverAdvisor.applyRecommendedDriver(inst);
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (!r.applied) { Navigation.findNavController(requireView()).popBackStack(); return; }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.driver_auto_set_title)
                        .setMessage(getString(R.string.driver_auto_set, r.gpuName, r.driverLabel))
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> Navigation.findNavController(requireView()).popBackStack())
                        .show();
            });
        }, "rd-gpu-advise").start();
    }

    /** "Valheim", or "Valheim-2"/"-3"/... — the first name with no existing instance directory.
     *  Lives in AppStorage so the Steam download screen pre-fills the same default. */
    private static String freeDefaultName() {
        return com.valdroid.AppStorage.freeDefaultInstanceName();
    }

    private void startInstall() {
        if (selectedZipUri == null) return;
        String rawName = etInstanceName.getText().toString().trim();
        // Instance name = directory name = part of every game path. RimWorld 1.6 loads mod audio
        // via UnityWebRequest/curl "file://" URLs WITHOUT escaping, so a space (or other URL-hostile
        // char) in the path silently kills all mod sounds ("Curl error 3: URL rejected", found
        // 2026-07-11). Sanitize to a URL/path-safe name up front.
        final String instanceName = rawName.replaceAll("[^A-Za-z0-9._-]+", "-")
                                           .replaceAll("^-+|-+$", "");
        if (instanceName.isEmpty()) {
            etInstanceName.setError(getString(R.string.error_name_required));
            return;
        }
        // Cap the length so the X-socket path can't overflow sun_path (see MAX_NAME_LEN).
        if (instanceName.length() > MAX_NAME_LEN) {
            etInstanceName.setError(getString(R.string.error_name_too_long, MAX_NAME_LEN));
            return;
        }
        // Refuse a name whose instance directory already exists, so we never install over (or beside)
        // an existing instance. InstallerService double-checks, but catching it here gives a clear
        // field error instead of a late broadcast failure.
        if (com.valdroid.AppStorage.requireSingleton().getInstanceDir(instanceName).exists()) {
            etInstanceName.setError(getString(R.string.error_name_exists));
            return;
        }

        lastInstanceName = instanceName;
        btnInstall.setEnabled(false);
        btnInstall.setText(R.string.installing);

        // Grab the context HERE, on the UI thread: copying the zip takes long enough for the user to
        // leave the screen, and requireContext()/requireActivity() from the worker then throw
        // IllegalStateException ("Fragment not attached") — an uncaught crash on a background thread,
        // seen in a tester's log. The application context outlives the fragment; UI touches go
        // through mainHandler behind an isAdded() check.
        final android.content.Context appCtx = requireContext().getApplicationContext();
        final String[] extras = extraInstallers.toArray(new String[0]);
        final Uri zipUri = selectedZipUri;
        showProgress(PHASE_COPY, R.string.install_phase_copy, 0, -1);   // up at once, size follows
        new Thread(() -> {
            try {
                // The archive's size from its provider, for a real percentage; -1 if it won't say.
                long size = -1;
                try (android.database.Cursor c = appCtx.getContentResolver().query(zipUri,
                        new String[]{ android.provider.OpenableColumns.SIZE }, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
                } catch (Exception ignored) {}
                final long total = size;

                File cacheZip = new File(appCtx.getCacheDir(), "instance.zip");
                try (InputStream in = appCtx.getContentResolver().openInputStream(zipUri);
                     FileOutputStream out = new FileOutputStream(cacheZip)) {
                    byte[] buf = new byte[65536];
                    int len;
                    long done = 0, lastSentMs = 0;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                        done += len;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastSentMs >= 250) {
                            lastSentMs = now;
                            final long d = done;
                            mainHandler.post(() -> showProgress(PHASE_COPY, R.string.install_phase_copy, d, total));
                        }
                    }
                }
                InstallerService.startInstallInstance(
                        appCtx, cacheZip.getAbsolutePath(), instanceName, extras);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                    showProgressError(e.getMessage());
                });
            }
        }).start();
    }
}
