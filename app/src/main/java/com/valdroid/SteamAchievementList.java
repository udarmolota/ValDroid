package com.valdroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "My achievements" on the Multiplayer screen, without signing in: the list the last sync saved
 * (SteamStatsSync: every achievement, display names, unlocked since when) plus the ones the game
 * marked on the phone since then (the stub's file), which go out at the next sign-in.
 */
public final class SteamAchievementList {
    /** Steam's language keys for the app's languages; English is the fallback. */
    static final String[] LANGUAGES = { "english", "russian", "spanish", "latam", "brazilian", "portuguese" };

    public static final class Entry {
        public final String id, name;
        public final boolean unlocked, pending;
        public final long time;   // unlock time, Unix seconds; 0 when locked or pending
        Entry(String id, String name, boolean unlocked, boolean pending, long time) {
            this.id = id; this.name = name; this.unlocked = unlocked; this.pending = pending; this.time = time;
        }
    }

    public final List<Entry> entries = new ArrayList<>();
    public long synced;   // Unix seconds of the last sync; 0 = never synced
    public int unlocked, pending;

    private SteamAchievementList() {}

    /** Next to the stub's file. */
    public static File cacheFile(File statsFile) {
        return new File(statsFile.getParentFile(), "steam_achievements.json");
    }

    public static SteamAchievementList load(File statsFile) {
        SteamAchievementList l = new SteamAchievementList();
        Set<String> onPhone = readPhone(statsFile);
        Set<String> known = new LinkedHashSet<>();
        try {
            JSONObject root = new JSONObject(readAll(cacheFile(statsFile)));
            l.synced = root.optLong("synced");
            JSONArray list = root.getJSONArray("achievements");
            String lang = language();
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                String id = a.getString("id");
                JSONObject names = a.optJSONObject("names");
                String name = names == null ? id : names.optString(lang, names.optString("english", id));
                boolean unlocked = a.optBoolean("unlocked");
                boolean pending = !unlocked && onPhone.contains(id);
                l.entries.add(new Entry(id, name, unlocked, pending, a.optLong("time")));
                known.add(id);
            }
        } catch (Exception ignored) {
            // Never synced with the list (or unreadable): the phone's file alone cannot tell what Steam
            // already has from what is new, so nothing is shown rather than a wrong "waiting" count.
            return l;
        }
        for (String id : onPhone)
            if (!known.contains(id)) l.entries.add(new Entry(id, id, false, true, 0));
        for (Entry e : l.entries) {
            if (e.unlocked) l.unlocked++;
            if (e.pending) l.pending++;
        }
        return l;
    }

    /** The app's language as Steam names it. */
    private static String language() {
        switch (Locale.getDefault().getLanguage()) {
            case "ru": return "russian";
            case "es": return "spanish";
            case "pt": return "brazilian";
            default: return "english";
        }
    }

    private static Set<String> readPhone(File statsFile) {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (String line : readAll(statsFile).split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 2 && p[0].equals("ach")) out.add(p[1]);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
