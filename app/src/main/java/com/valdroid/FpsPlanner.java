package com.valdroid;

import android.view.Display;

/**
 * Turns a frame-rate MODE (Economy ~30 / Balanced ~40 / Smooth ~60 / off) into a concrete cap for
 * THIS screen, plus the display mode to switch the panel to — so the cap divides the refresh rate
 * exactly and frames are held for an equal number of refreshes. A cap that does not divide the
 * refresh rate (45 on 120 Hz: 2-3-3-2 refreshes per frame) judders even at a steady average, which
 * is why players are never asked for a number: they pick a mode and the screen decides the number.
 *
 * "About 40" is what most phones can hold once warm: 30 feels slow, 60 is out of reach for most
 * outside the lowest settings. It comes out as 40 on 120 Hz (or 80 Hz), 45 on 90 Hz, 48 on 144 Hz.
 * A 60 Hz-only screen has no even value near 40 — Balanced then falls back to 30 and says so.
 */
public final class FpsPlanner {
    public static final int OFF = 0, ECONOMY = 1, BALANCED = 2, SMOOTH = 3;

    /** The resolved cap. fps 0 = uncapped; modeId 0 = leave the panel as the system has it. */
    public static final class Plan {
        public final int fps, refreshHz, modeId;
        public final boolean fellBack;   // Balanced found no even value near 40 and used Economy's
        Plan(int fps, int refreshHz, int modeId, boolean fellBack) {
            this.fps = fps; this.refreshHz = refreshHz; this.modeId = modeId; this.fellBack = fellBack;
        }
    }

    public static Plan plan(Display display, int mode) {
        if (mode == OFF || display == null) return new Plan(0, maxHz(display), 0, false);
        Plan p = pick(display, target(mode), lo(mode), hi(mode));
        if (p == null && mode == BALANCED) {
            Plan eco = pick(display, target(ECONOMY), lo(ECONOMY), hi(ECONOMY));
            if (eco != null) return new Plan(eco.fps, eco.refreshHz, eco.modeId, true);
        }
        // Nothing even at all (an odd panel): cap at the target and leave the panel alone.
        return p != null ? p : new Plan(target(mode), maxHz(display), 0, false);
    }

    private static int target(int m) { return m == ECONOMY ? 30 : m == SMOOTH ? 60 : 40; }
    private static int lo(int m)     { return m == ECONOMY ? 25 : m == SMOOTH ? 55 : 36; }
    private static int hi(int m)     { return m == ECONOMY ? 35 : m == SMOOTH ? 72 : 50; }

    /**
     * Best (refresh rate / k) inside [lo, hi]: closest to the target; on a tie the LOWER refresh
     * rate wins — same frames on screen, less power spent refreshing the panel. Only modes at the
     * current resolution (never trigger a resolution switch), and only 50 Hz and up: the 24/30/48 Hz
     * modes some panels list are for video and idle screens, not for a game.
     */
    private static Plan pick(Display d, int target, int lo, int hi) {
        Display.Mode cur = d.getMode();
        Plan best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                    || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            int hz = Math.round(m.getRefreshRate());
            if (hz < 50) continue;
            for (int k = 1; k <= 6; k++) {
                if (hz % k != 0) continue;
                int fps = hz / k;
                if (fps < lo || fps > hi) continue;
                int dist = Math.abs(fps - target);
                if (best == null || dist < bestDist || (dist == bestDist && hz < best.refreshHz)) {
                    best = new Plan(fps, hz, m.getModeId(), false);
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    public static int maxHz(Display d) {
        if (d == null) return 0;
        int best = 0;
        for (Display.Mode m : d.getSupportedModes()) best = Math.max(best, Math.round(m.getRefreshRate()));
        return best;
    }
}
