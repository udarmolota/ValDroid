package com.valdroid.fragments;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.GameDataTransfer;
import com.valdroid.R;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Import and export of an instance's saves or its game settings (argument {@link #ARG_KIND}), one screen
 * from the drawer each, laid out like ControlsTransferFragment: the instance, an import card, an export
 * card. The zip format is GameDataTransfer's. Importing overwrites files of the same names in the
 * instance, so it is confirmed first.
 */
public class DataTransferFragment extends Fragment {

    public static final String ARG_KIND = "kind";
    public static final String KIND_SAVES = "saves";
    public static final String KIND_SETTINGS = "settings";

    private String kind;
    private Spinner spInstance;
    private final List<GameInstance> instances = new ArrayList<>();
    private GameInstance exportFor;   // the instance an export in flight belongs to

    private final ActivityResultLauncher<String[]> pickImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::confirmImport);

    private final ActivityResultLauncher<String> pickExport =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::export);

    private boolean saves() {
        return !KIND_SETTINGS.equals(kind);
    }

    private String[] parts() {
        return new String[]{ saves() ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG };
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_data_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        kind = getArguments() != null ? getArguments().getString(ARG_KIND, KIND_SAVES) : KIND_SAVES;
        ((TextView) v.findViewById(R.id.tv_transfer_import_title)).setText(
                saves() ? R.string.saves_transfer_import_title : R.string.settings_transfer_import_title);
        ((TextView) v.findViewById(R.id.tv_transfer_import_hint)).setText(
                saves() ? R.string.saves_transfer_import_hint : R.string.settings_transfer_import_hint);
        ((TextView) v.findViewById(R.id.tv_transfer_export_title)).setText(
                saves() ? R.string.saves_transfer_export_title : R.string.settings_transfer_export_title);
        ((TextView) v.findViewById(R.id.tv_transfer_export_hint)).setText(
                saves() ? R.string.saves_transfer_export_hint : R.string.settings_transfer_export_hint);

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

        v.findViewById(R.id.btn_transfer_import).setOnClickListener(b -> {
            if (current() == null) return;
            pickImport.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        });
        v.findViewById(R.id.btn_transfer_export).setOnClickListener(b -> {
            GameInstance gi = current();
            if (gi == null) return;
            exportFor = gi;
            pickExport.launch("valdroid_" + kind + "_" + gi.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date())
                    + ".zip");
        });
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    /** Importing overwrites files of the same names: asked first, a stray tap must not do it. */
    private void confirmImport(@Nullable Uri uri) {
        GameInstance gi = current();
        if (uri == null || gi == null || !isAdded()) return;
        String file = displayName(uri);
        if (file == null) file = uri.getLastPathSegment();
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(saves() ? R.string.saves_transfer_import_confirm : R.string.settings_transfer_import_confirm,
                        file, gi.getName()))
                .setPositiveButton(R.string.controls_transfer_import, (d, w) -> importData(uri, gi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importData(Uri uri, GameInstance gi) {
        final android.content.Context app = requireContext().getApplicationContext();
        final File instanceDir = new File(gi.getGamePath());
        final String[] parts = parts();
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
                GameDataTransfer.Result r = GameDataTransfer.importZip(cacheZip, instanceDir, parts);
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

    private void export(@Nullable Uri uri) {
        final GameInstance gi = exportFor;
        if (uri == null || gi == null || !isAdded()) return;
        final android.content.Context app = requireContext().getApplicationContext();
        final File instanceDir = new File(gi.getGamePath());
        final String[] parts = parts();
        new Thread(() -> {
            String msg;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                GameDataTransfer.Result r = GameDataTransfer.export(instanceDir, out, parts);
                msg = r.ok() ? app.getString(R.string.transfer_exported, android.text.TextUtils.join(" + ", r.items),
                        (int) (r.bytes / 1024))
                        : app.getString(R.string.transfer_export_failed, r.error);
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_export_failed, String.valueOf(e.getMessage()));
            }
            toastLater(msg);
        }, "data-export").start();
    }

    private void toastLater(String msg) {
        android.app.Activity a = getActivity();
        if (a != null) a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }

    @Nullable
    private String displayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
