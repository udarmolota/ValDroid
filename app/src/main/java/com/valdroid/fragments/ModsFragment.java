package com.valdroid.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.valdroid.ModManager;
import com.valdroid.R;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Mods (BepInEx) for one instance, from the drawer. The instance is picked at the top (preselected
 * when there is only one); below it the master switch "Mod support (BepInEx)", which decides whether
 * BepInEx is started at launch, and the install button. The second card lists the instance's mods,
 * each with its own switch; with mod support off the list is shown greyed out, since nothing in it
 * loads. A long press deletes a mod.
 */
public class ModsFragment extends Fragment {

    private Spinner spInstance;
    private Switch swSupport;
    private TextView tvNativeWarning, tvOffNote, tvEmpty;
    private LinearLayout llList;
    private Button btnPick, btnInstall;
    private final List<GameInstance> instances = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean binding;   // true while the UI is being filled from state, so listeners stay quiet
    private boolean installing;
    /** The zip picked in the file field; installed only when the Install button is pressed. */
    @Nullable private Uri pickedZip;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;   // cancelled: keep what was picked before
                pickedZip = uri;
                btnPick.setText(displayName(uri) + ".zip");
                updateInstallEnabled();
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_mods_instance);
        swSupport = v.findViewById(R.id.sw_mod_support);
        tvNativeWarning = v.findViewById(R.id.tv_mods_native_mono_warning);
        tvOffNote = v.findViewById(R.id.tv_mods_off_note);
        tvEmpty = v.findViewById(R.id.tv_mods_empty);
        llList = v.findViewById(R.id.ll_mods_list);
        btnPick = v.findViewById(R.id.btn_mods_pick);
        btnInstall = v.findViewById(R.id.btn_mods_install);

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        swSupport.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            GameInstance gi = current();
            if (gi == null) return;
            gi.settings().setModSupport(on);
            refresh();
        });
        btnPick.setOnClickListener(x -> {
            if (current() != null) picker.launch(new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        });
        btnInstall.setOnClickListener(x -> installZip(pickedZip));
        refresh();
    }

    private void updateInstallEnabled() {
        btnInstall.setEnabled(current() != null && pickedZip != null && !installing);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // files may have changed by hand in the meantime
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    /** Fills the switch, the warning and the list from the selected instance's settings and files. */
    private void refresh() {
        if (llList == null) return;
        GameInstance gi = current();
        boolean has = gi != null;
        boolean on = has && gi.settings().isModSupport();
        binding = true;
        swSupport.setEnabled(has);
        swSupport.setChecked(on);
        binding = false;
        btnPick.setEnabled(has && !installing);
        updateInstallEnabled();
        tvNativeWarning.setVisibility(on && !gi.settings().isNativeMono() ? View.VISIBLE : View.GONE);

        llList.removeAllViews();
        List<ModManager.Mod> mods = has ? ModManager.list(new File(gi.getGamePath())) : new ArrayList<>();
        tvEmpty.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);
        tvOffNote.setVisibility(has && !on && !mods.isEmpty() ? View.VISIBLE : View.GONE);
        for (ModManager.Mod m : mods) llList.addView(row(gi, m, on));
    }

    private View row(GameInstance gi, ModManager.Mod m, boolean supportOn) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView title = new TextView(requireContext());
        title.setText(m.version != null ? m.name + "  " + m.version : m.name);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(requireContext());
        sw.setChecked(m.enabled);
        sw.setEnabled(supportOn);
        sw.setOnCheckedChangeListener((b, enabled) -> {
            if (!ModManager.setEnabled(new File(gi.getGamePath()), m, enabled))
                Toast.makeText(requireContext(), R.string.mod_switch_failed, Toast.LENGTH_SHORT).show();
            refresh();
        });
        row.addView(sw);

        // Greyed while mod support is off: nothing in the list loads then.
        row.setAlpha(supportOn ? 1f : 0.45f);
        row.setOnLongClickListener(x -> { confirmDelete(gi, m); return true; });
        return row;
    }

    private void confirmDelete(GameInstance gi, ModManager.Mod m) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.mod_delete_title, m.name))
                .setMessage(R.string.mod_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mod_delete_confirm, (d, w) -> {
                    ModManager.delete(new File(gi.getGamePath()), m);
                    refresh();
                })
                .show();
    }

    private void installZip(@Nullable Uri uri) {
        GameInstance gi = current();
        if (uri == null || gi == null) return;
        android.content.Context app = requireContext().getApplicationContext();
        String fallback = displayName(uri);
        installing = true;
        btnPick.setEnabled(false);
        updateInstallEnabled();
        new Thread(() -> {
            String result, error = null;
            try {
                result = ModManager.install(app, uri, new File(gi.getGamePath()), fallback);
            } catch (Exception e) {
                result = null;
                error = e.getMessage();
            }
            final String name = result, err = error;
            main.post(() -> {
                installing = false;
                if (!isAdded()) return;
                if (name != null) {
                    // Done: clear the field, so a second tap on Install cannot repeat it by accident.
                    pickedZip = null;
                    btnPick.setText(R.string.mods_pick_zip);
                    Toast.makeText(requireContext(), getString(R.string.mod_installed, name), Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.mod_install_failed)
                            .setMessage(err != null ? err : "")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                refresh();
            });
        }, "ModInstall").start();
    }

    /** The picked file's name without ".zip", used when the archive has no manifest. */
    private String displayName(Uri uri) {
        String n = null;
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {}
        if (n == null || n.isEmpty()) n = uri.getLastPathSegment();
        if (n == null) n = "mod";
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }
}
