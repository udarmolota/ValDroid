package com.valdroid.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.GameDataTransfer;
import com.valdroid.LauncherPreferences;
import com.valdroid.ModManager;
import com.valdroid.R;
import com.valdroid.controls.ControlsStorage;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Every import and export in one screen (as in Zomdroid and RimDroid, 2026-11). It used to be three
 * drawer screens (saves, game settings, controls), each with its own instance picker. Now the
 * instance is picked once at the top and each card has an Import and an Export button; Import picks
 * the file and asks once before it overwrites anything. The formats are unchanged: saves and settings
 * are GameDataTransfer's zips, controls ControlsStorage's zip (layout and icons; a bare .json is taken
 * too), mods any zip ModManager installs (an exported one holds every mod of the instance and installs
 * whole). The custom Vulkan driver and the icon packs are for the whole device and keep their own
 * screens; the last card leads there.
 */
public class TransferFragment extends Fragment {

    private static final int NONE = 0, SAVES = 1, MODS = 2, SETTINGS = 3, CONTROLS = 4;
    private static final String STATE_PENDING = "pending";
    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};

    private Spinner spInstance;
    private final List<GameInstance> instances = new ArrayList<>();
    /** What the open system picker is for; kept across a recreation of this screen. */
    private int pending = NONE;
    /** The instance an export in flight belongs to. */
    private GameInstance exportFor;

    private final ActivityResultLauncher<String[]> pickImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::confirmImport);
    private final ActivityResultLauncher<String[]> pickMods =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::importMods);
    private final ActivityResultLauncher<String> exportZip =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::export);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        if (savedInstanceState != null) pending = savedInstanceState.getInt(STATE_PENDING, NONE);

        spInstance = v.findViewById(R.id.sp_transfer_instance);
        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        // The instance played last is the likely one; the name stays in view at the top of the screen.
        String last = LauncherPreferences.requireSingleton().getLastInstanceName();
        for (int i = 0; i < instances.size(); i++)
            if (instances.get(i).getName().equals(last)) spInstance.setSelection(i);

        v.findViewById(R.id.btn_transfer_saves_import).setOnClickListener(b -> startImport(SAVES));
        v.findViewById(R.id.btn_transfer_saves_export).setOnClickListener(b -> startExport(SAVES));
        v.findViewById(R.id.btn_transfer_mods_import).setOnClickListener(b -> startImport(MODS));
        v.findViewById(R.id.btn_transfer_mods_export).setOnClickListener(b -> startExport(MODS));
        v.findViewById(R.id.btn_transfer_settings_import).setOnClickListener(b -> startImport(SETTINGS));
        v.findViewById(R.id.btn_transfer_settings_export).setOnClickListener(b -> startExport(SETTINGS));
        v.findViewById(R.id.btn_transfer_controls_import).setOnClickListener(b -> startImport(CONTROLS));
        v.findViewById(R.id.btn_transfer_controls_export).setOnClickListener(b -> startExport(CONTROLS));
        v.findViewById(R.id.btn_transfer_driver).setOnClickListener(b ->
                Navigation.findNavController(v).navigate(R.id.action_open_custom_driver));
        v.findViewById(R.id.btn_transfer_icon_packs).setOnClickListener(b ->
                Navigation.findNavController(v).navigate(R.id.action_open_icon_packs));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING, pending);
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    @Nullable
    private GameInstance currentOrToast() {
        GameInstance gi = current();
        if (gi == null) Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_SHORT).show();
        return gi;
    }

    // ---- import ----

    private void startImport(int what) {
        if (currentOrToast() == null) return;
        pending = what;
        if (what == MODS) pickMods.launch(ZIP_TYPES);
        else if (what == CONTROLS) pickImport.launch(new String[]{"application/zip", "application/x-zip-compressed",
                "application/json", "text/plain", "application/octet-stream"});
        else pickImport.launch(ZIP_TYPES);
    }

    /** Importing overwrites what the instance has: asked once, a stray tap must not do it. */
    private void confirmImport(@Nullable Uri uri) {
        GameInstance gi = current();
        int what = pending;
        pending = NONE;
        if (uri == null || gi == null || !isAdded()) return;
        String file = displayName(uri);
        if (file == null) file = uri.getLastPathSegment();
        String text = what == CONTROLS ? getString(R.string.controls_transfer_import_confirm, gi.getName(), file)
                : getString(what == SAVES ? R.string.saves_transfer_import_confirm
                        : R.string.settings_transfer_import_confirm, file, gi.getName());
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(text)
                .setPositiveButton(R.string.transfer_import, (d, w) -> {
                    if (what == CONTROLS) importControls(uri, gi.getName());
                    else importData(uri, gi, what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Mods add to the instance (a mod already there is updated, as with Install on the Mods screen),
     * so no question first: the result says what was installed.
     */
    private void importMods(@Nullable List<Uri> uris) {
        GameInstance gi = current();
        pending = NONE;
        if (uris == null || uris.isEmpty() || gi == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        final File gameDir = new File(gi.getGamePath());
        final boolean supportOn = gi.settings().isModSupport();
        Toast.makeText(app, R.string.transfer_mods_installing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String> done = new ArrayList<>(), failed = new ArrayList<>();
            for (Uri uri : uris) {
                String file = displayName(app, uri);
                if (file == null) file = String.valueOf(uri.getLastPathSegment());
                String base = file.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                        ? file.substring(0, file.length() - 4) : file;
                try {
                    done.add(ModManager.install(app, uri, gameDir, base));
                } catch (Exception e) {
                    failed.add(file + ": " + e.getMessage());
                }
            }
            StringBuilder msg = new StringBuilder(app.getString(R.string.transfer_mods_imported, done.size()));
            for (String n : done) msg.append("\n• ").append(n);
            if (!failed.isEmpty()) {
                msg.append("\n\n").append(app.getString(R.string.mods_copy_failed));
                for (String f : failed) msg.append("\n• ").append(f);
            }
            if (!done.isEmpty() && !supportOn) msg.append("\n\n").append(app.getString(R.string.ts_mods_off));
            android.app.Activity act = getActivity();
            if (act != null) act.runOnUiThread(() -> {
                if (!isAdded()) {
                    Toast.makeText(app, app.getString(R.string.transfer_mods_imported, done.size()), Toast.LENGTH_LONG).show();
                    return;
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(gi.getName())
                        .setMessage(msg.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "mods-import").start();
    }

    private void importData(Uri uri, GameInstance gi, String part) {
        final Context app = requireContext().getApplicationContext();
        final File instanceDir = new File(gi.getGamePath());
        new Thread(() -> {
            String msg;
            File cacheZip = new File(app.getCacheDir(), "import_data.zip");
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheZip)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                GameDataTransfer.Result r = GameDataTransfer.importZip(cacheZip, instanceDir, new String[]{part});
                msg = r.ok() ? app.getString(R.string.transfer_imported, android.text.TextUtils.join(" + ", r.items), gi.getName())
                        : app.getString(R.string.transfer_import_failed, r.error);
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_import_failed, String.valueOf(e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                cacheZip.delete();
            }
            toastLater(msg);
        }, "data-import").start();
    }

    private void importControls(Uri uri, String instance) {
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                ControlsStorage.importLayout(in, instance);
                msg = app.getString(R.string.controls_imported, instance);
            } catch (Exception e) {
                msg = app.getString(R.string.controls_import_failed,
                        e.getMessage() == null ? "invalid layout file" : e.getMessage());
            }
            toastLater(msg);
        }, "controls-import").start();
    }

    // ---- export ----

    private void startExport(int what) {
        GameInstance gi = currentOrToast();
        if (gi == null) return;
        if (what == MODS && ModManager.list(new File(gi.getGamePath())).isEmpty()) {
            Toast.makeText(requireContext(), R.string.transfer_mods_none, Toast.LENGTH_SHORT).show();
            return;
        }
        exportFor = gi;
        pending = what;
        String kind = what == SAVES ? "saves" : what == MODS ? "mods" : what == SETTINGS ? "settings" : "controls";
        exportZip.launch("valdroid_" + kind + "_" + gi.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date())
                + ".zip");
    }

    private void export(@Nullable Uri uri) {
        final GameInstance gi = exportFor;
        final int what = pending;
        pending = NONE;
        if (uri == null || gi == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        final File instanceDir = new File(gi.getGamePath());
        new Thread(() -> {
            String msg;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                if (what == CONTROLS) {
                    ControlsStorage.exportZip(app, gi.getName(), out);
                    msg = app.getString(R.string.controls_exported);
                } else if (what == MODS) {
                    int count = ModManager.exportZip(instanceDir, ModManager.list(instanceDir), out);
                    msg = app.getString(R.string.mods_exported, count);
                } else {
                    String part = what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG;
                    GameDataTransfer.Result r = GameDataTransfer.export(instanceDir, out, new String[]{part});
                    msg = r.ok() ? app.getString(R.string.transfer_exported, android.text.TextUtils.join(" + ", r.items),
                            (int) (r.bytes / 1024))
                            : app.getString(R.string.transfer_export_failed, r.error);
                }
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_export_failed, String.valueOf(e.getMessage()));
            }
            toastLater(msg);
        }, "transfer-export").start();
    }

    // ---- helpers ----

    private void toastLater(String msg) {
        android.app.Activity a = getActivity();
        if (a != null) a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }

    @Nullable
    private String displayName(Uri uri) {
        return displayName(requireContext(), uri);
    }

    @Nullable
    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
