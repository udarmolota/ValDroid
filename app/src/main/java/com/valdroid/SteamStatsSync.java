package com.valdroid;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.base.ClientMsgProtobuf;
import in.dragonbra.javasteam.base.IPacketMsg;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientGamesPlayed;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStats2;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse;
import in.dragonbra.javasteam.steam.handlers.ClientMsgHandler;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.Stats;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.types.KeyValue;

/**
 * Valheim's Steam achievements and stats from the native engine, sent to the account and brought
 * back. In the game the libsteam_api stub keeps them in a text file (VALDROID_STEAM_STATS: "stat NAME
 * VALUE BASE", "ach NAME"; BASE = Steam's value at the last sync). With a signed-in client this reads
 * the account's stats and the game's schema (achievements are bits of "ACHIEVEMENTS" stats: Valheim
 * has blocks 16 and 17), merges and stores:
 * <ul>
 *   <li>achievements: the account's set plus the phone's, never fewer;</li>
 *   <li>counters: Steam's value plus what the phone added since the last sync (VALUE - BASE); "Max…"
 *       stats are records, so the larger of the two;</li>
 *   <li>nothing is ever lowered or reset.</li>
 * </ul>
 * The store goes out while Steam sees the game as running (a few seconds), then the file is
 * rewritten from the result, so the game starts from the account's values. Must run while the game
 * is not running: the stub owns the file then. Nothing personal is logged, only counts.
 */
public final class SteamStatsSync {
    private static final String TAG = "ValDroid/Achievements";
    private static final int VALHEIM = 892970;

    private SteamStatsSync() {}

    /** One stat from the schema. */
    private static final class StatDef {
        final int id;
        final String name;
        final boolean isFloat;
        StatDef(int id, String name, boolean isFloat) { this.id = id; this.name = name; this.isFloat = isFloat; }
    }

    /** Achievement block id and bit, from the schema. */
    private static final class Bit {
        final int block, bit;
        Bit(int block, int bit) { this.block = block; this.bit = bit; }
    }

    /** The phone's side, as the stub left it. */
    private static final class Local {
        final Map<String, double[]> stats = new LinkedHashMap<>();   // name -> {value, base}
        final Set<String> achievements = new LinkedHashSet<>();
    }

    /** The store's answer; one store at a time, so the message type is enough to match it. */
    private static final class StoreHandler extends ClientMsgHandler {
        volatile CompletableFuture<CMsgClientStoreUserStatsResponse> pending;

        @Override public void handleMsg(IPacketMsg packet) {
            if (packet.getMsgType() != EMsg.ClientStoreUserStatsResponse) return;
            CompletableFuture<CMsgClientStoreUserStatsResponse> f = pending;
            if (f == null) return;
            ClientMsgProtobuf<CMsgClientStoreUserStatsResponse.Builder> msg =
                    new ClientMsgProtobuf<>(CMsgClientStoreUserStatsResponse.class, packet);
            f.complete(msg.getBody().build());
        }
    }

    /** Adds the store handler; call before the client connects. */
    public static void prepare(SteamClient client) {
        if (client.getHandler(StoreHandler.class) == null) client.addHandler(new StoreHandler());
        if (client.getHandler(SteamUserStats.class) == null) client.addHandler(new SteamUserStats());
    }

    /**
     * Merges the phone's file with the signed-in account and stores the result; returns how many
     * achievements were new for the account. Throws on anything Steam refuses: the file is then left
     * as it was.
     */
    public static int sync(SteamClient client, File file) throws Exception {
        UserStatsCallback r = client.getHandler(SteamUserStats.class)
                .getUserStats(VALHEIM, client.getSteamID()).toFuture().get(30, TimeUnit.SECONDS);
        if (r.getResult() != EResult.OK) throw new IOException("Steam stats: " + r.getResult());

        // Schema: stats by name, achievements by name -> (block, bit).
        Map<String, StatDef> defs = new LinkedHashMap<>();
        Map<String, Bit> bits = new LinkedHashMap<>();
        Map<Integer, String> bitNames = new LinkedHashMap<>();   // block << 8 | bit -> name
        for (KeyValue st : r.getSchemaKeyValues().get("stats").getChildren()) {
            int id = parseInt(st.getName());
            String type = String.valueOf(st.get("type").getValue());
            if ("4".equals(type) || "ACHIEVEMENTS".equalsIgnoreCase(type)) {
                for (KeyValue b : st.get("bits").getChildren()) {
                    int bit = parseInt(b.getName());
                    String name = b.get("name").getValue();
                    if (bit < 0 || bit > 31 || name == null) continue;
                    bits.put(name, new Bit(id, bit));
                    bitNames.put(id << 8 | bit, name);
                }
            } else {
                String name = st.get("name").getValue();
                if (id >= 0 && name != null) defs.put(name, new StatDef(id, name, "FLOAT".equalsIgnoreCase(type) || "2".equals(type)));
            }
        }
        Map<Integer, Integer> steam = new LinkedHashMap<>();
        for (Stats s : r.getStats()) steam.put(s.getStatId(), s.getStatValue());

        Local local = read(file);
        Map<Integer, Integer> changes = new LinkedHashMap<>();

        // Achievements: OR the phone's bits into the account's blocks.
        int newAchievements = 0;
        for (String name : local.achievements) {
            Bit b = bits.get(name);
            if (b == null) continue;   // not in the schema (a mod's?): kept in the file, not sent
            int current = changes.containsKey(b.block) ? changes.get(b.block) : steam.getOrDefault(b.block, 0);
            if ((current & (1 << b.bit)) != 0) continue;
            changes.put(b.block, current | (1 << b.bit));
            newAchievements++;
        }
        // Counters: Steam's value plus the phone's progress since the last sync; records: the larger.
        int newStats = 0;
        for (Map.Entry<String, double[]> e : local.stats.entrySet()) {
            StatDef d = defs.get(e.getKey());
            if (d == null) continue;
            double value = e.getValue()[0], base = e.getValue()[1];
            int raw = steam.getOrDefault(d.id, 0);
            double onSteam = d.isFloat ? Float.intBitsToFloat(raw) : raw;
            double merged = d.name.startsWith("Max") ? Math.max(onSteam, value) : onSteam + Math.max(0, value - base);
            if (merged <= onSteam) continue;
            changes.put(d.id, d.isFloat ? Float.floatToIntBits((float) merged) : (int) Math.min(Integer.MAX_VALUE, Math.round(merged)));
            newStats++;
        }

        if (!changes.isEmpty()) store(client, r.getCrcStats(), changes);
        steam.putAll(changes);
        write(file, defs, bitNames, steam, local);
        try {
            writeList(SteamAchievementList.cacheFile(file), r, bitNames, steam);
        } catch (Exception e) {
            Log.w(TAG, "achievement list not saved", e);   // only the screen's list; the sync is done
        }
        int total = 0;
        for (Map.Entry<Integer, String> e : bitNames.entrySet())
            if ((steam.getOrDefault(e.getKey() >> 8, 0) & (1 << (e.getKey() & 0xff))) != 0) total++;
        String summary = "achievements: " + newAchievements + " new sent, " + total + " of " + bitNames.size()
                + " unlocked; stats: " + newStats + " updated";
        Log.i(TAG, summary);
        return newAchievements;
    }

    /** Stores the changed stat values while Steam sees Valheim running, then clears "playing". */
    private static void store(SteamClient client, int crc, Map<Integer, Integer> changes) throws Exception {
        StoreHandler handler = client.getHandler(StoreHandler.class);
        playing(client, true);
        try {
            ClientMsgProtobuf<CMsgClientStoreUserStats2.Builder> msg =
                    new ClientMsgProtobuf<>(CMsgClientStoreUserStats2.class, EMsg.ClientStoreUserStats2);
            long me = client.getSteamID().convertToUInt64();
            msg.getBody().setGameId(VALHEIM).setSettorSteamId(me).setSetteeSteamId(me).setCrcStats(crc)
                    .setExplicitReset(false);
            for (Map.Entry<Integer, Integer> e : changes.entrySet())
                msg.getBody().addStatsBuilder().setStatId(e.getKey()).setStatValue(e.getValue());
            CompletableFuture<CMsgClientStoreUserStatsResponse> answer = new CompletableFuture<>();
            handler.pending = answer;
            client.send(msg);
            CMsgClientStoreUserStatsResponse res = answer.get(30, TimeUnit.SECONDS);
            handler.pending = null;
            if (res.getEresult() != EResult.OK.code())
                throw new IOException("Steam refused the stats: " + EResult.from(res.getEresult())
                        + (res.getStatsOutOfDate() ? " (out of date)" : "")
                        + (res.getStatsFailedValidationCount() > 0 ? " (" + res.getStatsFailedValidationCount() + " failed validation)" : ""));
            Log.i(TAG, "stored " + changes.size() + " stat values");
        } finally {
            playing(client, false);
        }
    }

    private static void playing(SteamClient client, boolean on) {
        ClientMsgProtobuf<CMsgClientGamesPlayed.Builder> msg =
                new ClientMsgProtobuf<>(CMsgClientGamesPlayed.class, EMsg.ClientGamesPlayed);
        if (on) msg.getBody().addGamesPlayedBuilder().setGameId(VALHEIM);
        client.send(msg);
        if (on) {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * The list the Multiplayer screen shows (SteamAchievementList): every achievement of the schema in
     * its order, with the display names in the app's languages, unlocked or not and since when (Steam's
     * time; the ones stored just now: now).
     */
    private static void writeList(File cache, UserStatsCallback r, Map<Integer, String> bitNames,
                                  Map<Integer, Integer> steam) throws Exception {
        Map<Integer, java.util.List<Integer>> times = new java.util.HashMap<>();
        for (in.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks b : r.getAchievementBlocks())
            times.put(b.getAchievementId(), b.getUnlockTime());
        KeyValue stats = r.getSchemaKeyValues().get("stats");
        long now = System.currentTimeMillis() / 1000;
        org.json.JSONArray list = new org.json.JSONArray();
        for (Map.Entry<Integer, String> e : bitNames.entrySet()) {
            int block = e.getKey() >> 8, bit = e.getKey() & 0xff;
            KeyValue display = stats.get(String.valueOf(block)).get("bits").get(String.valueOf(bit)).get("display");
            org.json.JSONObject names = new org.json.JSONObject();
            for (String lang : SteamAchievementList.LANGUAGES) {
                String n = display.get("name").get(lang).getValue();
                if (n != null) names.put(lang, n);
            }
            boolean unlocked = (steam.getOrDefault(block, 0) & (1 << bit)) != 0;
            long time = 0;
            if (unlocked) {
                java.util.List<Integer> t = times.get(block);
                time = t != null && bit < t.size() && t.get(bit) != 0 ? (t.get(bit) & 0xffffffffL) : now;
            }
            list.put(new org.json.JSONObject().put("id", e.getValue()).put("names", names)
                    .put("unlocked", unlocked).put("time", time));
        }
        org.json.JSONObject root = new org.json.JSONObject().put("synced", now).put("achievements", list);
        File tmp = new File(cache.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(root.toString());
        }
        if (!tmp.renameTo(cache)) throw new IOException("cannot replace " + cache);
    }

    // ---- the stub's file -------------------------------------------------------------------

    private static Local read(File file) throws IOException {
        Local l = new Local();
        if (!file.isFile()) return l;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 4 && p[0].equals("stat")) {
                    try { l.stats.put(p[1], new double[]{ Double.parseDouble(p[2]), Double.parseDouble(p[3]) }); }
                    catch (NumberFormatException ignored) {}
                } else if (p.length == 2 && p[0].equals("ach")) {
                    l.achievements.add(p[1]);
                }
            }
        }
        return l;
    }

    /** The account's values as both value and base; the phone's names Steam does not know stay. */
    private static void write(File file, Map<String, StatDef> defs, Map<Integer, String> bitNames,
                              Map<Integer, Integer> steam, Local local) throws IOException {
        StringBuilder sb = new StringBuilder("# ValDroid: Valheim's Steam achievements and stats on this phone (BASE = Steam's value at the last sync)\n");
        for (StatDef d : defs.values()) {
            int raw = steam.getOrDefault(d.id, 0);
            String v = d.isFloat ? Float.toString(Float.intBitsToFloat(raw)) : Integer.toString(raw);
            sb.append("stat ").append(d.name).append(' ').append(v).append(' ').append(v).append('\n');
        }
        Set<String> achievements = new LinkedHashSet<>();
        for (Map.Entry<Integer, String> e : bitNames.entrySet())
            if ((steam.getOrDefault(e.getKey() >> 8, 0) & (1 << (e.getKey() & 0xff))) != 0) achievements.add(e.getValue());
        achievements.addAll(local.achievements);
        for (String a : achievements) sb.append("ach ").append(a).append('\n');
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        if (!tmp.renameTo(file)) throw new IOException("cannot replace " + file);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }
}
