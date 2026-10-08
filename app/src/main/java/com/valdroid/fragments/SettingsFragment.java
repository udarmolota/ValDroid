package com.valdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.valdroid.InstanceSettings;
import com.valdroid.LauncherPreferences;
import com.valdroid.LauncherPreferences.VulkanDriverOption;
import com.valdroid.R;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.util.List;

public class SettingsFragment extends Fragment {

    /** Pass the instance name via arguments so this page edits THAT instance's settings. */
    public static final String ARG_INSTANCE = "instance";

    private LauncherPreferences prefs;
    private View rowEtc2Cache;
    // While the native engine is chosen the renderer group shows Vulkan and ignores its own checks.
    private boolean rendererLocked;
    private TextView tvEtc2Cache;
    private View btnGfxUltra, btnGfxLow, btnGfxMinimal;
    private InstanceSettings inst;   // per-instance: renderer / driver / debug / scale / controls
    private String instanceName;     // the instance this page edits

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = LauncherPreferences.requireSingleton();

        // Which instance are we editing? Argument (from the launcher card's gear) → last launched →
        // first installed → a "default" profile (degenerate case when no instances exist yet).
        String instName = getArguments() != null ? getArguments().getString(ARG_INSTANCE) : null;
        if (instName == null || instName.isEmpty()) instName = prefs.getLastInstanceName();
        if (instName == null || instName.isEmpty()) {
            List<GameInstance> all = GameInstanceManager.requireSingleton().getInstances();
            if (!all.isEmpty()) instName = all.get(0).getName();
        }
        if (instName == null || instName.isEmpty()) instName = "default";
        instanceName = instName;
        inst = new InstanceSettings(instName);
        requireActivity().setTitle(getString(R.string.nav_settings) + " — " + instName);

        Switch swDebug        = view.findViewById(R.id.sw_debug);
        Switch swReverse      = view.findViewById(R.id.sw_reverse_landscape);
        Switch swCompat       = view.findViewById(R.id.sw_compat_mode);
        Switch swHaptic       = view.findViewById(R.id.sw_haptic);
        final android.widget.Button btnSteamDl = view.findViewById(R.id.btn_steam_dl);
        final TextView tvSteamDlStatus = view.findViewById(R.id.tv_steam_dl_status);

        // Renderer chooser: ZINK_ZFA (GPU via Vulkan, default) or MOBILEGLUES (GPU via the phone's
        // own GLES driver — zero Vulkan; first full 1.5 session 2026-08-09, 62 fps on the S25).
        // GL4ES stays hidden; any other stored renderer falls back to MOBILEGLUES, the same as
        // InstanceSettings.getRenderer / LauncherPreferences.getRenderer.
        android.widget.RadioGroup rgRenderer = view.findViewById(R.id.rg_renderer);
        android.widget.RadioButton rbZinkZfa = view.findViewById(R.id.rb_zink_zfa);
        android.widget.RadioButton rbMobileGlues = view.findViewById(R.id.rb_mobileglues);
        switch (inst.getRenderer()) {
            case ZINK_ZFA:
                rbZinkZfa.setChecked(true);
                break;
            case MOBILEGLUES:
            default:
                rbMobileGlues.setChecked(true);
                inst.setRenderer(LauncherPreferences.Renderer.MOBILEGLUES);
                break;
        }
        rgRenderer.setOnCheckedChangeListener((group, checkedId) -> {
            if (rendererLocked) return;   // shown for the native engine, not the player's choice
            if (checkedId == R.id.rb_zink_zfa) {
                inst.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
            } else if (checkedId == R.id.rb_mobileglues) {
                inst.setRenderer(LauncherPreferences.Renderer.MOBILEGLUES);
            }
            showEtc2CacheRow(checkedId == R.id.rb_mobileglues);
        });
        swDebug.setChecked(inst.isDebug());
        // Mirrored landscape: opt-in for USB-C gamepad cradles that hold the phone the other way up.
        // Takes effect on the next launch (orientation is requested once in GameActivity.onCreate).
        swReverse.setChecked(inst.isReverseLandscape());
        swReverse.setOnCheckedChangeListener((btn, checked) -> inst.setReverseLandscape(checked));
        // Background pause opt-out. Off (the default) = the game is held while the app is not
        // visible; GameActivity reads it on every onStop, so it applies from the next time.
        Switch swKeepBg = view.findViewById(R.id.sw_keep_running_bg);
        swKeepBg.setChecked(inst.isKeepRunningInBackground());
        swKeepBg.setOnCheckedChangeListener((btn, checked) -> inst.setKeepRunningInBackground(checked));
        // Compatibility mode: box64 FP/barrier tuning (WEAKBARRIER=2 + X87DOUBLE=1) that lets the game launch
        // on devices hit by the deep "won't start / black screen" bug (Adreno 610/725, weak-Vulkan Mali).
        swCompat.setChecked(inst.isCompatibilityMode());
        // No warning dialog on enabling it any more: it sent players to the ValDroidSaveFix mod, and
        // the save bug that mod worked around is fixed at the root (box64 qsort). The switch's own
        // hint already says it is slower and only helps some devices.
        swCompat.setOnCheckedChangeListener((btn, checked) -> inst.setCompatibilityMode(checked));
        // Engine: full emulation / box64 + native Mono / native engine. An option this APK or instance
        // cannot run is hidden: native Mono needs the runtime packaged (NativeMono.isSupported), the
        // native engine the native player module. With only one left there is nothing to choose.
        GameInstance engineInstance = null;
        for (GameInstance candidate : GameInstanceManager.requireSingleton().getInstances()) {
            if (candidate.getName().equals(instanceName)) engineInstance = candidate;
        }
        boolean nativeMonoOffered = com.valdroid.game.NativeMono.isSupported(engineInstance);
        boolean nativeEngineOffered = com.valdroid.game.NativeEngine.isAvailable();
        RadioGroup rgEngine = view.findViewById(R.id.rg_engine);
        view.findViewById(R.id.rb_engine_box64_mono).setVisibility(nativeMonoOffered ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.tv_engine_box64_mono_hint).setVisibility(nativeMonoOffered ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.rb_engine_native).setVisibility(nativeEngineOffered ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.tv_engine_native_hint).setVisibility(nativeEngineOffered ? View.VISIBLE : View.GONE);
        int engineChoices = 1 + (nativeMonoOffered ? 1 : 0) + (nativeEngineOffered ? 1 : 0);
        view.findViewById(R.id.card_engine).setVisibility(engineChoices > 1 ? View.VISIBLE : View.GONE);
        int engine = inst.getEngine();
        if (engine == InstanceSettings.ENGINE_NATIVE && !nativeEngineOffered) engine = InstanceSettings.ENGINE_BOX64_MONO;
        if (engine == InstanceSettings.ENGINE_BOX64_MONO && !nativeMonoOffered) engine = InstanceSettings.ENGINE_BOX64;
        rgEngine.check(engine == InstanceSettings.ENGINE_NATIVE ? R.id.rb_engine_native
                : engine == InstanceSettings.ENGINE_BOX64_MONO ? R.id.rb_engine_box64_mono : R.id.rb_engine_box64);
        rgEngine.setOnCheckedChangeListener((group, checkedId) -> {
            int chosen = checkedId == R.id.rb_engine_native ? InstanceSettings.ENGINE_NATIVE
                    : checkedId == R.id.rb_engine_box64_mono ? InstanceSettings.ENGINE_BOX64_MONO
                    : InstanceSettings.ENGINE_BOX64;
            inst.setEngine(chosen);
            applyEngineState(view, chosen);
            // Leaving native Mono for full emulation is the player's way around a problem we would
            // otherwise never hear about.
            if (chosen == InstanceSettings.ENGINE_BOX64) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.native_mono_off_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
        // Vulkan compatibility (native engine): Auto / On / Off, in the order of the VK_COMPAT_ values.
        // Auto says what it comes to on this phone.
        Spinner spVkCompat = view.findViewById(R.id.sp_vk_compat);
        ArrayAdapter<String> vkCompatAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{
                        getString(com.valdroid.game.NativeEngine.needsVulkanCompat()
                                ? R.string.vk_compat_auto_on : R.string.vk_compat_auto_off),
                        getString(R.string.vk_compat_on), getString(R.string.vk_compat_off) });
        vkCompatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVkCompat.setAdapter(vkCompatAdapter);
        spVkCompat.setSelection(inst.getVulkanCompat());
        spVkCompat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                inst.setVulkanCompat(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        swHaptic.setChecked(inst.isHapticFeedback());
        swHaptic.setOnCheckedChangeListener((btn, checked) -> inst.setHapticFeedback(checked));
        // In-game overlay — GLOBAL: off / classic FPS counter / full performance bar. Shows the true
        // presented frame rate; the bar adds what it is up against. Takes effect next game launch.
        android.widget.RadioGroup rgHud = view.findViewById(R.id.rg_hud);
        switch (prefs.getHudMode()) {
            case LauncherPreferences.HUD_FULL: rgHud.check(R.id.rb_hud_full); break;
            case LauncherPreferences.HUD_FPS:  rgHud.check(R.id.rb_hud_fps);  break;
            default:                           rgHud.check(R.id.rb_hud_off);  break;
        }
        rgHud.setOnCheckedChangeListener((group, checkedId) -> prefs.setHudMode(
                checkedId == R.id.rb_hud_full ? LauncherPreferences.HUD_FULL
              : checkedId == R.id.rb_hud_fps  ? LauncherPreferences.HUD_FPS
              : LauncherPreferences.HUD_OFF));
        // Audio has no UI: it's always on. Raw Vorbis decodes clean since the box64 qsort_r fix, so the
        // launcher loads the libasound→AAudio shim on every launch (GameLauncher) — no toggle, no pack.

        // Advanced: per-instance extra env vars (KEY=VALUE, space-separated). Applied last in
        // GameLauncher so they override the box64 defaults. Diagnostic/perf knob (e.g.
        // BOX64_DYNAREC_ALIGNED_ATOMICS=1 for the Mali/Cortex save bug, or BOX64_DYNAREC_STRONGMEM=2).
        final android.widget.EditText etEnv = view.findViewById(R.id.et_env_vars);
        String envCur = inst.getEnvVars();
        etEnv.setText(envCur != null ? envCur : "");
        etEnv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                inst.setEnvVars(s.toString().replace('\n', ' '));   // newlines → spaces (KEY=VALUE list)
            }
        });
        // The whole Advanced card is collapsed by default (power-user diagnostics — too many testers
        // wandered in). Tap the header to expand/collapse; the arrow flips to match.
        final TextView advHeader = view.findViewById(R.id.tv_advanced_header);
        final View advContent = view.findViewById(R.id.ll_advanced_content);
        advHeader.setOnClickListener(v -> {
            boolean show = advContent.getVisibility() != View.VISIBLE;
            advContent.setVisibility(show ? View.VISIBLE : View.GONE);
            advHeader.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    show ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
        });

        // (The old Steam auth-only spike + on-device sound-pack generator shared this button; both are
        //  gone now — Steam auth moved to the Download screen, and audio is always-on raw Vorbis.)

        // Anon-mod-download spike — REMOVED (the real anonymous downloader shipped). Button hidden.
        // Steam downloader SPIKE button (full game download) HIDDEN — superseded by the real
        // "Download game (Steam)" screen. Click logic kept but unreachable.
        btnSteamDl.setVisibility(View.GONE);
        tvSteamDlStatus.setVisibility(View.GONE);
        btnSteamDl.setOnClickListener(v -> {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            final android.app.Activity act = requireActivity();
            final android.os.Handler mainH = new android.os.Handler(android.os.Looper.getMainLooper());
            final float dp = getResources().getDisplayMetrics().density;

            final boolean manifestOnly = false;  // manifest-only PASSED on device — now do the real download

            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * dp);
            box.setPadding(pad, pad, pad, 0);
            // Instance name → the game downloads into instances/<name> and becomes launchable.
            final android.widget.EditText etInstance = new android.widget.EditText(act);
            etInstance.setHint("Instance name (e.g. RimWorld 1.5)");
            etInstance.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            final android.widget.EditText etUser = new android.widget.EditText(act);
            etUser.setHint("Steam username");
            etUser.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            final android.widget.EditText etPass = new android.widget.EditText(act);
            etPass.setHint("Steam password");
            etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // Manifest = game version. ValDroid currently supports RimWorld 1.5 only (Steam "public"
            // is already 1.6). Blank → recommended 1.5 build; a number → pin a specific build.
            final android.widget.EditText etManifest = new android.widget.EditText(act);
            etManifest.setHint("Manifest ID — blank = recommended 1.5");
            etManifest.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            box.addView(etInstance);
            box.addView(etUser);
            box.addView(etPass);
            box.addView(etManifest);

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                    .setTitle("Steam download spike")
                    .setMessage((manifestOnly ? "MANIFEST-ONLY test. " : "")
                            + "ValDroid currently works with RimWorld 1.5 (Steam 'latest' is already 1.6). "
                            + "Logs in (approve in Steam Mobile / enter code), then downloads the RimWorld 1.5 "
                            + "Linux build into the named instance.")
                    .setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start", (d, w) -> {
                        String u = etUser.getText().toString().trim();
                        String p = etPass.getText().toString();
                        String name = etInstance.getText().toString().trim();
                        if (name.isEmpty()) {
                            android.widget.Toast.makeText(appCtx, "Enter an instance name", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        long manifestId = 0L;   // 0 = latest
                        try {
                            String mt = etManifest.getText().toString().trim();
                            if (!mt.isEmpty()) manifestId = Long.parseLong(mt);
                        } catch (NumberFormatException ignored) { /* blank/invalid → latest */ }
                        final long manifestIdF = manifestId;
                        mainH.post(() -> {
                            tvSteamDlStatus.setVisibility(View.VISIBLE);
                            tvSteamDlStatus.setText("Steam: connecting…");
                        });
                        // Advanced/debug downloader: newest public build unless a manifest id pins
                        // a specific one.
                        new Thread(new com.valdroid.SteamDownloadSpike(u, p, name, manifestOnly, manifestIdF,
                                new com.valdroid.SteamDownloadSpike.Listener() {
                            @Override public java.util.concurrent.CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
                                final java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
                                mainH.post(() -> {
                                    final android.widget.EditText etCode = new android.widget.EditText(act);
                                    etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
                                    etCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                                            .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                                            .setView(etCode)
                                            .setCancelable(false)
                                            .setPositiveButton("OK", (dd, ww) -> fut.complete(etCode.getText().toString().trim()))
                                            .show();
                                });
                                return fut;
                            }
                            @Override public void onProgress(String message) {
                                mainH.post(() -> tvSteamDlStatus.setText(message));
                            }
                            @Override public void onDone(String message) {
                                mainH.post(() -> tvSteamDlStatus.setText("Done: " + message));
                            }
                        }), "SteamDownloadSpike").start();
                    })
                    .show();
        });

        // (Steam Cloud saves moved out of debug into its own screen: drawer -> "Steam Cloud saves"
        //  = CloudSavesFragment. The old debug spike button here was removed.)

        // "Upload your custom driver" link (under the driver picker) → the device-global import screen.
        View tvUpload = view.findViewById(R.id.tv_upload_driver);
        tvUpload.setOnClickListener(v ->
                androidx.navigation.Navigation.findNavController(v).navigate(R.id.action_open_custom_driver));

        swDebug.setOnCheckedChangeListener((btn, checked) -> {
            inst.setDebug(checked);   // per-instance debug
            // The Steam spike buttons (btnSteamDl/btnSteamSpike — superseded by the Download screen)
            // stay fully hidden.
        });

        // --- Vulkan driver picker ---
        Spinner spDriver = view.findViewById(R.id.spinner_vulkan_driver);
        // The "Custom driver (imported)" entry is only usable once a driver has been imported
        // (App settings → Import custom Vulkan driver). Until then it is shown greyed-out and
        // cannot be selected.
        final boolean customAvailable = com.valdroid.CustomDriverInstaller.isInstalled();
        ArrayAdapter<VulkanDriverOption> driverAdapter = new ArrayAdapter<VulkanDriverOption>(
                requireContext(), android.R.layout.simple_spinner_item,
                LauncherPreferences.VULKAN_DRIVERS) {
            private boolean isCustom(int pos) {
                return com.valdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                        LauncherPreferences.VULKAN_DRIVERS.get(pos).soName);
            }
            @Override public boolean areAllItemsEnabled() { return customAvailable; }
            @Override public boolean isEnabled(int pos) { return customAvailable || !isCustom(pos); }
            @Override public View getDropDownView(int pos, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(pos, convertView, parent);
                boolean enabled = isEnabled(pos);
                if (v instanceof TextView) ((TextView) v).setEnabled(enabled);
                v.setEnabled(enabled);
                v.setAlpha(enabled ? 1f : 0.4f);
                return v;
            }
        };
        driverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDriver.setAdapter(driverAdapter);

        // If the saved choice was the custom driver but none is imported, fall back to System.
        int driverIdx = inst.getVulkanDriverIndex();
        if (!customAvailable && com.valdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                LauncherPreferences.VULKAN_DRIVERS.get(driverIdx).soName)) {
            driverIdx = 0;
            inst.setVulkanDriverSo(LauncherPreferences.VULKAN_DRIVERS.get(0).soName);
        }
        spDriver.setSelection(driverIdx);
        spDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!customAvailable && com.valdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                        LauncherPreferences.VULKAN_DRIVERS.get(pos).soName)) {
                    spDriver.setSelection(0);   // disabled entry — revert to System
                    return;
                }
                inst.setVulkanDriverSo(LauncherPreferences.VULKAN_DRIVERS.get(pos).soName);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // --- Recommended driver: detect the GPU and pick the matching bundled driver ---
        View btnRecommend = view.findViewById(R.id.btn_recommend_driver);
        btnRecommend.setOnClickListener(v -> {
            btnRecommend.setEnabled(false);
            new Thread(() -> {
                com.valdroid.GpuInfo gpu = com.valdroid.GpuInfo.query();
                String so = gpu.recommendedDriverSo();
                int idx = 0;
                for (int i = 0; i < LauncherPreferences.VULKAN_DRIVERS.size(); i++) {
                    if (LauncherPreferences.VULKAN_DRIVERS.get(i).soName.equals(so)) { idx = i; break; }
                }
                final int fIdx = idx;
                final String label = LauncherPreferences.VULKAN_DRIVERS.get(idx).label;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    spDriver.setSelection(fIdx);   // fires onItemSelected → persists the choice
                    btnRecommend.setEnabled(true);
                    android.widget.Toast.makeText(requireContext(),
                            getString(R.string.driver_recommend_result, gpu.displayName(), label),
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }, "rd-gpu-detect").start();
        });

        // --- Render resolution (Video card): vertical radios, just the resolution text. Per-device
        // presets from the ~540-row floor up to 72%. Lower = more FPS on weak GPUs. Applied at the next
        // launch.
        android.widget.RadioGroup rgRes = view.findViewById(R.id.rg_render_res);
        android.graphics.Rect bounds =
                requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        final int sLong  = Math.max(bounds.width(), bounds.height());   // landscape width
        final int sShort = Math.min(bounds.width(), bounds.height());   // landscape height = native render height
        final int MIN = LauncherPreferences.minRenderScalePercent(sLong, sShort);
        // Up to 72% only (778 rows on a 1080p panel): 85% and native were too heavy for phones. The
        // floor is the default, so a fresh install starts on the lightest option.
        java.util.LinkedHashSet<Integer> pctSet = new java.util.LinkedHashSet<>();
        pctSet.add(MIN);
        for (int p : new int[]{ 60, LauncherPreferences.RENDER_SCALE_MAX_OFFERED }) if (p > MIN) pctSet.add(p);
        final java.util.List<Integer> pcts = new java.util.ArrayList<>(pctSet);
        java.util.Collections.sort(pcts);   // ascending; displayed high → floor (low)

        final int curPct = LauncherPreferences.effectiveRenderScalePercent(inst.getRenderScalePercent(), sLong, sShort);
        final boolean curFixed = inst.getFixedResMode() != com.valdroid.InstanceSettings.FIXED_NONE;
        int nearestPct = pcts.get(0), nearestD = Integer.MAX_VALUE;   // relative preset closest to the stored %
        for (int p : pcts) { int d = Math.abs(p - curPct); if (d < nearestD) { nearestD = d; nearestPct = p; } }

        final int FIXED_TAG = -1;   // sentinel tag = the fixed 1280x720 letterboxed radio (not a percent)
        for (int i = pcts.size() - 1; i >= 0; i--) {   // native first, down to the floor
            int p = pcts.get(i);
            int W = Math.round(sLong * p / 100f), H = Math.round(sShort * p / 100f);
            android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
            rb.setId(View.generateViewId());
            rb.setText(W + "×" + H + " (" + getString(R.string.render_res_fullscreen) + ")");
            rb.setTag(p);
            rgRes.addView(rb);
            if (!curFixed && p == nearestPct) rb.setChecked(true);
        }
        android.widget.RadioButton rbFixed = new android.widget.RadioButton(requireContext());
        rbFixed.setId(View.generateViewId());
        rbFixed.setText("1280×720 (" + getString(R.string.render_res_black_margins) + ")");
        rbFixed.setTag(FIXED_TAG);
        rgRes.addView(rbFixed);
        if (curFixed) rbFixed.setChecked(true);

        rgRes.setOnCheckedChangeListener((group, checkedId) -> {   // set after the initial checks → no spurious writes
            View rb = group.findViewById(checkedId);
            if (rb == null || rb.getTag() == null) return;
            int tag = (Integer) rb.getTag();
            if (tag == FIXED_TAG) {
                inst.setFixedResMode(com.valdroid.InstanceSettings.FIXED_720_16_9);
            } else {
                inst.setFixedResMode(com.valdroid.InstanceSettings.FIXED_NONE);
                inst.setRenderScalePercent(tag);   // while fixed mode is off, GameActivity uses this %
            }
        });

        // Graphics profile: a button queues that profile for the NEXT launch (written once, then the
        // game's own settings are left alone). It is queued rather than written now because the
        // game, if running, would overwrite the file on exit.
        btnGfxUltra = view.findViewById(R.id.btn_gfx_ultra);
        btnGfxLow = view.findViewById(R.id.btn_gfx_low);
        btnGfxUltra.setOnClickListener(v ->
                queueGraphicsProfile(com.valdroid.InstanceSettings.GFX_ULTRA, R.string.gfx_preset_ultra));
        btnGfxLow.setOnClickListener(v ->
                queueGraphicsProfile(com.valdroid.InstanceSettings.GFX_LOW, R.string.gfx_preset_low));
        btnGfxMinimal = view.findViewById(R.id.btn_gfx_minimal);
        btnGfxMinimal.setOnClickListener(v ->
                queueGraphicsProfile(com.valdroid.InstanceSettings.GFX_MINIMAL, R.string.gfx_preset_minimal));
        refreshGraphicsButtons();

        // ETC2 compression switch (both ETC2 paths; see GameLauncher). Takes effect on next launch.
        Switch swEtc2 = view.findViewById(R.id.sw_etc2);
        swEtc2.setChecked(inst.isEtc2Enabled());
        swEtc2.setOnCheckedChangeListener((btn, checked) -> inst.setEtc2Enabled(checked));

        // Shader (program binary) cache switch. Takes effect on next launch.
        Switch swShaderCache = view.findViewById(R.id.sw_shader_cache);
        swShaderCache.setChecked(inst.isShaderCache());
        swShaderCache.setOnCheckedChangeListener((btn, checked) -> inst.setShaderCache(checked));

        // ETC2 transcode cache: size + Clear, collapsed to its header — it is housekeeping, not a
        // setting. App-wide (every instance shares it), but shown here because it only exists for
        // MobileGlues, and this is where the renderer is chosen.
        rowEtc2Cache = view.findViewById(R.id.row_etc2_cache);
        tvEtc2Cache = view.findViewById(R.id.tv_etc2_cache);
        final TextView cacheHeader = view.findViewById(R.id.tv_etc2_cache_header);
        final View cacheBody = view.findViewById(R.id.body_etc2_cache);
        cacheHeader.setOnClickListener(v -> {
            boolean open = cacheBody.getVisibility() != View.VISIBLE;
            cacheBody.setVisibility(open ? View.VISIBLE : View.GONE);
            cacheHeader.setText(open ? R.string.etc2_cache_header_expanded : R.string.etc2_cache_header_collapsed);
            if (open) refreshEtc2CacheSize();
        });
        view.findViewById(R.id.btn_etc2_cache_clear).setOnClickListener(v -> clearEtc2Cache());
        showEtc2CacheRow(inst.getRenderer() == LauncherPreferences.Renderer.MOBILEGLUES);

        // Frame-rate mode, a dropdown: off / Economy ~30 / Balanced ~40 / Smooth ~60. The concrete
        // number is picked for this screen (FpsPlanner) and shown under the choice, so nobody has to
        // know their panel's refresh rates. Takes effect on next launch.
        final int[] fpsModes = { com.valdroid.FpsPlanner.OFF, com.valdroid.FpsPlanner.ECONOMY,
                com.valdroid.FpsPlanner.BALANCED, com.valdroid.FpsPlanner.SMOOTH };
        Spinner spFps = view.findViewById(R.id.sp_fps_cap);
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{ getString(R.string.fps_cap_off),
                        getString(R.string.fps_mode_eco), getString(R.string.fps_mode_balanced),
                        getString(R.string.fps_mode_smooth) });
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFps.setAdapter(fpsAdapter);
        final TextView tvFpsResolved = view.findViewById(R.id.tv_fps_resolved);
        for (int i = 0; i < fpsModes.length; i++) if (fpsModes[i] == inst.getFpsMode()) spFps.setSelection(i);
        showFpsResolved(tvFpsResolved, inst.getFpsMode());
        spFps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                inst.setFpsMode(fpsModes[position]);
                showFpsResolved(tvFpsResolved, fpsModes[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Texture compression card: folds to its header on a tap (applyEngineState sets it per engine).
        view.findViewById(R.id.tv_texq_header).setOnClickListener(v ->
                setTexqOpen(view, view.findViewById(R.id.body_texq).getVisibility() != View.VISIBLE));

        applyEngineState(view, engine);
    }

    /**
     * Settings the native engine does not use are greyed out while it is chosen: the renderer (it
     * always renders with Vulkan directly, so the group shows that), the Vulkan driver (the phone's
     * own), MobileGlues' ETC2 and shader caches, and box64's compatibility mode. Their stored values
     * stay as they are and come back with a box64 engine.
     */
    private void applyEngineState(View view, int engine) {
        boolean nativeEngine = engine == InstanceSettings.ENGINE_NATIVE;
        setTexqOpen(view, !nativeEngine);   // all MobileGlues: of no use to the native engine
        RadioGroup rgRenderer = view.findViewById(R.id.rg_renderer);
        rendererLocked = true;
        if (nativeEngine) rgRenderer.check(R.id.rb_zink_zfa);
        else rgRenderer.check(inst.getRenderer() == LauncherPreferences.Renderer.ZINK_ZFA
                ? R.id.rb_zink_zfa : R.id.rb_mobileglues);
        rendererLocked = false;
        for (int i = 0; i < rgRenderer.getChildCount(); i++) rgRenderer.getChildAt(i).setEnabled(!nativeEngine);
        // The driver import stays live: the native engine can test an imported driver
        // (VALDROID_VULKAN_DRIVER=custom in the environment field).
        for (int id : new int[]{ R.id.spinner_vulkan_driver, R.id.btn_recommend_driver,
                R.id.sw_etc2, R.id.sw_shader_cache, R.id.sw_compat_mode }) {
            View v = view.findViewById(id);
            if (v != null) v.setEnabled(!nativeEngine);
        }
        view.findViewById(R.id.tv_native_renderer_note).setVisibility(nativeEngine ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.tv_native_driver_note).setVisibility(nativeEngine ? View.VISIBLE : View.GONE);
        boolean weakGpu = nativeEngine && com.valdroid.GpuInfo.query().isWeakForNativeEngine();
        view.findViewById(R.id.tv_engine_weak_gpu_note).setVisibility(weakGpu ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.ll_vk_compat).setVisibility(nativeEngine ? View.VISIBLE : View.GONE);
    }

    private void setTexqOpen(View view, boolean open) {
        view.findViewById(R.id.body_texq).setVisibility(open ? View.VISIBLE : View.GONE);
        ((TextView) view.findViewById(R.id.tv_texq_header)).setText(
                getString(R.string.texq_label) + (open ? " \u25BE" : " \u25B8"));
    }

    // The game's settings change while it runs, so the buttons are re-read on every return here.
    @Override
    public void onResume() {
        super.onResume();
        refreshGraphicsButtons();
    }

    // ---- frame-rate mode: what it becomes on THIS screen ----

    private void showFpsResolved(TextView tv, int mode) {
        if (tv == null || !isAdded()) return;
        com.valdroid.FpsPlanner.Plan p = com.valdroid.FpsPlanner.plan(requireActivity().getDisplay(), mode);
        if (mode == com.valdroid.FpsPlanner.OFF)
            tv.setText(p.refreshHz > 0 ? getString(R.string.fps_mode_resolved_off, p.refreshHz) : "");
        else if (p.fellBack)
            tv.setText(getString(R.string.fps_mode_resolved_fallback, p.fps, p.refreshHz));
        else
            tv.setText(getString(R.string.fps_mode_resolved, p.fps, p.refreshHz));
    }

    // ---- graphics profile buttons ----

    private void queueGraphicsProfile(int preset, int nameRes) {
        inst.setGraphicsPending(preset);
        inst.setGraphicsMinimal(preset == InstanceSettings.GFX_MINIMAL);
        android.widget.Toast.makeText(requireContext(),
                getString(R.string.gfx_preset_pending, getString(nameRes)),
                android.widget.Toast.LENGTH_SHORT).show();
        refreshGraphicsButtons();
    }

    /**
     * A button is greyed while its profile is what the game will run with: the one queued for the
     * next launch if there is one, else whatever the game's settings file matches exactly. Both
     * stay live once the player has changed anything in game. Reads a small file, off the main
     * thread anyway.
     */
    private void refreshGraphicsButtons() {
        if (btnGfxUltra == null || inst == null) return;
        final InstanceSettings s = inst;
        final java.io.File dir = com.valdroid.AppStorage.requireSingleton().getInstanceDir(instanceName);
        new Thread(() -> {
            int pending = s.getGraphicsPending();
            final int current = (pending != InstanceSettings.GFX_KEEP) ? pending
                    : com.valdroid.ValheimInstanceSetup.detectGraphicsPreset(dir);
            android.app.Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded() || btnGfxUltra == null) return;
                btnGfxUltra.setEnabled(current != InstanceSettings.GFX_ULTRA);
                btnGfxLow.setEnabled(current != InstanceSettings.GFX_LOW);
                btnGfxMinimal.setEnabled(current != InstanceSettings.GFX_MINIMAL);
            });
        }, "GfxProfileDetect").start();
    }

    // ---- ETC2 transcode cache row ----
    // Directory walks go off the main thread: the cache holds thousands of files once warm.

    private void showEtc2CacheRow(boolean visible) {
        if (rowEtc2Cache == null) return;
        rowEtc2Cache.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void refreshEtc2CacheSize() {
        final java.io.File dir = com.valdroid.AppStorage.requireSingleton().getEtc2CacheDir();
        new Thread(() -> {
            long bytes = 0;
            java.io.File[] fs = dir.listFiles();
            if (fs != null) for (java.io.File f : fs) if (f.isFile()) bytes += f.length();
            final long total = bytes;
            android.app.Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded() || tvEtc2Cache == null) return;
                tvEtc2Cache.setText(total == 0 ? getString(R.string.etc2_cache_empty)
                        : getString(R.string.etc2_cache_size,
                                android.text.format.Formatter.formatShortFileSize(requireContext(), total)));
            });
        }, "Etc2CacheSize").start();
    }

    /**
     * Safe even with the game running: a vanished entry is just a miss, re-encoded on sight. Only
     * top-level files are touched — the cache is flat, and a stray subdirectory is not ours.
     */
    private void clearEtc2Cache() {
        final java.io.File dir = com.valdroid.AppStorage.requireSingleton().getEtc2CacheDir();
        new Thread(() -> {
            java.io.File[] fs = dir.listFiles();
            if (fs != null) for (java.io.File f : fs) if (f.isFile()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            android.app.Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded()) return;
                android.widget.Toast.makeText(requireContext(), R.string.etc2_cache_cleared,
                        android.widget.Toast.LENGTH_SHORT).show();
                refreshEtc2CacheSize();
            });
        }, "Etc2CacheClear").start();
    }
}
