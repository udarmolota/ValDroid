package com.valdroid.fragments;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.R;
import com.valdroid.controls.IconPacks;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Icon packs for the on-screen buttons, from the drawer: the packs (built in and imported), each with a
 * few of its pictures and an Apply button that puts it on the chosen instance's layout, and the import
 * of a pack zip. The format is described in {@link IconPacks}.
 */
public class IconPacksFragment extends Fragment {

    private static final String ACTION_NAMES = "attack, secondary_attack, block, dodge, jump, crouch, run, "
            + "auto_run, use, sit, hide_weapons, forsaken_power, inventory, map, menu, chat, keyboard, toggle_controls";
    private static final int PREVIEW_ICONS = 6;

    private Spinner spInstance;
    private LinearLayout packList;
    private final List<GameInstance> instances = new ArrayList<>();

    private final ActivityResultLauncher<String[]> pickPack =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPackPicked);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_icon_packs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_icon_packs_instance);
        packList = v.findViewById(R.id.ll_icon_packs);
        final TextView howto = v.findViewById(R.id.tv_icon_packs_howto);
        final TextView format = v.findViewById(R.id.tv_icon_packs_format);
        format.setText(getString(R.string.icon_packs_format, ACTION_NAMES));
        howto.setText(getString(R.string.icon_packs_howto) + " \u25B8");
        howto.setOnClickListener(b -> {
            boolean open = format.getVisibility() != View.VISIBLE;
            format.setVisibility(open ? View.VISIBLE : View.GONE);
            howto.setText(getString(R.string.icon_packs_howto) + (open ? " \u25BE" : " \u25B8"));
        });
        v.findViewById(R.id.btn_icon_packs_import).setOnClickListener(b ->
                pickPack.launch(new String[]{"application/zip", "application/octet-stream", "*/*"}));

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        refreshPacks();
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    /** One row per pack: name, count, a few pictures on dark tiles (they are white), Apply, Delete. */
    private void refreshPacks() {
        if (packList == null || !isAdded()) return;
        packList.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int tile = Math.round(36 * density), pad = Math.round(5 * density), gap = Math.round(4 * density);
        for (IconPacks.Pack pack : IconPacks.list(requireContext())) {
            View row = getLayoutInflater().inflate(R.layout.item_icon_pack, packList, false);
            ((TextView) row.findViewById(R.id.tv_icon_pack_name)).setText(pack.name);
            String count = getString(R.string.icon_packs_count, pack.icons.size());
            ((TextView) row.findViewById(R.id.tv_icon_pack_info))
                    .setText(pack.builtIn ? count + " · " + getString(R.string.icon_packs_builtin) : count);
            LinearLayout preview = row.findViewById(R.id.ll_icon_pack_preview);
            for (int i = 0; i < pack.icons.size() && i < PREVIEW_ICONS; i++) {
                Bitmap bmp = IconPacks.load(requireContext(), pack, pack.icons.get(i));
                if (bmp == null) continue;
                ImageView iv = new ImageView(requireContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
                lp.setMarginEnd(gap);
                iv.setLayoutParams(lp);
                iv.setPadding(pad, pad, pad, pad);
                iv.setBackgroundColor(0xFF37474F);
                iv.setImageBitmap(bmp);
                preview.addView(iv);
            }
            row.findViewById(R.id.btn_icon_pack_apply).setOnClickListener(b -> confirmApply(pack));
            Button delete = row.findViewById(R.id.btn_icon_pack_delete);
            delete.setVisibility(pack.builtIn ? View.GONE : View.VISIBLE);
            delete.setOnClickListener(b -> new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.icon_packs_delete_confirm, pack.name))
                    .setPositiveButton(R.string.icon_packs_delete, (d, w) -> {
                        IconPacks.delete(pack);
                        refreshPacks();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            packList.addView(row);
        }
    }

    /** Applying rewrites the layout's icons: asked first, a stray tap must not do it. */
    private void confirmApply(IconPacks.Pack pack) {
        GameInstance gi = current();
        if (gi == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.icon_packs_apply_confirm, pack.name, gi.getName()))
                .setPositiveButton(R.string.icon_packs_apply, (d, w) -> apply(pack, gi))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void apply(IconPacks.Pack pack, GameInstance gi) {
        int n = IconPacks.applyToInstance(requireContext(), pack, gi.getName());
        if (n > 0) toast(getString(R.string.icon_packs_applied, n, gi.getName()));
        else toast(getString(n == 0 ? R.string.icon_packs_applied_none : R.string.icon_packs_apply_failed));
    }

    private void onPackPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String name = queryDisplayName(uri);
        new Thread(() -> {
            String msg;
            try {
                IconPacks.Pack p = IconPacks.importZip(requireContext(), uri, name);
                msg = getString(R.string.icon_packs_imported, p.name, p.icons.size());
            } catch (Exception e) {
                msg = getString(R.string.icon_packs_import_failed, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String text = msg;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> { toast(text); refreshPacks(); });
        }, "icon-pack-import").start();
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s == null ? null : s.substring(s.lastIndexOf('/') + 1);
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
