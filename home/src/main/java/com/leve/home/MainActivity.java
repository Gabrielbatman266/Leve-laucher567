package com.leve.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leve Home — launcher no estilo dos celulares novos (iPhone / HyperOS), mas leve.
 *
 * Tela inicial em páginas com ícones, relógio grande, pontinhos de página, barra de
 * pesquisa e dock. Deslize para cima: gaveta com todos os apps. Deslize para baixo:
 * notificações. Segure num espaço vazio: painel de personalização.
 *
 * Leveza: ícones desenhados uma vez e guardados em cache, grade da gaveta reaproveita
 * as células, sem desfoque (blur), animações só de posição/transparência (GPU).
 */
public class MainActivity extends Activity {

    // ---------- Opções de personalização ----------
    static final String[] BG_NAMES = {"Papel de parede do celular", "Preto AMOLED", "Aurora", "Pôr do sol", "Oceano", "Grafite"};
    static final int[][] BG_COLORS = {
            null, null,
            {0xFF0B1E3A, 0xFF1C4E6E, 0xFF2E8C7A},
            {0xFF2A0F3D, 0xFF7A2E5C, 0xFFE07A4F},
            {0xFF001428, 0xFF003E6B, 0xFF0B7FAB},
            {0xFF0E0F12, 0xFF22252B, 0xFF3A3E46},
    };
    static final String[] SHAPE_NAMES = {"Estilo iPhone", "Estilo Xiaomi", "Círculo", "Original do app"};
    static final String[] SIZE_NAMES = {"Pequeno", "Médio", "Grande"};
    static final int[] SIZE_DP = {50, 58, 64};

    private static final Locale BR = new Locale("pt", "BR");
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final Interpolator EASE = new DecelerateInterpolator(1.8f);
    private static final int REQ_HOME = 7;

    // ---------- Modelo ----------
    static final class App {
        final LauncherActivityInfo info;
        final String label, search, key;
        final ComponentName cn;
        final UserHandle user;
        final boolean clone;

        App(LauncherActivityInfo info, String label, UserHandle user, long serial, boolean clone) {
            this.info = info;
            this.label = label;
            this.cn = info.getComponentName();
            this.user = user;
            this.clone = clone;
            this.search = normalize(label);
            this.key = cn.flattenToShortString() + "#" + serial;
        }
    }

    private final List<App> allApps = new ArrayList<>();
    private final Map<String, App> byKey = new HashMap<>();
    private final List<App> drawerShown = new ArrayList<>();

    private SharedPreferences prefs;
    private LauncherApps launcherApps;
    private UserManager userManager;
    private LauncherApps.Callback appsCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread workThread;
    private Handler work;
    private volatile int loadGen = 0;

    // Ícones prontos (bitmap já com o formato), limitado a ~10 MB
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    // Ajustes atuais
    private int cols, iconDp, shape, bg;
    private boolean labels, bigClock, anim = true;

    // ---------- Views ----------
    private FrameLayout root;
    private LinearLayout content, pagesRow, dots, dock;
    private Pager pager;
    private TextView clockView, dateView;
    private View clockBox;
    private FrameLayout drawer;
    private EditText drawerSearch;
    private LinearLayout suggestions;
    private TextView suggestionsTitle;
    private GridView drawerGrid;
    private DrawerAdapter drawerAdapter;
    private FrameLayout sheet;
    private LinearLayout sheetPanel;
    private PopupWindow popup;
    private GestureDetector gestures;
    private BroadcastReceiver timeReceiver;
    private int insetTop, insetBottom;
    private int lastPagerW, lastPagerH;
    private boolean drawerOpen, sheetOpen;

    // ================================================================
    // Ciclo de vida
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) getSystemService(USER_SERVICE);
        readSettings();

        workThread = new HandlerThread("leve-home");
        workThread.start();
        work = new Handler(workThread.getLooper());

        // Desenha por trás das barras do sistema (visual de tela cheia)
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(buildUi());
        applyBackground();

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || sheetOpen) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dy) < dp(60) || Math.abs(dy) < Math.abs(dx) * 1.3f) return false;
                if (!drawerOpen) {
                    if (dy < 0) openDrawer(false);
                    else expandNotifications();
                    return true;
                } else if (dy > 0 && !drawerGrid.canScrollVertically(-1)) {
                    closeDrawer();
                    return true;
                }
                return false;
            }
        });

        appsCallback = new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { loadApps(); }
            @Override public void onPackageAdded(String p, UserHandle u) { loadApps(); }
            @Override public void onPackageChanged(String p, UserHandle u) { loadApps(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { loadApps(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { loadApps(); }
        };
        launcherApps.registerCallback(appsCallback, ui);
        loadApps();

        if (!prefs.getBoolean("pediu_padrao", false) && !isDefaultHome()) {
            prefs.edit().putBoolean("pediu_padrao", true).apply();
            requestDefaultHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        anim = prefs.getBoolean("anim", true) && !(pm != null && pm.isPowerSaveMode());
        updateClock();
        timeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { updateClock(); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(timeReceiver, f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (timeReceiver != null) {
            try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
            timeReceiver = null;
        }
        dismissPopup();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        launcherApps.unregisterCallback(appsCallback);
        workThread.quit();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Botão Home: fecha tudo e volta para a primeira página
        dismissPopup();
        if (sheetOpen) closeSheet();
        if (drawerOpen) closeDrawer();
        else if (pager != null) pager.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (popup != null && popup.isShowing()) dismissPopup();
        else if (sheetOpen) closeSheet();
        else if (drawerOpen) closeDrawer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME && !isDefaultHome()) openHomeSettings();
    }

    // ================================================================
    // Ajustes
    // ================================================================

    private void readSettings() {
        cols = prefs.getInt("cols", 4);
        iconDp = SIZE_DP[clamp(prefs.getInt("size", 1), 0, 2)];
        shape = clamp(prefs.getInt("shape", 0), 0, SHAPE_NAMES.length - 1);
        bg = clamp(prefs.getInt("bg", 0), 0, BG_NAMES.length - 1);
        labels = prefs.getBoolean("labels", true);
        bigClock = prefs.getBoolean("clock", true);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void applyBackground() {
        if (bg == 0) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            root.setBackgroundColor(Color.TRANSPARENT);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } else {
            // Fundo próprio: o papel de parede nem é desenhado (economiza GPU)
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            if (bg == 1) root.setBackgroundColor(Color.BLACK);
            else root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, BG_COLORS[bg]));
        }
    }

    // ================================================================
    // Interface
    // ================================================================

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(0.8f)), stroke);
        return g;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Sombra suave no texto para ler bem sobre qualquer papel de parede. */
    private void shadow(TextView t) {
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
    }

    private View buildUi() {
        root = new FrameLayout(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ----- Relógio grande -----
        LinearLayout cb = new LinearLayout(this);
        cb.setOrientation(LinearLayout.VERTICAL);
        cb.setGravity(Gravity.CENTER_HORIZONTAL);
        cb.setPadding(0, dp(18), 0, dp(6));
        clockView = text(68, Color.WHITE);
        clockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clockView.setIncludeFontPadding(false);
        shadow(clockView);
        cb.addView(clockView);
        dateView = text(16, 0xEEFFFFFF);
        dateView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        shadow(dateView);
        cb.addView(dateView);
        cb.setOnClickListener(v -> tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        clockBox = cb;
        content.addView(cb, lp(MATCH, WRAP));

        // ----- Páginas de ícones -----
        pager = new Pager(this);
        pager.setHorizontalScrollBarEnabled(false);
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pager.setFillViewport(true);
        pagesRow = new LinearLayout(this);
        pagesRow.setOrientation(LinearLayout.HORIZONTAL);
        pager.addView(pagesRow, new FrameLayout.LayoutParams(WRAP, MATCH));
        content.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w > 0 && h > 0 && (w != lastPagerW || h != lastPagerH)) {
                lastPagerW = w;
                lastPagerH = h;
                ui.post(this::rebuildPages);
            }
        });

        // ----- Pontinhos -----
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(4), 0, dp(6));
        content.addView(dots, lp(MATCH, WRAP));

        // ----- Pesquisar -----
        TextView search = text(15, 0xEEFFFFFF);
        search.setText("⌕  Pesquisar");
        search.setGravity(Gravity.CENTER);
        search.setBackground(round(0x40FFFFFF, 18, 0x26FFFFFF));
        search.setPadding(dp(16), dp(7), dp(16), dp(7));
        search.setOnClickListener(v -> openDrawer(true));
        pressable(search);
        LinearLayout.LayoutParams sp = lp(WRAP, WRAP);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = dp(10);
        content.addView(search, sp);

        // ----- Dock -----
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setBackground(round(0x38FFFFFF, 30, 0x22FFFFFF));
        dock.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams dlp = lp(MATCH, WRAP);
        dlp.leftMargin = dp(10);
        dlp.rightMargin = dp(10);
        dlp.bottomMargin = dp(10);
        content.addView(dock, dlp);

        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(MATCH, MATCH));

        sheet = buildSheet();
        sheet.setVisibility(View.GONE);
        root.addView(sheet, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Margens das barras do sistema (notch, barra de navegação)
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
            content.setPadding(dp(8), insetTop, dp(8), insetBottom);
            drawer.setPadding(0, insetTop, 0, 0);
            drawerGrid.setPadding(dp(8), dp(4), dp(8), insetBottom + dp(16));
            sheetPanel.setPadding(dp(20), dp(12), dp(20), insetBottom + dp(16));
            return ins.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();

        clockBox.setVisibility(bigClock ? View.VISIBLE : View.GONE);
        return root;
    }

    private void updateClock() {
        if (clockView == null) return;
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("HH:mm", BR).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", BR).format(now);
        dateView.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
    }

    // ---------- Ícone (imagem + nome) ----------

    private View iconCell(final App a, boolean showLabel, final boolean inDock) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(6), 0, dp(4));
        ImageView img = new ImageView(this);
        cell.addView(img, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        loadIcon(a, img);
        if (showLabel) {
            TextView l = text(11.5f, Color.WHITE);
            l.setText(a.label);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            l.setPadding(dp(2), dp(5), dp(2), 0);
            shadow(l);
            cell.addView(l, lp(MATCH, WRAP));
        }
        cell.setOnClickListener(v -> launch(a, img));
        cell.setOnLongClickListener(v -> {
            iconMenu(a, v, inDock ? Place.DOCK : Place.HOME);
            return true;
        });
        pressable(cell);
        return cell;
    }

    private int cellHeight() {
        return dp(iconDp) + dp(10) + (labels ? dp(22) : 0);
    }

    /** Monta as páginas da tela inicial conforme o tamanho da tela. */
    private void rebuildPages() {
        if (pagesRow == null || lastPagerW == 0) return;
        int pageW = lastPagerW;
        int rows = Math.max(2, lastPagerH / (cellHeight() + dp(8)));
        int perPage = rows * cols;

        List<App> apps = new ArrayList<>();
        for (String k : listPref("home")) {
            App a = byKey.get(k);
            if (a != null) apps.add(a);
        }
        int pages = Math.max(1, (apps.size() + perPage - 1) / perPage);
        int current = pager.page();

        pagesRow.removeAllViews();
        for (int p = 0; p < pages; p++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(0, dp(6), 0, 0);
            page.setOnLongClickListener(v -> { openSheet(); return true; });
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < cols; c++) {
                    int idx = p * perPage + r * cols + c;
                    View cell = idx < apps.size() ? iconCell(apps.get(idx), labels, false) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0, cellHeight(), 1f));
                }
                LinearLayout.LayoutParams rp = lp(MATCH, WRAP);
                rp.bottomMargin = dp(8);
                page.addView(row, rp);
            }
            pagesRow.addView(page, new LinearLayout.LayoutParams(pageW, MATCH));
        }
        pager.setPageWidth(pageW);
        rebuildDots(pages);
        int target = Math.min(current, pages - 1);
        pager.post(() -> pager.scrollTo(target * pageW, 0));
        updateDots(target);
    }

    private void rebuildDots(int n) {
        dots.removeAllViews();
        if (n <= 1) return;
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            d.setBackground(round(Color.WHITE, 4, 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(6), dp(6));
            p.leftMargin = dp(4);
            p.rightMargin = dp(4);
            dots.addView(d, p);
        }
    }

    void updateDots(int page) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setAlpha(i == page ? 1f : 0.35f);
        }
    }

    private void rebuildDock() {
        dock.removeAllViews();
        List<App> apps = new ArrayList<>();
        for (String k : listPref("dock")) {
            App a = byKey.get(k);
            if (a != null && apps.size() < cols) apps.add(a);
        }
        dock.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);
        for (App a : apps) {
            dock.addView(iconCell(a, false, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
    }

    // ================================================================
    // Gaveta de apps (deslizar para cima)
    // ================================================================

    private FrameLayout buildDrawer() {
        FrameLayout d = new FrameLayout(this);
        d.setBackgroundColor(0xF0101114);
        d.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        d.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Alça
        View handle = new View(this);
        handle.setBackground(round(0x55FFFFFF, 3, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(10);
        col.addView(handle, hp);

        drawerSearch = new EditText(this);
        drawerSearch.setHint("⌕  Pesquisar apps");
        drawerSearch.setHintTextColor(0x99FFFFFF);
        drawerSearch.setTextColor(Color.WHITE);
        drawerSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        drawerSearch.setSingleLine(true);
        drawerSearch.setImeOptions(EditorInfo.IME_ACTION_GO);
        drawerSearch.setBackground(round(0x26FFFFFF, 14, 0));
        drawerSearch.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP);
        sp.setMargins(dp(16), dp(12), dp(16), dp(8));
        col.addView(drawerSearch, sp);
        drawerSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filterDrawer(); }
        });
        drawerSearch.setOnEditorActionListener((v, id, e) -> {
            if (!drawerShown.isEmpty()) launch(drawerShown.get(0), null);
            return true;
        });

        suggestionsTitle = text(13, 0x99FFFFFF);
        suggestionsTitle.setText("SUGESTÕES");
        suggestionsTitle.setLetterSpacing(0.12f);
        suggestionsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        suggestionsTitle.setPadding(dp(20), dp(6), 0, dp(2));
        col.addView(suggestionsTitle);
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.HORIZONTAL);
        suggestions.setPadding(dp(8), 0, dp(8), dp(4));
        col.addView(suggestions, lp(MATCH, WRAP));

        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(cols);
        drawerGrid.setVerticalScrollBarEnabled(false);
        drawerGrid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        drawerGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        drawerGrid.setClipToPadding(false);
        drawerGrid.setFastScrollEnabled(false);
        drawerAdapter = new DrawerAdapter();
        drawerGrid.setAdapter(drawerAdapter);
        drawerGrid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {
                if (s != SCROLL_STATE_IDLE) hideKeyboard();
            }
            @Override public void onScroll(android.widget.AbsListView v, int a, int b, int c) { }
        });
        col.addView(drawerGrid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return d;
    }

    private void openDrawer(boolean focusSearch) {
        if (drawerOpen) return;
        drawerOpen = true;
        dismissPopup();
        rebuildSuggestions();
        filterDrawer();
        drawerGrid.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        if (anim) {
            drawer.setTranslationY(dp(80));
            drawer.setAlpha(0f);
            drawer.animate().translationY(0).alpha(1f).setDuration(230).setInterpolator(EASE).start();
            content.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(230).setInterpolator(EASE).start();
        } else {
            content.setAlpha(0f);
        }
        if (focusSearch) {
            drawerSearch.requestFocus();
            ui.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(drawerSearch, 0);
            }, anim ? 180 : 0);
        }
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        if (drawerSearch.length() > 0) drawerSearch.setText("");
        if (anim) {
            drawer.animate().translationY(dp(80)).alpha(0f).setDuration(190).setInterpolator(EASE)
                    .withEndAction(() -> { if (!drawerOpen) drawer.setVisibility(View.GONE); }).start();
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(EASE).start();
        } else {
            drawer.setVisibility(View.GONE);
            content.setAlpha(1f);
        }
    }

    private void filterDrawer() {
        drawerShown.clear();
        String q = normalize(drawerSearch.getText().toString());
        if (q.isEmpty()) {
            drawerShown.addAll(allApps);
        } else {
            List<App> contains = new ArrayList<>();
            for (App a : allApps) {
                if (a.search.startsWith(q)) drawerShown.add(a);
                else if (a.search.contains(q)) contains.add(a);
            }
            drawerShown.addAll(contains);
        }
        boolean showSug = q.isEmpty() && suggestions.getChildCount() > 0;
        suggestions.setVisibility(showSug ? View.VISIBLE : View.GONE);
        suggestionsTitle.setVisibility(showSug ? View.VISIBLE : View.GONE);
        drawerAdapter.notifyDataSetChanged();
    }

    /** Os apps que você mais abre aparecem no topo da gaveta. */
    private void rebuildSuggestions() {
        suggestions.removeAllViews();
        List<App> top = new ArrayList<>();
        for (App a : allApps) if (prefs.getInt("u:" + a.key, 0) > 0) top.add(a);
        Collections.sort(top, (x, y) -> prefs.getInt("u:" + y.key, 0) - prefs.getInt("u:" + x.key, 0));
        for (int i = 0; i < Math.min(cols, top.size()); i++) {
            suggestions.addView(drawerCell(top.get(i), null), new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        for (int i = top.size(); i < cols && !top.isEmpty(); i++) {
            suggestions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    /** Célula da gaveta (reaproveitada pela grade para gastar pouca memória). */
    private View drawerCell(final App a, View recycle) {
        LinearLayout cell = (LinearLayout) recycle;
        if (cell == null) {
            cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(10), 0, dp(8));
            ImageView img = new ImageView(this);
            cell.addView(img, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
            TextView l = text(11.5f, Color.WHITE);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            l.setPadding(dp(2), dp(5), dp(2), 0);
            cell.addView(l, lp(MATCH, WRAP));
            pressable(cell);
        }
        final ImageView img = (ImageView) cell.getChildAt(0);
        loadIcon(a, img);
        ((TextView) cell.getChildAt(1)).setText(a.clone ? a.label + " ²" : a.label);
        cell.setOnClickListener(v -> launch(a, img));
        cell.setOnLongClickListener(v -> { iconMenu(a, v, Place.DRAWER); return true; });
        return cell;
    }

    private final class DrawerAdapter extends BaseAdapter {
        @Override public int getCount() { return drawerShown.size(); }
        @Override public Object getItem(int p) { return drawerShown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup parent) { return drawerCell(drawerShown.get(p), v); }
    }

    // ================================================================
    // Menu do ícone (segurar o dedo)
    // ================================================================

    enum Place { HOME, DOCK, DRAWER }

    private void iconMenu(final App a, View anchor, final Place where) {
        final List<String> home = listPref("home");
        final List<String> dockL = listPref("dock");
        List<String> opts = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        if (where == Place.HOME) {
            final int i = home.indexOf(a.key);
            if (i > 0) { opts.add("◂  Mover para trás"); acts.add(() -> { Collections.swap(home, i, i - 1); saveList("home", home); rebuildPages(); }); }
            if (i >= 0 && i < home.size() - 1) { opts.add("▸  Mover para frente"); acts.add(() -> { Collections.swap(home, i, i + 1); saveList("home", home); rebuildPages(); }); }
            opts.add("✕  Tirar da tela inicial"); acts.add(() -> { home.remove(a.key); saveList("home", home); rebuildPages(); });
        } else if (where == Place.DRAWER) {
            if (!home.contains(a.key)) {
                opts.add("＋  Adicionar à tela inicial");
                acts.add(() -> {
                    home.add(a.key);
                    saveList("home", home);
                    rebuildPages();
                    toast("Adicionado à tela inicial");
                });
            }
        }
        if (where == Place.DOCK) {
            final int i = dockL.indexOf(a.key);
            if (i > 0) { opts.add("◂  Mover para a esquerda"); acts.add(() -> { Collections.swap(dockL, i, i - 1); saveList("dock", dockL); rebuildDock(); }); }
            if (i >= 0 && i < dockL.size() - 1) { opts.add("▸  Mover para a direita"); acts.add(() -> { Collections.swap(dockL, i, i + 1); saveList("dock", dockL); rebuildDock(); }); }
            opts.add("✕  Tirar do dock"); acts.add(() -> { dockL.remove(a.key); saveList("dock", dockL); rebuildDock(); });
        } else if (!dockL.contains(a.key)) {
            opts.add("⬓  Colocar no dock");
            acts.add(() -> {
                if (dockL.size() >= cols) {
                    toast("O dock está cheio (" + cols + " apps). Tire um antes.");
                    return;
                }
                dockL.add(a.key);
                saveList("dock", dockL);
                rebuildDock();
            });
        }
        opts.add("ⓘ  Informações do app"); acts.add(() -> openAppInfo(a));
        if (!a.clone) {
            opts.add("🗑  Desinstalar");
            acts.add(() -> tryStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.cn.getPackageName()))));
        }
        showPopup(anchor, a.label, opts, acts);
    }

    /** Menu flutuante arredondado perto do ícone. */
    private void showPopup(View anchor, String title, List<String> opts, final List<Runnable> acts) {
        dismissPopup();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(0xF2202226, 16, 0x22FFFFFF));
        box.setPadding(0, dp(6), 0, dp(6));
        TextView t = text(12.5f, 0x99FFFFFF);
        t.setText(title);
        t.setPadding(dp(16), dp(6), dp(16), dp(4));
        box.addView(t);
        for (int i = 0; i < opts.size(); i++) {
            final int idx = i;
            TextView o = text(15, Color.WHITE);
            o.setText(opts.get(i));
            o.setPadding(dp(16), dp(11), dp(24), dp(11));
            o.setOnClickListener(v -> { dismissPopup(); acts.get(idx).run(); });
            box.addView(o, lp(MATCH, WRAP));
        }
        box.measure(View.MeasureSpec.makeMeasureSpec(dp(260), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        popup = new PopupWindow(box, Math.max(dp(200), box.getMeasuredWidth()), WRAP, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        if (anim) popup.setAnimationStyle(android.R.style.Animation_Dialog);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int h = box.getMeasuredHeight();
        int screenH = root.getHeight();
        int y = loc[1] + anchor.getHeight() + dp(4);
        if (y + h > screenH - insetBottom) y = Math.max(insetTop, loc[1] - h - dp(4));
        int x = Math.max(dp(8), Math.min(loc[0], root.getWidth() - popup.getWidth() - dp(8)));
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
    }

    private void dismissPopup() {
        if (popup != null && popup.isShowing()) popup.dismiss();
        popup = null;
    }

    // ================================================================
    // Painel de personalização (segurar num espaço vazio)
    // ================================================================

    private FrameLayout buildSheet() {
        FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(0x66000000);
        s.setOnClickListener(v -> closeSheet());
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sheetPanel = new LinearLayout(this);
        sheetPanel.setOrientation(LinearLayout.VERTICAL);
        sheetPanel.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF7181A1E);
        float r = dp(26);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetPanel.setBackground(g);
        sc.addView(sheetPanel);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        s.addView(sc, p);
        return s;
    }

    private void fillSheet() {
        sheetPanel.removeAllViews();
        View handle = new View(this);
        handle.setBackground(round(0x55FFFFFF, 3, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(10);
        sheetPanel.addView(handle, hp);
        TextView title = text(20, Color.WHITE);
        title.setText("Personalizar");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, dp(8));
        sheetPanel.addView(title);

        option("Fundo", BG_NAMES[bg], () -> { prefs.edit().putInt("bg", (bg + 1) % BG_NAMES.length).apply(); refreshAll(false); });
        option("Formato dos ícones", SHAPE_NAMES[shape], () -> { prefs.edit().putInt("shape", (shape + 1) % SHAPE_NAMES.length).apply(); refreshAll(true); });
        option("Tamanho dos ícones", SIZE_NAMES[clamp(prefs.getInt("size", 1), 0, 2)],
                () -> { prefs.edit().putInt("size", (clamp(prefs.getInt("size", 1), 0, 2) + 1) % 3).apply(); refreshAll(true); });
        option("Colunas", cols + " por linha", () -> { prefs.edit().putInt("cols", cols == 4 ? 5 : 4).apply(); refreshAll(false); });
        option("Nomes dos apps", labels ? "Mostrar" : "Ocultar", () -> { prefs.edit().putBoolean("labels", !labels).apply(); refreshAll(false); });
        option("Relógio grande", bigClock ? "Mostrar" : "Ocultar", () -> { prefs.edit().putBoolean("clock", !bigClock).apply(); refreshAll(false); });
        option("Animações", prefs.getBoolean("anim", true) ? "Ligadas" : "Desligadas", () -> {
            boolean on = !prefs.getBoolean("anim", true);
            prefs.edit().putBoolean("anim", on).apply();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            anim = on && !(pm != null && pm.isPowerSaveMode());
            fillSheet();
        });
        link("Trocar papel de parede do celular", () -> {
            closeSheet();
            if (!startQuiet(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede"))) {
                tryStart(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            }
        });
        link("Tela inicial padrão", () -> { closeSheet(); openHomeSettings(); });
        link("Atualizar o Leve Home  (v" + Updater.installedVersion(this) + ")", () -> {
            closeSheet();
            new Updater(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).check();
        });
        TextView tip = text(12.5f, 0x88FFFFFF);
        tip.setText("Dicas: deslize para cima para ver todos os apps, para baixo para as notificações. "
                + "Segure num ícone para mover, colocar no dock ou desinstalar.");
        tip.setPadding(dp(4), dp(14), dp(4), 0);
        sheetPanel.addView(tip);
    }

    private void option(String label, String value, Runnable onTap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(0x14FFFFFF, 14, 0));
        r.setPadding(dp(16), dp(13), dp(16), dp(13));
        TextView l = text(15, Color.WHITE);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView v = text(14, 0xFF9CC2FF);
        v.setText(value + "  ›");
        r.addView(v, lp(WRAP, WRAP));
        r.setOnClickListener(x -> onTap.run());
        pressable(r);
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = dp(8);
        sheetPanel.addView(r, p);
    }

    private void link(String label, Runnable onTap) {
        TextView t = text(15, 0xFF9CC2FF);
        t.setText(label + "  ›");
        t.setBackground(round(0x14FFFFFF, 14, 0));
        t.setPadding(dp(16), dp(13), dp(16), dp(13));
        t.setOnClickListener(x -> onTap.run());
        pressable(t);
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = dp(8);
        sheetPanel.addView(t, p);
    }

    private void openSheet() {
        if (sheetOpen) return;
        sheetOpen = true;
        dismissPopup();
        fillSheet();
        sheet.setVisibility(View.VISIBLE);
        if (anim) {
            sheet.setAlpha(0f);
            sheet.animate().alpha(1f).setDuration(180).start();
            View panel = (View) sheetPanel.getParent();
            panel.setTranslationY(dp(260));
            panel.animate().translationY(0).setDuration(260).setInterpolator(EASE).start();
        }
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        if (anim) {
            View panel = (View) sheetPanel.getParent();
            panel.animate().translationY(dp(260)).setDuration(180).setInterpolator(EASE).start();
            sheet.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> { if (!sheetOpen) sheet.setVisibility(View.GONE); }).start();
        } else {
            sheet.setVisibility(View.GONE);
        }
    }

    /** Aplica os ajustes na hora, sem reiniciar o launcher. */
    private void refreshAll(boolean iconsChanged) {
        readSettings();
        if (iconsChanged) icons.evictAll();
        applyBackground();
        clockBox.setVisibility(bigClock ? View.VISIBLE : View.GONE);
        drawerGrid.setNumColumns(cols);
        lastPagerW = 0;
        pager.requestLayout();
        rebuildDock();
        drawerAdapter.notifyDataSetChanged();
        fillSheet();
        ui.post(() -> {
            lastPagerW = pager.getWidth();
            lastPagerH = pager.getHeight();
            rebuildPages();
        });
    }

    // ================================================================
    // Apps
    // ================================================================

    private void loadApps() {
        final int gen = ++loadGen;
        work.post(() -> {
            final List<App> result = new ArrayList<>();
            String self = getPackageName();
            UserHandle me = Process.myUserHandle();
            try {
                for (UserHandle u : userManager.getUserProfiles()) {
                    long serial = userManager.getSerialNumberForUser(u);
                    for (LauncherActivityInfo info : launcherApps.getActivityList(null, u)) {
                        if (self.equals(info.getComponentName().getPackageName())) continue;
                        CharSequence l = info.getLabel();
                        String name = l == null ? info.getComponentName().getPackageName() : l.toString().trim();
                        result.add(new App(info, name, u, serial, !u.equals(me)));
                    }
                }
            } catch (Exception ignored) { }
            final Collator col = Collator.getInstance(BR);
            col.setStrength(Collator.PRIMARY);
            Collections.sort(result, (a, b) -> {
                int c = col.compare(a.label, b.label);
                return c != 0 ? c : (a.clone ? 1 : 0) - (b.clone ? 1 : 0);
            });
            ui.post(() -> {
                if (gen != loadGen || isFinishing()) return;
                allApps.clear();
                allApps.addAll(result);
                byKey.clear();
                for (App a : allApps) byKey.put(a.key, a);
                if (!prefs.getBoolean("init", false) && !allApps.isEmpty()) createDefaults();
                rebuildDock();
                rebuildPages();
                if (drawerOpen) filterDrawer();
            });
        });
    }

    /** Primeira vez: dock com Telefone, Mensagens, Navegador e Câmera; tela com os apps mais comuns. */
    private void createDefaults() {
        List<String> dockL = new ArrayList<>();
        addPkg(dockL, defaultPkg(new Intent(Intent.ACTION_DIAL)));
        String sms = null;
        try { sms = Telephony.Sms.getDefaultSmsPackage(this); } catch (Exception ignored) { }
        addPkg(dockL, sms);
        addPkg(dockL, defaultPkg(new Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))));
        addPkg(dockL, defaultPkg(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));

        List<String> home = new ArrayList<>();
        String[] wanted = {"whatsapp", "instagram", "youtube", "tiktok", "leve music", "spotify", "ymusic",
                "facebook", "threads", "kwai", "galeria", "gallery", "fotos", "maps", "gmail", "play store",
                "chatgpt", "claude", "netflix", "nubank", "calculadora", "relogio", "clock", "configura", "settings"};
        for (String w : wanted) {
            for (App a : allApps) {
                if (!a.clone && a.search.contains(w) && !home.contains(a.key) && !dockL.contains(a.key)) {
                    home.add(a.key);
                    break;
                }
            }
        }
        saveList("dock", dockL);
        saveList("home", home);
        prefs.edit().putBoolean("init", true).apply();
    }

    private String defaultPkg(Intent i) {
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                return ri.activityInfo.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void addPkg(List<String> list, String pkg) {
        if (pkg == null) return;
        for (App a : allApps) {
            if (!a.clone && a.cn.getPackageName().equals(pkg)) {
                if (!list.contains(a.key)) list.add(a.key);
                return;
            }
        }
    }

    private List<String> listPref(String name) {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(name, "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void saveList(String name, List<String> list) {
        prefs.edit().putString(name, TextUtils.join("\n", list)).apply();
    }

    private void launch(App a, View from) {
        Bundle opts = null;
        Rect bounds = null;
        if (from != null && anim) {
            // Abre "crescendo" a partir do ícone, como nos celulares novos
            opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            int[] loc = new int[2];
            from.getLocationOnScreen(loc);
            bounds = new Rect(loc[0], loc[1], loc[0] + from.getWidth(), loc[1] + from.getHeight());
        }
        try {
            launcherApps.startMainActivity(a.cn, a.user, bounds, opts);
        } catch (Exception e) {
            toast("Não foi possível abrir");
            return;
        }
        prefs.edit().putInt("u:" + a.key, prefs.getInt("u:" + a.key, 0) + 1).apply();
        if (drawerOpen) ui.postDelayed(this::closeDrawer, 300);
    }

    private void openAppInfo(App a) {
        try {
            launcherApps.startAppDetailsActivity(a.cn, a.user, null, null);
        } catch (Exception e) {
            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.cn.getPackageName(), null)));
        }
    }

    // ================================================================
    // Ícones com formato (desenhados uma vez, fora da tela principal)
    // ================================================================

    private void loadIcon(final App a, final ImageView view) {
        final String ck = a.key + "@" + shape + "@" + iconDp;
        view.setTag(ck);
        Bitmap b = icons.get(ck);
        if (b != null) {
            view.setImageBitmap(b);
            return;
        }
        view.setImageDrawable(null);
        final int px = dp(iconDp);
        final int shp = shape;
        work.post(() -> {
            Bitmap bmp;
            try {
                bmp = renderIcon(a, px, shp);
            } catch (Exception e) {
                bmp = null;
            }
            if (bmp != null) icons.put(ck, bmp);
            final Bitmap fin = bmp;
            ui.post(() -> { if (ck.equals(view.getTag()) && fin != null) view.setImageBitmap(fin); });
        });
    }

    private Path shapePath(int px, int shp) {
        Path p = new Path();
        RectF r = new RectF(0, 0, px, px);
        if (shp == 2) p.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW);
        else {
            float rad = shp == 0 ? px * 0.225f : px * 0.32f;
            p.addRoundRect(r, rad, rad, Path.Direction.CW);
        }
        return p;
    }

    private Bitmap renderIcon(App a, int px, int shp) {
        int dpi = getResources().getDisplayMetrics().densityDpi;
        Drawable d = a.clone ? a.info.getBadgedIcon(dpi) : a.info.getIcon(dpi);
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (shp == 3 || a.clone) {
            d.setBounds(0, 0, px, px);
            d.draw(c);
            return b;
        }
        Path path = shapePath(px, shp);
        if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            c.save();
            c.clipPath(path);
            int ex = px / 4; // as camadas têm 108dp, só 72dp aparecem
            Drawable back = ad.getBackground(), fore = ad.getForeground();
            if (back != null) { back.setBounds(-ex, -ex, px + ex, px + ex); back.draw(c); }
            if (fore != null) { fore.setBounds(-ex, -ex, px + ex, px + ex); fore.draw(c); }
            c.restore();
        } else {
            // Ícone antigo: fundo branco no formato escolhido, como nos celulares novos
            Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(Color.WHITE);
            c.drawPath(path, white);
            int in = (int) (px * 0.14f);
            d.setBounds(in, in, px - in, px - in);
            d.draw(c);
        }
        return b;
    }

    // ================================================================
    // Gestos e utilidades
    // ================================================================

    /** Deslizar para baixo: abre a barra de notificações. */
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) { }
    }

    /** Botão afunda um pouquinho ao tocar. */
    private void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            if (!anim) return false;
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start();
            }
            return false;
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && drawerSearch != null) imm.hideSoftInputFromWindow(drawerSearch.getWindowToken(), 0);
        if (drawerSearch != null) drawerSearch.clearFocus();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private boolean startQuiet(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryStart(Intent i) {
        if (startQuiet(i)) return true;
        toast("Não foi possível abrir");
        return false;
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            } catch (Exception ignored) { }
        }
        openHomeSettings();
    }

    private void openHomeSettings() {
        Toast.makeText(this, "Escolha \"Leve Home\"", Toast.LENGTH_LONG).show();
        if (startQuiet(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        tryStart(new Intent(Settings.ACTION_SETTINGS));
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // ================================================================
    // Páginas com "encaixe" (snap), como nos celulares novos
    // ================================================================

    final class Pager extends HorizontalScrollView {
        private int pageW = 1;
        private boolean flung;

        Pager(Context c) { super(c); }

        void setPageWidth(int w) { pageW = Math.max(1, w); }

        int page() { return Math.round(getScrollX() / (float) pageW); }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) flung = false;
            boolean r = super.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung) snapTo(page());
            return r;
        }

        @Override
        public void fling(int velocityX) {
            flung = true;
            int cur = (int) Math.floor(getScrollX() / (float) pageW);
            int target = velocityX > 0 ? cur + 1 : cur;
            snapTo(target);
        }

        private void snapTo(int p) {
            int max = Math.max(0, pagesRow.getChildCount() - 1);
            p = Math.max(0, Math.min(max, p));
            smoothScrollTo(p * pageW, 0);
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            updateDots(page());
        }
    }
}
