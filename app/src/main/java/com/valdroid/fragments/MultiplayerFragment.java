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
 * played to start with) and Crossplay switched on or off for it. With it on, every native launch
 * signs in to Steam first and hands the game a ticket for its PlayFab login (SteamTicketLogin).
 * Crossplay needs the native engine; for another engine the switch is greyed out with a note.
 */
public class MultiplayerFragment extends Fragment {
    private Spinner spInstance;
    private Switch swCrossplay;
    private View tvNativeOnly;
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
        tvNativeOnly = v.findViewById(R.id.tv_mp_native_only);

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
        binding = false;
        tvNativeOnly.setVisibility(gi != null && !nativeEngine ? View.VISIBLE : View.GONE);
    }
}
