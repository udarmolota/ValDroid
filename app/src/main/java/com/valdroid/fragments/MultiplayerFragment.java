package com.valdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.valdroid.LauncherPreferences;
import com.valdroid.R;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiplayer for one instance, from the drawer: the instance is picked at the top (the last one
 * played to start with), Crossplay and Steam achievements switched on or off for it. With either on,
 * every native launch signs in to Steam first: crossplay hands the game a ticket for its PlayFab
 * login, achievements sends the new ones to the account (SteamTicketLogin, SteamStatsSync). Both need
 * the native engine; for another engine the switches are greyed out with a note.
 */
public class MultiplayerFragment extends Fragment {
    private Spinner spInstance;
    private Switch swCrossplay, swAchievements;
    private View tvNativeOnly;
    private android.widget.TextView tvAchievements;
    private android.widget.Button btnMyAchievements;
    private final List<GameInstance> instances = new ArrayList<>();
    private boolean binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_multiplayer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_mp_instance);
        swCrossplay = v.findViewById(R.id.sw_crossplay);
        swAchievements = v.findViewById(R.id.sw_steam_achievements);
        tvNativeOnly = v.findViewById(R.id.tv_mp_native_only);
        tvAchievements = v.findViewById(R.id.tv_achievements_summary);
        btnMyAchievements = v.findViewById(R.id.btn_my_achievements);
        btnMyAchievements.setOnClickListener(x -> showAchievements());

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        String last = LauncherPreferences.requireSingleton().getLastInstanceName();
        int selected = 0;
        for (int i = 0; i < instances.size(); i++) {
            names.add(instances.get(i).getName());
            if (instances.get(i).getName().equals(last)) selected = i;
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        spInstance.setSelection(selected);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        swCrossplay.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            GameInstance gi = current();
            if (gi != null) gi.settings().setCrossplay(on);
        });
        swAchievements.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            GameInstance gi = current();
            if (gi != null) gi.settings().setSteamAchievements(on);
        });
        refresh();
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    private void refresh() {
        GameInstance gi = current();
        boolean nativeEngine = gi != null && com.valdroid.game.NativeEngine.isAvailable() && gi.settings().isNativeEngine();
        binding = true;
        swCrossplay.setChecked(gi != null && gi.settings().isCrossplay());
        swCrossplay.setEnabled(nativeEngine);
        swAchievements.setChecked(gi != null && gi.settings().isSteamAchievements());
        swAchievements.setEnabled(nativeEngine);
        binding = false;
        tvNativeOnly.setVisibility(gi != null && !nativeEngine ? View.VISIBLE : View.GONE);
        fillAchievements(gi);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (spInstance != null) fillAchievements(current());   // a game may have marked some meanwhile
    }

    // ---- My achievements: the last sign-in's list plus what waits on the phone ----

    private void fillAchievements(@Nullable GameInstance gi) {
        if (gi == null) {
            tvAchievements.setText("");
            btnMyAchievements.setVisibility(View.GONE);
            return;
        }
        com.valdroid.SteamAchievementList l =
                com.valdroid.SteamAchievementList.load(com.valdroid.game.NativeEngine.steamStatsFile(gi));
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (l.synced > 0) {
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(new java.util.Date(l.synced * 1000));
            sb.append("\uD83C\uDFC6  ").append(getString(R.string.steam_achievements_summary, l.unlocked, l.entries.size()));
            if (l.pending > 0) sb.append('\n').append(getString(R.string.steam_achievements_pending, l.pending));
            int start = sb.length();
            sb.append('\n').append(getString(R.string.steam_achievements_updated, when));
            // The update time a size smaller and dimmer than the count (14sp text, 12sp here).
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(12, true), start, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(tvAchievements.getCurrentTextColor() & 0xB3FFFFFF),
                    start, sb.length(), 0);
        } else {
            sb.append(getString(R.string.steam_achievements_never));
        }
        tvAchievements.setText(sb);
        btnMyAchievements.setVisibility(l.entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Unlocked first (newest first), then the ones waiting to be sent, then the locked ones. */
    private void showAchievements() {
        GameInstance gi = current();
        if (gi == null) return;
        com.valdroid.SteamAchievementList l =
                com.valdroid.SteamAchievementList.load(com.valdroid.game.NativeEngine.steamStatsFile(gi));
        List<com.valdroid.SteamAchievementList.Entry> sorted = new ArrayList<>(l.entries);
        java.util.Collections.sort(sorted, (a, b) -> {
            int ra = a.unlocked ? 0 : a.pending ? 1 : 2, rb = b.unlocked ? 0 : b.pending ? 1 : 2;
            if (ra != rb) return ra - rb;
            return Long.compare(b.time, a.time);
        });
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        CharSequence[] rows = new CharSequence[sorted.size()];
        for (int i = 0; i < rows.length; i++) {
            com.valdroid.SteamAchievementList.Entry e = sorted.get(i);
            rows[i] = e.unlocked ? "✅ " + e.name + (e.time > 0 ? "  ·  " + df.format(new java.util.Date(e.time * 1000)) : "")
                    : e.pending ? "⏳ " + e.name + "  ·  " + getString(R.string.steam_achievements_waiting)
                    : "○ " + e.name;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.steam_achievements_title) + "  " + l.unlocked + " / " + l.entries.size())
                .setItems(rows, null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
