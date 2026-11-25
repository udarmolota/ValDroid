package com.valdroid.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.ModManager;
import com.valdroid.R;
import com.valdroid.ThunderstoreClient;
import com.valdroid.ThunderstoreClient.Pkg;
import com.valdroid.ThunderstoreClient.Resolved;
import com.valdroid.ThunderstoreIndex;
import com.valdroid.ThunderstoreIndex.Entry;
import com.valdroid.game.GameInstance;
import com.valdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thunderstore: our own catalog of Valheim mods, two cards a row with search, sort and a category
 * filter, built from Thunderstore's listing for mod managers (ThunderstoreIndex, kept on the device).
 * The ↓ on a card queues the mod; tapping the card opens its Thunderstore page over the catalog in a
 * WebView, where the bar queues it too and the site's own download links queue the version they point
 * at. "Install" asks for the instance, works out the dependencies (from the catalog), shows the whole
 * list with its size and installs it with the same code as a zip picked on the Mods screen.
 *
 * <p>The first catalog download is about 15 MB: off Wi-Fi it is asked for first. Later refreshes
 * (at most daily, on Wi-Fi only) fetch only the parts that changed.
 */
public class ThunderstoreFragment extends Fragment {
    private static final String PAGE_URL = "https://thunderstore.io/c/valheim/p/";
    private static final String PREFS = "thunderstore";
    private static final String KEY_TEXT_ZOOM = "text_zoom";
    /** The site's text is large in a phone's WebView: start smaller, as the Workshop screens do. */
    private static final int DEFAULT_TEXT_ZOOM = 80;
    /** The queue button on a card: outlined while not queued, filled green once queued (as Zomdroid). */
    private static final int QUEUED_GREEN = 0xFF4C9A2A;

    /** key ("Ns-Name") -> package (version null = latest), in the order added. Kept for the app session. */
    private static final Map<String, Pkg> QUEUE = new LinkedHashMap<>();
    /** The catalog as last loaded: reading it again on every visit would cost a second or two. */
    @Nullable private static List<Entry> sCatalog;
    @Nullable private static Map<String, Entry> sByKey;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> shown = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private IconLoader icons;

    private WebView web;
    private View catalogView;
    private ProgressBar progress;
    private EditText etSearch;
    private Spinner spSort, spCategory;
    private View statusRow;
    private TextView tvStatus;
    private MaterialButton btnStatus;
    private TextView tvItem;
    private Button btnQueue;
    private Button btnInstall;
    private View btnTextSmaller, btnTextLarger;
    @Nullable private Pkg current;
    private boolean busy;
    private final List<String> categories = new ArrayList<>();
    private final Runnable applyFilter = this::filter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_thunderstore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        icons = new IconLoader(requireContext().getApplicationContext());
        catalogView = v.findViewById(R.id.ll_ts_catalog);
        web = v.findViewById(R.id.wv_thunderstore);
        progress = v.findViewById(R.id.pb_thunderstore);
        etSearch = v.findViewById(R.id.et_ts_search);
        spSort = v.findViewById(R.id.sp_ts_sort);
        spCategory = v.findViewById(R.id.sp_ts_category);
        statusRow = v.findViewById(R.id.ll_ts_status);
        tvStatus = v.findViewById(R.id.tv_ts_status);
        btnStatus = v.findViewById(R.id.btn_ts_status);
        tvItem = v.findViewById(R.id.tv_thunderstore_item);
        btnQueue = v.findViewById(R.id.btn_thunderstore_queue);
        btnInstall = v.findViewById(R.id.btn_thunderstore_install);
        btnTextSmaller = v.findViewById(R.id.btn_ts_text_smaller);
        btnTextLarger = v.findViewById(R.id.btn_ts_text_larger);

        RecyclerView rv = v.findViewById(R.id.rv_ts);
        // A column per ~180dp of width, at least two: two on a phone held upright, more in landscape.
        // Handhelds such as the Retroid stay landscape, where two fixed columns made each square icon
        // taller than the screen (2026-11-24). The screen is built again on a rotation.
        int columns = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
        rv.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        rv.setAdapter(adapter);

        ArrayAdapter<String> sorts = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.ts_sort_popular), getString(R.string.ts_sort_updated),
                getString(R.string.ts_sort_rating), getString(R.string.ts_sort_name)});
        sorts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSort.setAdapter(sorts);
        AdapterView.OnItemSelectedListener refilter = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { filter(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spSort.setOnItemSelectedListener(refilter);
        spCategory.setOnItemSelectedListener(refilter);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(applyFilter);
                main.postDelayed(applyFilter, 250);
            }
        });

        setUpWebView();
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        web.getSettings().setTextZoom(prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM));
        btnTextSmaller.setOnClickListener(x -> zoomText(prefs, -10));
        btnTextLarger.setOnClickListener(x -> zoomText(prefs, +10));
        btnQueue.setOnClickListener(x -> toggleCurrent());
        btnInstall.setOnClickListener(x -> chooseInstance());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        if (web != null && web.getVisibility() == View.VISIBLE) {
                            if (web.canGoBack()) web.goBack();
                            else closePage();
                        } else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
        updateBar();
        startCatalog();
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacks(applyFilter);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        if (icons != null) icons.shutdown();
        super.onDestroyView();
    }

    // ------------------------------------------------------------------ catalog

    private void startCatalog() {
        if (sCatalog != null) {
            showCatalog(sCatalog);
            maybeRefreshQuietly();
            return;
        }
        Context app = requireContext().getApplicationContext();
        if (ThunderstoreIndex.hasCache(app)) {
            status(getString(R.string.ts_reading_catalog), null, null);
            new Thread(() -> {
                List<Entry> all = null;
                try { all = ThunderstoreIndex.load(app); } catch (Exception ignored) {}
                final List<Entry> loaded = all;
                main.post(() -> {
                    if (!isAdded()) return;
                    if (loaded == null || loaded.isEmpty()) { askToDownload(); return; }
                    setCatalog(loaded);
                    maybeRefreshQuietly();
                });
            }, "ThunderstoreLoad").start();
        } else {
            askToDownload();
        }
    }

    /** The first download: straight away on Wi-Fi, otherwise after asking. */
    private void askToDownload() {
        if (onWifi()) {
            download(false);
            return;
        }
        status(getString(R.string.ts_not_loaded), getString(R.string.ts_load_catalog), () -> askToDownload2());
        askToDownload2();
    }

    private void askToDownload2() {
        dialog().setTitle(R.string.ts_mobile_title)
                .setMessage(R.string.ts_mobile_message)
                .setPositiveButton(R.string.ts_load, (d, w) -> download(false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A daily refresh in the background, on Wi-Fi only; the catalog shown stays usable meanwhile. */
    private void maybeRefreshQuietly() {
        Context app = requireContext().getApplicationContext();
        if (ThunderstoreIndex.age(app) > ThunderstoreIndex.MAX_AGE_MS && onWifi()) download(true);
    }

    private void download(boolean quietly) {
        Context app = requireContext().getApplicationContext();
        if (!quietly) status(getString(R.string.ts_loading_catalog, 0, 0), null, null);
        new Thread(() -> {
            String error = null;
            List<Entry> all = null;
            try {
                ThunderstoreIndex.refresh(app, (done, total) -> {
                    if (!quietly) main.post(() -> { if (isAdded()) status(getString(R.string.ts_loading_catalog, done, total), null, null); });
                });
                all = ThunderstoreIndex.load(app);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<Entry> loaded = all;
            final String err = error;
            main.post(() -> {
                if (!isAdded()) return;
                if (loaded != null && !loaded.isEmpty()) setCatalog(loaded);
                else if (!quietly) status(getString(R.string.ts_catalog_failed, String.valueOf(err)),
                        getString(R.string.ts_retry), () -> download(false));
            });
        }, "ThunderstoreIndex").start();
    }

    private void setCatalog(List<Entry> all) {
        sCatalog = all;
        sByKey = ThunderstoreIndex.byKey(all);
        showCatalog(all);
    }

    private void showCatalog(List<Entry> all) {
        TreeSet<String> cats = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry e : all) if (visible(e)) cats.addAll(e.categories);
        String chosen = spCategory.getSelectedItemPosition() > 0 ? categories.get(spCategory.getSelectedItemPosition()) : null;
        categories.clear();
        categories.add(getString(R.string.ts_all_categories));
        categories.addAll(cats);
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, categories);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(a);
        if (chosen != null && categories.contains(chosen)) spCategory.setSelection(categories.indexOf(chosen));
        filter();
    }

    /** What the catalog never shows: mods marked deprecated or NSFW (still used for dependencies). */
    private static boolean visible(Entry e) { return !e.deprecated && !e.nsfw; }

    private void filter() {
        List<Entry> all = sCatalog;
        if (all == null || etSearch == null) return;
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        int cat = spCategory.getSelectedItemPosition();
        String category = cat > 0 && cat < categories.size() ? categories.get(cat) : null;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (!visible(e)) continue;
            if (category != null && !e.categories.contains(category)) continue;
            if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)
                    && !e.namespace.toLowerCase(Locale.ROOT).contains(q)
                    && !e.description.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(e);
        }
        Comparator<Entry> order;
        switch (spSort.getSelectedItemPosition()) {
            case 1: order = (a, b) -> b.updated.compareTo(a.updated); break;
            case 2: order = (a, b) -> Integer.compare(b.rating, a.rating); break;
            case 3: order = (a, b) -> a.name.compareToIgnoreCase(b.name); break;
            default: order = (a, b) -> Long.compare(b.downloads, a.downloads);
        }
        Collections.sort(out, order);
        shown.clear();
        shown.addAll(out);
        adapter.notifyDataSetChanged();
        if (out.isEmpty()) status(getString(R.string.ts_nothing_found), null, null);
        else statusRow.setVisibility(View.GONE);
    }

    private void status(String text, @Nullable String button, @Nullable Runnable action) {
        statusRow.setVisibility(View.VISIBLE);
        tvStatus.setText(text);
        btnStatus.setVisibility(button != null ? View.VISIBLE : View.GONE);
        if (button != null) {
            btnStatus.setText(button);
            btnStatus.setOnClickListener(x -> { if (action != null) action.run(); });
        }
    }

    private boolean onWifi() {
        ConnectivityManager cm = (ConnectivityManager) requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    // ------------------------------------------------------------------ cards

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_thunderstore_mod, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder h, int pos) { h.bind(shown.get(pos)); }
        @Override public int getItemCount() { return shown.size(); }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final MaterialButton queue;
        final TextView name, meta, desc;
        Entry entry;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.iv_ts_icon);
            queue = v.findViewById(R.id.btn_ts_queue);
            name = v.findViewById(R.id.tv_ts_name);
            meta = v.findViewById(R.id.tv_ts_meta);
            desc = v.findViewById(R.id.tv_ts_desc);
            v.setOnClickListener(x -> { if (entry != null) openPage(entry); });
            queue.setOnClickListener(x -> {
                if (entry == null) return;
                toggle(new Pkg(entry.namespace, entry.name, null));
                paintQueue();
            });
        }

        void bind(Entry e) {
            entry = e;
            name.setText(e.title());
            meta.setText(getString(R.string.ts_card_meta, e.namespace, count(e.downloads), size(e.size)));
            desc.setText(e.description);
            paintQueue();
            icons.load(e.icon, icon);
        }

        void paintQueue() {
            boolean q;
            synchronized (QUEUE) { q = QUEUE.containsKey(entry.key()); }
            queue.setText(q ? "✓" : "↓");
            queue.setTextColor(0xFFFFFFFF);
            queue.setStrokeColor(android.content.res.ColorStateList.valueOf(q ? QUEUED_GREEN : 0xFFFFFFFF));
            queue.setStrokeWidth(Math.round(2 * queue.getResources().getDisplayMetrics().density));
            queue.setBackgroundTintList(android.content.res.ColorStateList.valueOf(q ? QUEUED_GREEN : 0x00000000));
            queue.setContentDescription(getString(q ? R.string.ts_remove_from_queue : R.string.ts_add_to_queue));
        }
    }

    /** 1234567 -> "1.2M". */
    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private static String size(long bytes) {
        if (bytes >= 1 << 20) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%d KB", Math.max(1, bytes / 1024));
    }

    /** Mod icons: memory and disk cache, a few downloads at a time, sized down for a card. */
    private static final class IconLoader {
        private final File dir;
        private final ExecutorService pool = Executors.newFixedThreadPool(4);
        private final Handler main = new Handler(Looper.getMainLooper());
        private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
                (int) Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 8)) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };

        IconLoader(Context app) { dir = new File(app.getCacheDir(), "thunderstore_icons"); }

        void load(String url, ImageView into) {
            into.setTag(url);
            Bitmap b = url.isEmpty() ? null : MEMORY.get(url);
            into.setImageBitmap(b);
            if (b != null || url.isEmpty()) return;
            pool.execute(() -> {
                Bitmap got = fetch(url);
                if (got == null) return;
                MEMORY.put(url, got);
                main.post(() -> { if (url.equals(into.getTag())) into.setImageBitmap(got); });
            });
        }

        void shutdown() { pool.shutdownNow(); }

        @Nullable
        private Bitmap fetch(String url) {
            File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".img");
            if (!f.isFile()) {
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                File tmp = new File(f.getPath() + ".tmp");
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20_000);
                    c.setReadTimeout(20_000);
                    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        c.disconnect();
                    }
                    if (!tmp.renameTo(f)) return null;
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return null;
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;   // icons are 256 px; a card shows them at about half that
            return BitmapFactory.decodeFile(f.getPath(), o);
        }
    }

    // ------------------------------------------------------------------ a mod's page

    @SuppressLint("SetJavaScriptEnabled") // Thunderstore is a script-driven site
    private void setUpWebView() {
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 || web.getVisibility() != View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // A download or "install with mod manager" link: queue that version, stay on the page.
                Pkg linked = ThunderstoreClient.fromDownloadLink(uri.toString());
                if (linked != null) {
                    queue(linked);
                    return true;
                }
                String host = uri.getHost() == null ? "" : uri.getHost();
                if ("https".equals(uri.getScheme()) && (host.equals("thunderstore.io") || host.endsWith(".thunderstore.io"))) {
                    return false;
                }
                // Links out of Thunderstore (GitHub, Discord, Ko-fi in mod descriptions) go to the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // no app for it: stay put
                }
                return true;
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (web != null && web.getVisibility() == View.VISIBLE) {
                    current = ThunderstoreClient.fromPage(url);   // the site changes pages without reloading
                    updateBar();
                }
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            Pkg linked = ThunderstoreClient.fromDownloadLink(url);
            if (linked != null) queue(linked);
        });
    }

    private void zoomText(android.content.SharedPreferences prefs, int delta) {
        if (web == null) return;
        int zoom = Math.max(50, Math.min(150, web.getSettings().getTextZoom() + delta));
        web.getSettings().setTextZoom(zoom);
        prefs.edit().putInt(KEY_TEXT_ZOOM, zoom).apply();
    }

    private void openPage(Entry e) {
        current = new Pkg(e.namespace, e.name, null);
        web.clearHistory();
        web.loadUrl(PAGE_URL + e.namespace + "/" + e.name + "/");
        web.setVisibility(View.VISIBLE);
        catalogView.setVisibility(View.GONE);
        btnTextSmaller.setVisibility(View.VISIBLE);
        btnTextLarger.setVisibility(View.VISIBLE);
        updateBar();
    }

    private void closePage() {
        web.stopLoading();
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        progress.setVisibility(View.INVISIBLE);
        catalogView.setVisibility(View.VISIBLE);
        btnTextSmaller.setVisibility(View.GONE);
        btnTextLarger.setVisibility(View.GONE);
        current = null;
        adapter.notifyDataSetChanged();   // the page may have queued something
        updateBar();
    }

    // ------------------------------------------------------------------ queue and bar

    private void queue(Pkg p) {
        synchronized (QUEUE) { QUEUE.put(p.key(), p); }
        if (isAdded()) Toast.makeText(requireContext(), getString(R.string.ts_added, p.name.replace('_', ' ')), Toast.LENGTH_SHORT).show();
        updateBar();
    }

    private void toggle(Pkg p) {
        synchronized (QUEUE) {
            if (QUEUE.containsKey(p.key())) QUEUE.remove(p.key());
            else QUEUE.put(p.key(), p);
        }
        updateBar();
    }

    private void toggleCurrent() {
        if (current != null) toggle(current);
    }

    private void updateBar() {
        if (tvItem == null) return;
        int size;
        boolean queued;
        synchronized (QUEUE) {
            size = QUEUE.size();
            queued = current != null && QUEUE.containsKey(current.key());
        }
        if (current != null) {
            tvItem.setText(current.name.replace('_', ' ') + "\n" + current.namespace);
            btnQueue.setVisibility(View.VISIBLE);
            btnQueue.setText(queued ? R.string.ts_remove_from_queue : R.string.ts_add_to_queue);
        } else {
            tvItem.setText(R.string.ts_open_a_mod);
            btnQueue.setVisibility(View.GONE);
        }
        btnInstall.setEnabled(size > 0 && !busy);
        btnInstall.setText(size > 0 ? getString(R.string.ts_install_n, size) : getString(R.string.ts_install));
    }

    private MaterialAlertDialogBuilder dialog() {
        return new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ValDroid_Dialog_Buttons);
    }

    // ------------------------------------------------------------------ install

    /** The instance to install into: asked every time, unless there is only one. */
    private void chooseInstance() {
        GameInstanceManager.requireSingleton().reload();
        List<GameInstance> instances = new ArrayList<>(GameInstanceManager.requireSingleton().getInstances());
        if (instances.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_LONG).show();
            return;
        }
        if (instances.size() == 1) {
            resolve(instances.get(0));
            return;
        }
        String[] names = new String[instances.size()];
        for (int i = 0; i < names.length; i++) names[i] = instances.get(i).getName();
        dialog().setTitle(R.string.ts_choose_instance)
                .setItems(names, (d, which) -> resolve(instances.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Works out the full list off the main thread, then asks. */
    private void resolve(GameInstance gi) {
        List<Pkg> wanted;
        synchronized (QUEUE) { wanted = new ArrayList<>(QUEUE.values()); }
        if (wanted.isEmpty()) return;
        File gameDir = new File(gi.getGamePath());
        Map<String, Entry> index = sByKey != null ? sByKey : new HashMap<>();
        TextView status = new TextView(requireContext());
        AlertDialog wait = progressDialog(status, getString(R.string.ts_resolving));
        setBusy(true);
        new Thread(() -> {
            List<Resolved> list = null;
            String error = null;
            try {
                Map<String, String> installed = new HashMap<>();
                for (ModManager.Mod m : ModManager.list(gameDir)) {
                    if (m.version != null) installed.put(m.name.toLowerCase(Locale.ROOT), m.version);
                }
                list = ThunderstoreClient.resolve(wanted, installed, index);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<Resolved> result = list;
            final String err = error;
            main.post(() -> {
                wait.dismiss();
                setBusy(false);
                if (!isAdded()) return;
                if (result == null) {
                    dialog().setTitle(R.string.mod_install_failed)
                            .setMessage(getString(R.string.ts_error_resolve, String.valueOf(err)))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    confirm(gi, wanted, result);
                }
            });
        }, "ThunderstoreResolve").start();
    }

    private void confirm(GameInstance gi, List<Pkg> wanted, List<Resolved> list) {
        java.util.Set<String> asked = new java.util.HashSet<>();
        for (Pkg p : wanted) asked.add(p.key());
        StringBuilder mods = new StringBuilder(), deps = new StringBuilder();
        long total = 0;
        for (Resolved r : list) {
            StringBuilder to = asked.contains(r.pkg.key()) ? mods : deps;
            to.append("• ").append(r.pkg.name.replace('_', ' ')).append(' ').append(r.pkg.version).append('\n');
            total += r.size;
        }
        String message = mods.toString().trim();
        if (deps.length() > 0) message += "\n\n" + getString(R.string.ts_also_needed) + "\n" + deps.toString().trim();
        if (total > 0) message += "\n\n" + getString(R.string.ts_total_size, size(total));
        message += "\n\n" + getString(R.string.ts_confirm_note, gi.getName());
        dialog().setTitle(getString(R.string.ts_confirm_title, list.size()))
                .setMessage(message)
                .setPositiveButton(R.string.ts_install, (d, w) -> install(gi, list))
                .setNeutralButton(R.string.ts_clear_queue, (d, w) -> {
                    synchronized (QUEUE) { QUEUE.clear(); }
                    adapter.notifyDataSetChanged();
                    updateBar();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void install(GameInstance gi, List<Resolved> list) {
        Context app = requireContext().getApplicationContext();
        File gameDir = new File(gi.getGamePath());
        File cache = new File(app.getCacheDir(), "thunderstore");
        TextView status = new TextView(requireContext());
        AlertDialog wait = progressDialog(status, "");
        setBusy(true);
        new Thread(() -> {
            List<String> done = new ArrayList<>(), failed = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Resolved r = list.get(i);
                String label = r.pkg.name.replace('_', ' ');
                String line = app.getString(R.string.ts_installing, i + 1, list.size(), label);
                main.post(() -> status.setText(line));
                File zip = null;
                try {
                    zip = ThunderstoreClient.download(r, cache);
                    ModManager.install(app, Uri.fromFile(zip), gameDir, r.pkg.toString());
                    done.add(label + " " + r.pkg.version);
                } catch (Exception e) {
                    failed.add(app.getString(R.string.ts_failed_line, label, String.valueOf(e.getMessage())));
                } finally {
                    if (zip != null) //noinspection ResultOfMethodCallIgnored
                        zip.delete();
                }
            }
            main.post(() -> {
                wait.dismiss();
                setBusy(false);
                if (failed.isEmpty()) {
                    synchronized (QUEUE) { QUEUE.clear(); }
                }
                adapter.notifyDataSetChanged();
                updateBar();
                if (!isAdded()) return;
                StringBuilder msg = new StringBuilder();
                for (String d : done) msg.append("✓ ").append(d).append('\n');
                for (String f : failed) msg.append(f).append('\n');
                if (!gi.settings().isModSupport()) msg.append('\n').append(getString(R.string.ts_mods_off));
                dialog().setTitle(failed.isEmpty() ? R.string.ts_done_title : R.string.ts_done_some)
                        .setMessage(msg.toString().trim())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "ThunderstoreInstall").start();
    }

    private void setBusy(boolean b) {
        busy = b;
        updateBar();
    }

    /** A non-cancellable dialog with a spinner and a line of text the caller updates. */
    private AlertDialog progressDialog(TextView status, String text) {
        Context c = requireContext();
        int pad = Math.round(24 * c.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.addView(new ProgressBar(c));
        status.setText(text);
        status.setPadding(pad, 0, 0, 0);
        box.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return dialog().setView(box).setCancelable(false).show();
    }
}
