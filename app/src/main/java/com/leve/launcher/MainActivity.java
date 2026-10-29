package com.leve.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Leve Launcher v10 — tela inicial minimalista com tema espacial.
 *
 * Abas (toque no nome ou deslize para os lados):
 *   Essenciais  – seus atalhos organizados + controle de música
 *   Novos       – apps baixados nos últimos 30 dias
 *   Frequentes  – os apps que você mais abre
 *   Todos       – todos os apps (inclusive clones), organizados por A–Z,
 *                 categoria, tamanho ou só jogos
 *   Bateria     – nível, consumo, tempo restante e quais apps mais gastaram hoje
 *   Sistema     – RAM, CPU, temperatura, armazenamento
 *
 * Barra fixa embaixo: Telefone, Mensagens, Câmera, Calculadora e Galeria
 * (segure o dedo num botão para trocar por outro app).
 *
 * Economia: fundo preto puro, sem ícones, sem widgets, sem bibliotecas.
 * As medições e a animação do fundo só rodam com a tela inicial visível,
 * e a animação desliga sozinha no modo economia de bateria.
 */
public class MainActivity extends Activity {

    // ---------- Cores ----------
    static final int BG = 0xFF000000;
    static final int TEXT = 0xFFEDEFF7;
    static final int MUTED = 0xFF7C8199;
    static final int FAINT = 0xFF3A3E52;
    static final int ACCENT = 0xFF9DB0FF;
    static final int SURFACE = 0xFF0B0C14;
    static final int LINE = 0xFF1C1F2E;

    private static final int MAX_FREQUENT = 12;
    private static final int REQ_HOME = 7;
    private static final long STATS_EVERY_MS = 3000;

    private static final int TAB_ESS = 0, TAB_NEW = 1, TAB_FREQ = 2, TAB_ALL = 3, TAB_BAT = 4, TAB_SYS = 5;
    private static final String[] TAB_NAMES = {"Essenciais", "Novos", "Frequentes", "Todos", "Bateria", "Sistema"};
    private static final int TABS = 6;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NEW_BADGE_MS = 3 * DAY;
    private static final long NEW_TAB_MS = 30 * DAY;

    // Barra de baixo: atalhos fixos (dá para trocar segurando o dedo)
    private static final String[] DOCK_SYMBOL = {"☎", "✉", "◎", "⊞", "▦"};
    private static final String[] DOCK_NAME = {"Telefone", "Mensagens", "Câmera", "Calculadora", "Galeria"};
    private static final long USAGE_EVERY_MS = 30000;

    private static final Locale BR = new Locale("pt", "BR");
    private static final String FLOW_URL = "https://labs.google/fx/tools/flow";

    // ---------- Modelos ----------

    private static final class App {
        final String label, search, key;
        final ComponentName component;
        final UserHandle user;
        final boolean clone;
        long installed;
        long size;            // tamanho em bytes (app; + dados se o "Acesso ao uso" estiver liberado)
        String cat = "Outros";
        boolean game;

        App(String label, ComponentName component, UserHandle user, long serial, boolean clone) {
            this.label = label;
            this.component = component;
            this.user = user;
            this.clone = clone;
            this.search = normalize(label);
            this.key = component.flattenToShortString() + "#" + serial;
        }

        String display() { return clone ? label + "  · clone" : label; }

        boolean isNew() { return installed > 0 && System.currentTimeMillis() - installed < NEW_BADGE_MS; }
    }

    /** Item da aba Essenciais: um app ou um site. */
    private static final class Ess {
        final String key, label, url;
        final App app;
        Ess(String key, App app) { this.key = key; this.app = app; this.label = app.display(); this.url = null; }
        Ess(String key, String label, String url) { this.key = key; this.app = null; this.label = label; this.url = url; }
    }

    private static final class Stats {
        long ramUsed, ramTotal, storFree, storTotal;
        int cpuPct = -1, batt = -1, voltage = -1, currentMa = Integer.MIN_VALUE;
        float cpuTemp = Float.NaN, battTemp = Float.NaN;
        boolean charging, full;
        long chargeCounterUah = -1, chargeTimeMs = -1;
        String health = "—";
    }

    // ---------- Estado ----------

    private final List<App> allApps = new ArrayList<>();
    private final Map<String, App> byKey = new HashMap<>();
    private final List<Object> rows = new ArrayList<>();
    private final List<Ess> essItems = new ArrayList<>();
    private final Map<String, Integer> usage = new HashMap<>();

    private SharedPreferences prefs;
    private LauncherApps launcherApps;
    private UserManager userManager;
    private LauncherApps.Callback appsCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread statsThread;
    private Handler statsHandler;
    private boolean resumed;
    private volatile int loadGeneration = 0;
    private int tab = TAB_ESS;

    // ---------- Views ----------
    private StarsView stars;
    private TextView defaultBanner, statsLine, emptyHint, essHint, nowPlaying, animToggle;
    private final TextView[] tabViews = new TextView[TABS];
    private HorizontalScrollView tabScroll;
    private View pageEss, pageApps, pageBat, pageSys;
    private EditText searchBox;
    private ListView list, essList;
    private RowAdapter adapter;
    private EssAdapter essAdapter;
    private GestureDetector swipe;
    // Sistema
    private TextView sRam, sStor, sCpu, sCpuTemp, sUptime;
    private BarView barRam, barCpu;
    // Bateria
    private TextView bLevel, bStatus, bRemain, bCurrent, bVolt, bHealth, bTemp, usagePerm, usageNote;
    private BarView barBatt;
    private LinearLayout usageBox;
    private double avgMa = -1;
    private long lastUsageAt = 0;

    // ================================================================
    // Ciclo de vida
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("leve", MODE_PRIVATE);
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) getSystemService(USER_SERVICE);
        loadUsage();
        sortMode = Math.max(0, Math.min(3, prefs.getInt("ordem", SORT_AZ)));
        setContentView(buildUi());

        statsThread = new HandlerThread("leve-stats");
        statsThread.start();
        statsHandler = new Handler(statsThread.getLooper());

        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(70) && Math.abs(dx) > Math.abs(dy) * 1.6f
                        && Math.abs(vx) > dp(250)) {
                    selectTab(tab + (dx < 0 ? 1 : -1));
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
        if (launcherApps != null) launcherApps.registerCallback(appsCallback, ui);

        loadApps();

        if (!prefs.getBoolean("pediu_padrao", false) && !isDefaultHome()) {
            prefs.edit().putBoolean("pediu_padrao", true).apply();
            requestDefaultHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastUsageAt = 0;
        updateMusicSettings();
        defaultBanner.setVisibility(isDefaultHome() ? View.GONE : View.VISIBLE);
        ui.removeCallbacks(tick);
        ui.post(tick);
        updateAnimation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        stars.setRunning(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (launcherApps != null && appsCallback != null) launcherApps.unregisterCallback(appsCallback);
        if (statsThread != null) statsThread.quit();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetHome();
    }

    @Override
    public void onBackPressed() {
        resetHome();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME && !isDefaultHome()) openHomeSettings();
    }

    // ================================================================
    // Interface
    // ================================================================

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(22));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        stars = new StarsView(this);
        frame.addView(stars, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), 0);
        frame.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));

        // --- Aviso de tela inicial padrão ---
        defaultBanner = text(14, ACCENT);
        defaultBanner.setText("✦  Toque para tornar o Leve sua tela inicial");
        defaultBanner.setBackground(pill(0xFF0D1024, 0xFF2B3566));
        defaultBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        defaultBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDefaultHome(); }
        });
        LinearLayout.LayoutParams bp = lp(MATCH, WRAP);
        bp.bottomMargin = dp(14);
        root.addView(defaultBanner, bp);

        // --- Relógio, data e medidores ---
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(TEXT);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)); }
        });
        root.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextColor(MUTED);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setLetterSpacing(0.04f);
        root.addView(date);

        statsLine = text(13, ACCENT);
        statsLine.setText("RAM —  ·  CPU —  ·  Bateria —");
        statsLine.setLetterSpacing(0.03f);
        statsLine.setPadding(0, dp(8), 0, 0);
        statsLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(TAB_SYS); }
        });
        root.addView(statsLine);

        // --- Acesso rápido ---
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qp = lp(MATCH, WRAP);
        qp.topMargin = dp(18);
        root.addView(quick, qp);
        quick.addView(quickButton("◌  Limpar", new View.OnClickListener() {
            @Override public void onClick(View v) { openCleaner(); }
        }), quickParams(false));
        quick.addView(quickButton("⚙  Config", new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(new Intent(Settings.ACTION_SETTINGS)); }
        }), quickParams(false));
        quick.addView(quickButton("ϟ  Bateria", new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(TAB_BAT); }
        }), quickParams(true));

        // --- Abas ---
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabs);
        LinearLayout.LayoutParams tp = lp(MATCH, WRAP);
        tp.topMargin = dp(20);
        root.addView(tabScroll, tp);
        for (int i = 0; i < TABS; i++) {
            final int which = i;
            TextView t = text(14, FAINT);
            t.setText(TAB_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(8), dp(20), dp(8));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(which); }
            });
            tabViews[i] = t;
            tabs.addView(t, lp(WRAP, WRAP));
        }

        // --- Páginas ---
        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pageEss = buildEssPage();
        pageApps = buildAppsPage();
        pageBat = buildBatPage();
        pageSys = buildSysPage();
        pages.addView(pageEss, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageApps, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageBat, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageSys, new FrameLayout.LayoutParams(MATCH, MATCH));

        // --- Barra fixa embaixo com os essenciais do celular ---
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(pill(SURFACE, LINE));
        dock.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams dlp = lp(MATCH, WRAP);
        dlp.topMargin = dp(8);
        dlp.bottomMargin = dp(14);
        root.addView(dock, dlp);
        for (int i = 0; i < DOCK_NAME.length; i++) {
            dockViews[i] = dockButton(i);
            dock.addView(dockViews[i], new LinearLayout.LayoutParams(0, WRAP, 1f));
        }

        showTab(false);
        return frame;
    }

    private final LinearLayout[] dockViews = new LinearLayout[5];

    // ---------- Organizar apps ----------
    private static final int SORT_AZ = 0, SORT_CAT = 1, SORT_SIZE = 2, SORT_GAMES = 3;
    private static final String[] SORT_NAMES = {"A–Z", "Categorias", "Tamanho", "Jogos"};
    private static final String[] CATEGORIES = {
            "Jogos", "Conversas", "Redes sociais", "Vídeo e TV", "Música e áudio", "Fotos e câmera",
            "Bancos e pagamentos", "Compras", "Navegadores", "Mapas e transporte", "Produtividade",
            "Notícias e leitura", "Ferramentas", "Sistema", "Outros"};
    private android.widget.HorizontalScrollView sortBar;
    private final TextView[] sortChips = new TextView[SORT_NAMES.length];
    private int sortMode = SORT_AZ;

    private static long apkSize(android.content.pm.ApplicationInfo ai) {
        long total = 0;
        try {
            if (ai.sourceDir != null) total += new java.io.File(ai.sourceDir).length();
            if (ai.splitSourceDirs != null) {
                for (String sp : ai.splitSourceDirs) total += new java.io.File(sp).length();
            }
        } catch (Exception ignored) { }
        return total;
    }

    @SuppressWarnings("deprecation")
    private static boolean isGame(android.content.pm.ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT >= 26 && ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) return true;
        return (ai.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** Categoria pelo que o próprio app informa + palavras no nome. */
    private static String categoryFor(android.content.pm.ApplicationInfo ai, String name, String pkg,
                                      boolean system, boolean game) {
        if (game) return "Jogos";
        String n = name + " " + pkg.toLowerCase(Locale.ROOT);
        if (has(n, "whatsapp", "telegram", "messenger", "signal", "discord", "mensagens", "messages", "sms"))
            return "Conversas";
        if (has(n, "instagram", "facebook", "threads", "tiktok", "kwai", "twitter", "x.android", "snapchat",
                "pinterest", "reddit", "linkedin", "bluesky"))
            return "Redes sociais";
        if (name.equals("inter") || name.startsWith("banco inter") || name.startsWith("inter&")
                || name.equals("c6") || name.startsWith("c6 ")
                || has(n, "nubank", "itau", "bradesco", "caixa", "banco", "santander", "picpay", "mercado pago",
                "sicredi", "sicoob", "pagbank", "pagseguro", "neon", "bank", "wallet", "carteira"))
            return "Bancos e pagamentos";
        if (has(n, "shopee", "mercado livre", "amazon", "aliexpress", "shein", "temu", "magalu", "americanas",
                "ifood", "rappi", "olx", "casas bahia"))
            return "Compras";
        if (has(n, "chrome", "brave", "firefox", "opera", "edge", "navegador", "browser", "duckduckgo", "samsung internet"))
            return "Navegadores";
        if (has(n, "youtube", "netflix", "prime video", "disney", "globoplay", "twitch", "vlc", "player",
                "video", " tv") || name.equals("max") || name.endsWith(" tv"))
            return "Vídeo e TV";
        if (has(n, "spotify", "ymusic", "nyx", "deezer", "music", "musica", "soundcloud", "radio", "podcast"))
            return "Música e áudio";
        if (has(n, "galeria", "gallery", "camera", "fotos", "photos", "capcut", "picsart", "lightroom", "snapseed"))
            return "Fotos e câmera";
        if (has(n, "maps", "mapas", "waze", "uber", "moovit", "gps") || name.equals("99") || name.startsWith("99 "))
            return "Mapas e transporte";
        if (has(n, "claude", "chatgpt", "gemini", "drive", "docs", "sheets", "word", "excel", "notion", "keep",
                "notas", "notes", "calendar", "agenda", "gmail", "outlook", "email", "flow", "office", "zoom", "meet"))
            return "Produtividade";
        if (has(n, "news", "noticias", "kindle", "leitor", "reader", "wattpad", "g1"))
            return "Notícias e leitura";
        if (has(n, "calculadora", "calculator", "relogio", "clock", "lanterna", "gravador", "recorder",
                "arquivos", "files", "scanner", "bussola", "tradutor", "translate", "limpador", "cleaner", "seguranca"))
            return "Ferramentas";
        if (Build.VERSION.SDK_INT >= 26) {
            switch (ai.category) {
                case android.content.pm.ApplicationInfo.CATEGORY_AUDIO: return "Música e áudio";
                case android.content.pm.ApplicationInfo.CATEGORY_VIDEO: return "Vídeo e TV";
                case android.content.pm.ApplicationInfo.CATEGORY_IMAGE: return "Fotos e câmera";
                case android.content.pm.ApplicationInfo.CATEGORY_SOCIAL: return "Redes sociais";
                case android.content.pm.ApplicationInfo.CATEGORY_NEWS: return "Notícias e leitura";
                case android.content.pm.ApplicationInfo.CATEGORY_MAPS: return "Mapas e transporte";
                case android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Produtividade";
                default: break;
            }
        }
        if (system || has(pkg, "com.miui.", "com.xiaomi.", "com.android.", "com.google.android.")) return "Sistema";
        return "Outros";
    }

    private static String sizeGroup(long bytes) {
        long mb = bytes / (1024 * 1024);
        if (bytes <= 0) return "TAMANHO DESCONHECIDO";
        if (mb >= 1024) return "ACIMA DE 1 GB";
        if (mb >= 500) return "500 MB A 1 GB";
        if (mb >= 100) return "100 A 500 MB";
        return "ATÉ 100 MB";
    }

    private static String formatSize(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(BR, "%.1f GB", mb / 1024);
        if (mb >= 10) return String.format(BR, "%.0f MB", mb);
        return String.format(BR, "%.1f MB", mb);
    }

    private LinearLayout dockButton(final int i) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(6), 0, dp(6));
        TextView sym = text(20, TEXT);
        sym.setText(DOCK_SYMBOL[i]);
        sym.setGravity(Gravity.CENTER);
        b.addView(sym);
        TextView name = text(10.5f, MUTED);
        name.setText(DOCK_NAME[i]);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(name, lp(MATCH, WRAP));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDock(i); }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { chooseDockApp(i); return true; }
        });
        return b;
    }

    private void updateDockLabels() {
        for (int i = 0; i < dockViews.length; i++) {
            if (dockViews[i] == null) continue;
            App custom = byKey.get(prefs.getString("dock" + i, ""));
            TextView name = (TextView) dockViews[i].getChildAt(1);
            name.setText(custom != null ? custom.label : DOCK_NAME[i]);
        }
    }

    private void openDock(int i) {
        App custom = byKey.get(prefs.getString("dock" + i, ""));
        if (custom != null) { launch(custom); return; }
        boolean ok = false;
        switch (i) {
            case 0:
                ok = startQuiet(new Intent(Intent.ACTION_DIAL));
                break;
            case 1: {
                String sms = null;
                try { sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this); } catch (Exception ignored) { }
                Intent li = sms != null ? getPackageManager().getLaunchIntentForPackage(sms) : null;
                ok = (li != null && startQuiet(li)) || openByWords("mensagens", "messages", "sms")
                        || startQuiet(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING));
                break;
            }
            case 2:
                ok = startQuiet(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                        || openByWords("camera");
                break;
            case 3:
                ok = openByWords("calculadora", "calculator")
                        || startQuiet(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
                break;
            case 4:
                ok = openByWords("galeria", "gallery", "fotos", "photos")
                        || startQuiet(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY));
                break;
        }
        if (!ok) Toast.makeText(this, "Segure o dedo no botão para escolher o app", Toast.LENGTH_LONG).show();
    }

    /** Abre o primeiro app (não clone) cujo nome tenha uma das palavras. */
    private boolean openByWords(String... words) {
        for (String w : words) {
            for (App a : allApps) {
                if (!a.clone && a.search.contains(w)) { launch(a); return true; }
            }
        }
        return false;
    }

    private void chooseDockApp(final int i) {
        if (allApps.isEmpty()) return;
        final List<App> apps = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("↺  Padrão (" + DOCK_NAME[i] + ")");
        for (App a : allApps) {
            apps.add(a);
            names.add(a.display());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Botão " + (i + 1) + " da barra")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) prefs.edit().remove("dock" + i).apply();
                        else prefs.edit().putString("dock" + i, apps.get(which - 1).key).apply();
                        updateDockLabels();
                    }
                }).show();
    }

    private TextView quickButton(String label, View.OnClickListener click) {
        TextView b = text(14, TEXT);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(pill(SURFACE, 0xFF22263A));
        b.setPadding(dp(6), dp(11), dp(6), dp(11));
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams quickParams(boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (!last) p.rightMargin = dp(8);
        return p;
    }

    private ListView makeList() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setVerticalScrollBarEnabled(false);
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        l.setSelector(android.R.color.transparent);
        l.setCacheColorHint(Color.TRANSPARENT);
        return l;
    }

    // ---------- Página Essenciais ----------

    private View buildEssPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Mini player: controla a música que estiver tocando (YMusic, Nyx etc.)
        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.HORIZONTAL);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setBackground(pill(SURFACE, LINE));
        player.setPadding(dp(16), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams pp = lp(MATCH, WRAP);
        pp.topMargin = dp(8);
        pp.bottomMargin = dp(4);
        page.addView(player, pp);

        nowPlaying = text(14, MUTED);
        nowPlaying.setText("♪  Música");
        nowPlaying.setSingleLine(true);
        nowPlaying.setPadding(0, dp(10), 0, dp(10));
        nowPlaying.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openMusicApp(); }
        });
        player.addView(nowPlaying, new LinearLayout.LayoutParams(0, WRAP, 1f));
        player.addView(mediaButton("⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        player.addView(mediaButton("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        player.addView(mediaButton("⏭", KeyEvent.KEYCODE_MEDIA_NEXT));

        essHint = text(14, MUTED);
        essHint.setText("Seus atalhos aparecem aqui.\nNa aba Todos, toque longo num app → Adicionar aos Essenciais.");
        essHint.setLineSpacing(dp(4), 1f);
        essHint.setPadding(0, dp(18), 0, 0);
        essHint.setVisibility(View.GONE);
        page.addView(essHint);

        essList = makeList();
        essAdapter = new EssAdapter();
        essList.setAdapter(essAdapter);
        essList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= essItems.size()) {
                    selectTab(TAB_ALL);
                    Toast.makeText(MainActivity.this,
                            "Toque longo num app → Adicionar aos Essenciais", Toast.LENGTH_LONG).show();
                    return;
                }
                Ess e = essItems.get(pos);
                if (e.app != null) launch(e.app);
                else tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse(e.url)));
            }
        });
        essList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < essItems.size()) showEssMenu(pos);
                return true;
            }
        });
        page.addView(essList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return page;
    }

    private TextView mediaButton(String symbol, final int keyCode) {
        TextView b = text(20, TEXT);
        b.setText(symbol);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.media.session.MediaController mc = activeController();
                if (mc != null) {
                    android.media.session.MediaController.TransportControls tc = mc.getTransportControls();
                    android.media.session.PlaybackState ps = mc.getPlaybackState();
                    boolean playing = ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) tc.skipToPrevious();
                    else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) tc.skipToNext();
                    else if (playing) tc.pause();
                    else tc.play();
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { updateNowPlaying(); }
                    }, 400);
                    return;
                }
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am == null) return;
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
                ui.postDelayed(new Runnable() {
                    @Override public void run() { updateNowPlaying(); }
                }, 400);
            }
        });
        return b;
    }

    // ---------- Página Frequentes / Todos ----------

    private View buildAppsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        searchBox = new EditText(this);
        searchBox.setHint("Buscar app…");
        searchBox.setHintTextColor(FAINT);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchBox.setBackground(pill(SURFACE, LINE));
        searchBox.setPadding(dp(18), dp(11), dp(18), dp(11));
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP);
        sp.topMargin = dp(8);
        sp.bottomMargin = dp(4);
        page.addView(searchBox, sp);

        // Botões para organizar a aba Todos
        sortBar = new android.widget.HorizontalScrollView(this);
        sortBar.setHorizontalScrollBarEnabled(false);
        sortBar.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(2), 0, dp(4));
        sortBar.addView(chips);
        for (int i = 0; i < SORT_NAMES.length; i++) {
            final int mode = i;
            TextView c = text(13, MUTED);
            c.setText(SORT_NAMES[i]);
            c.setPadding(dp(14), dp(7), dp(14), dp(7));
            c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sortMode = mode;
                    prefs.edit().putInt("ordem", mode).apply();
                    refreshRows();
                    list.setSelection(0);
                    if (mode == SORT_SIZE && hasUsageAccess()) loadApps();
                }
            });
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP);
            cp.rightMargin = dp(8);
            chips.addView(c, cp);
            sortChips[i] = c;
        }
        page.addView(sortBar, lp(MATCH, WRAP));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshRows(); }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                for (Object o : rows) {
                    if (o instanceof App) { launch((App) o); break; }
                }
                return true;
            }
        });

        emptyHint = text(14, MUTED);
        emptyHint.setText("Os apps que você mais usar vão aparecer aqui.");
        emptyHint.setPadding(0, dp(18), 0, 0);
        emptyHint.setVisibility(View.GONE);
        page.addView(emptyHint);

        list = makeList();
        list.setFastScrollEnabled(true);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof App) launch((App) o);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof App) showAppMenu((App) o);
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (state != SCROLL_STATE_IDLE) hideKeyboard();
            }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        page.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return page;
    }

    // ---------- Página Sistema ----------

    private View buildSysPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(c);

        section(c, "MEMÓRIA");
        sRam = row(c, "RAM em uso");
        barRam = bar(c);
        sStor = row(c, "Armazenamento livre");

        section(c, "PROCESSADOR");
        sCpu = row(c, "Uso estimado");
        barCpu = bar(c);
        sCpuTemp = row(c, "Temperatura");

        section(c, "APARELHO");
        sUptime = row(c, "Ligado há");
        TextView model = row(c, "Modelo");
        model.setText(Build.MANUFACTURER + " " + Build.MODEL);
        TextView android = row(c, "Android");
        android.setText(Build.VERSION.RELEASE);

        section(c, "LAUNCHER");
        animToggle = row(c, "Animação do fundo");
        ((View) animToggle.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("anim", !prefs.getBoolean("anim", true)).apply();
                updateAnimation();
            }
        });
        musicAppValue = row(c, "App de música");
        ((View) musicAppValue.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseMusicApp(); }
        });
        musicAccessValue = row(c, "Mostrar música tocando");
        ((View) musicAccessValue.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Ative \"Leve Launcher\" na lista", Toast.LENGTH_LONG).show();
                if (!startQuiet(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) {
                    tryStart(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });
        updateMusicSettings();
        TextView change = row(c, "Tela inicial padrão");
        change.setText("Trocar ›");
        ((View) change.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openHomeSettings(); }
        });
        return scroll;
    }

    private void section(LinearLayout c, String title) {
        TextView t = text(12, ACCENT);
        t.setText(title);
        t.setLetterSpacing(0.2f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(18), 0, dp(4));
        c.addView(t);
    }

    private TextView row(LinearLayout c, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView l = text(15, MUTED);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView v = text(15, TEXT);
        v.setText("—");
        v.setGravity(Gravity.END);
        r.addView(v, lp(WRAP, WRAP));
        c.addView(r);
        return v;
    }

    private BarView bar(LinearLayout c) {
        BarView b = new BarView(this);
        LinearLayout.LayoutParams p = lp(MATCH, dp(4));
        p.bottomMargin = dp(6);
        c.addView(b, p);
        return b;
    }

    // ---------- Abas ----------

    private void selectTab(int t) {
        if (t < 0 || t >= TABS || t == tab) return;
        tab = t;
        hideKeyboard();
        if (searchBox.length() > 0) searchBox.setText("");
        showTab(true);
        refreshRows();
        if (t == TAB_SYS || t == TAB_BAT) {
            if (t == TAB_BAT) lastUsageAt = 0;
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    private void showTab(boolean fade) {
        for (int i = 0; i < TABS; i++) {
            tabViews[i].setTextColor(i == tab ? TEXT : FAINT);
        }
        final TextView current = tabViews[tab];
        tabScroll.post(new Runnable() {
            @Override public void run() {
                int x = current.getLeft() - (tabScroll.getWidth() - current.getWidth()) / 2;
                tabScroll.smoothScrollTo(Math.max(0, x), 0);
            }
        });
        View show = tab == TAB_ESS ? pageEss : tab == TAB_SYS ? pageSys : tab == TAB_BAT ? pageBat : pageApps;
        pageEss.setVisibility(show == pageEss ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(show == pageApps ? View.VISIBLE : View.GONE);
        pageBat.setVisibility(show == pageBat ? View.VISIBLE : View.GONE);
        pageSys.setVisibility(show == pageSys ? View.VISIBLE : View.GONE);
        if (fade) {
            show.setAlpha(0f);
            show.animate().alpha(1f).setDuration(140).start();
        }
        if (list != null) list.setSelection(0);
    }

    // ================================================================
    // Apps
    // ================================================================

    private void loadApps() {
        final int gen = ++loadGeneration;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<App> result = new ArrayList<>();
                String self = getPackageName();
                UserHandle me = Process.myUserHandle();
                try {
                    // Lista todos os perfis: inclui os "apps duplos" (clones) da Xiaomi
                    for (UserHandle u : userManager.getUserProfiles()) {
                        long serial = userManager.getSerialNumberForUser(u);
                        for (LauncherActivityInfo info : launcherApps.getActivityList(null, u)) {
                            ComponentName cn = info.getComponentName();
                            if (self.equals(cn.getPackageName())) continue;
                            CharSequence label = info.getLabel();
                            String name = label == null ? cn.getPackageName() : label.toString().trim();
                            result.add(new App(name, cn, u, serial, !u.equals(me)));
                        }
                    }
                } catch (Exception e) {
                    result.clear();
                }
                if (result.isEmpty()) {
                    // Plano B: método simples, só o perfil principal
                    PackageManager pm = getPackageManager();
                    Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    long serial = userManager.getSerialNumberForUser(me);
                    for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                        if (ri.activityInfo == null || self.equals(ri.activityInfo.packageName)) continue;
                        CharSequence label = ri.loadLabel(pm);
                        result.add(new App(label == null ? ri.activityInfo.packageName : label.toString().trim(),
                                new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), me, serial, false));
                    }
                }
                PackageManager pmi = getPackageManager();
                Map<String, Object[]> pkgInfo = new HashMap<>();
                boolean fullSize = hasUsageAccess();
                for (App a : result) {
                    String pkg = a.component.getPackageName();
                    Object[] inf = pkgInfo.get(pkg);
                    if (inf == null) {
                        long t = 0, size = 0;
                        String cat = "Outros";
                        boolean game = false;
                        try {
                            android.content.pm.PackageInfo pi = pmi.getPackageInfo(pkg, 0);
                            android.content.pm.ApplicationInfo ai = pi.applicationInfo;
                            boolean system = ai != null && (ai.flags
                                    & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                            if (!system) t = pi.firstInstallTime;
                            if (ai != null) {
                                size = apkSize(ai);
                                game = isGame(ai);
                                cat = categoryFor(ai, a.search, pkg, system, game);
                            }
                        } catch (Exception ignored) { }
                        inf = new Object[]{t, size, cat, game};
                        pkgInfo.put(pkg, inf);
                    }
                    a.installed = (Long) inf[0];
                    a.size = (Long) inf[1];
                    a.cat = (String) inf[2];
                    a.game = (Boolean) inf[3];
                }
                if (fullSize && Build.VERSION.SDK_INT >= 26) {
                    // Tamanho completo (app + dados + cache), igual ao das Configurações
                    try {
                        android.app.usage.StorageStatsManager ssm = (android.app.usage.StorageStatsManager)
                                getSystemService(Context.STORAGE_STATS_SERVICE);
                        Map<String, Long> done = new HashMap<>();
                        for (App a : result) {
                            String k = a.component.getPackageName() + "#" + a.user.hashCode();
                            Long v = done.get(k);
                            if (v == null) {
                                try {
                                    android.app.usage.StorageStats st = ssm.queryStatsForPackage(
                                            android.os.storage.StorageManager.UUID_DEFAULT,
                                            a.component.getPackageName(), a.user);
                                    v = st.getAppBytes() + st.getDataBytes();
                                } catch (Exception e) {
                                    v = a.size;
                                }
                                done.put(k, v);
                            }
                            a.size = v;
                        }
                    } catch (Exception ignored) { }
                }
                final Collator collator = Collator.getInstance(Locale.getDefault());
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(result, new Comparator<App>() {
                    @Override public int compare(App a, App b) {
                        int c = collator.compare(a.label, b.label);
                        if (c != 0) return c;
                        return (a.clone ? 1 : 0) - (b.clone ? 1 : 0);
                    }
                });
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGeneration || isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(result);
                        byKey.clear();
                        for (App a : allApps) byKey.put(a.key, a);
                        if (!prefs.getBoolean("ess_init", false) && !allApps.isEmpty()) {
                            createDefaultEssentials();
                        }
                        addLeveMusicOnce();
                        rebuildEssentials();
                        updateDockLabels();
                        updateMusicSettings();
                        if (tab == TAB_ESS && essItems.isEmpty()) {
                            tab = TAB_ALL;
                            showTab(false);
                        }
                        refreshRows();
                    }
                });
            }
        }).start();
    }

    private List<App> frequentApps() {
        List<App> f = new ArrayList<>();
        for (App a : allApps) {
            Integer c = usage.get(a.key);
            if (c != null && c > 0) f.add(a);
        }
        Collections.sort(f, new Comparator<App>() {
            @Override public int compare(App a, App b) { return usage.get(b.key) - usage.get(a.key); }
        });
        return f.size() > MAX_FREQUENT ? new ArrayList<>(f.subList(0, MAX_FREQUENT)) : f;
    }

    private void refreshRows() {
        rows.clear();
        String q = normalize(searchBox.getText().toString());
        boolean showEmpty = false;
        if (!q.isEmpty()) {
            List<App> contains = new ArrayList<>();
            for (App a : allApps) {
                if (a.search.startsWith(q)) rows.add(a);
                else if (a.search.contains(q)) contains.add(a);
            }
            rows.addAll(contains);
        } else if (tab == TAB_NEW) {
            List<App> fresh = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (App a : allApps) if (a.installed > 0 && now - a.installed < NEW_TAB_MS) fresh.add(a);
            Collections.sort(fresh, new Comparator<App>() {
                @Override public int compare(App a, App b) { return Long.compare(b.installed, a.installed); }
            });
            String last = null;
            for (App a : fresh) {
                String group = installGroup(a.installed);
                if (!group.equals(last)) {
                    rows.add(group);
                    last = group;
                }
                rows.add(a);
            }
            emptyHint.setText("Nenhum app novo nos últimos 30 dias.");
            showEmpty = rows.isEmpty() && !allApps.isEmpty();
        } else if (tab == TAB_FREQ) {
            rows.addAll(frequentApps());
            emptyHint.setText("Os apps que você mais usar vão aparecer aqui.");
            showEmpty = rows.isEmpty() && !allApps.isEmpty();
        } else if (tab == TAB_ALL) {
            if (sortMode == SORT_CAT) {
                for (String cat : CATEGORIES) {
                    boolean header = false;
                    for (App a : allApps) {
                        if (!a.cat.equals(cat)) continue;
                        if (!header) {
                            rows.add(cat.toUpperCase(BR));
                            header = true;
                        }
                        rows.add(a);
                    }
                }
            } else if (sortMode == SORT_SIZE) {
                List<App> bySize = new ArrayList<>(allApps);
                Collections.sort(bySize, new Comparator<App>() {
                    @Override public int compare(App a, App b) { return Long.compare(b.size, a.size); }
                });
                String last = null;
                for (App a : bySize) {
                    String group = sizeGroup(a.size);
                    if (!group.equals(last)) {
                        rows.add(group);
                        last = group;
                    }
                    rows.add(a);
                }
            } else if (sortMode == SORT_GAMES) {
                for (App a : allApps) if (a.game) rows.add(a);
                emptyHint.setText("Nenhum jogo encontrado neste celular.");
                showEmpty = rows.isEmpty() && !allApps.isEmpty();
            } else {
                String last = null;
                for (App a : allApps) {
                    String letter = firstLetter(a.search);
                    if (!letter.equals(last)) {
                        rows.add(letter);
                        last = letter;
                    }
                    rows.add(a);
                }
            }
        }
        if (sortBar != null) {
            sortBar.setVisibility(tab == TAB_ALL && q.isEmpty() ? View.VISIBLE : View.GONE);
            for (int i = 0; i < sortChips.length; i++) {
                boolean on = i == sortMode;
                sortChips[i].setTextColor(on ? BG : MUTED);
                sortChips[i].setBackground(on ? pill(ACCENT, 0) : pill(SURFACE, LINE));
            }
        }
        emptyHint.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** Agrupa por data de instalação: HOJE, ONTEM, ESTA SEMANA, ESTE MÊS. */
    private static String installGroup(long when) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        if (when >= today) return "HOJE";
        if (when >= today - DAY) return "ONTEM";
        if (when >= today - 6 * DAY) return "ESTA SEMANA";
        return "ESTE MÊS";
    }

    private static String firstLetter(String s) {
        if (s.isEmpty()) return "#";
        char c = Character.toUpperCase(s.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    private void launch(App app) {
        boolean ok;
        try {
            launcherApps.startMainActivity(app.component, app.user, null, null);
            ok = true;
        } catch (Exception e) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(app.component);
            i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ok = tryStart(i);
        }
        if (ok) {
            Integer c = usage.get(app.key);
            usage.put(app.key, c == null ? 1 : c + 1);
            prefs.edit().putInt("u:" + app.key, usage.get(app.key)).apply();
            resetHome();
        }
    }

    private void openAppInfo(App app) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
        } catch (Exception e) {
            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.component.getPackageName(), null)));
        }
    }

    private void showAppMenu(final App app) {
        final List<String> opts = new ArrayList<>();
        final boolean inEss = essKeys().contains(app.key);
        opts.add(inEss ? "Remover dos Essenciais" : "Adicionar aos Essenciais");
        if (usage.containsKey(app.key)) opts.add("Remover dos frequentes");
        opts.add("Informações do app / desinstalar");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.display())
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String o = opts.get(which);
                        if (o.startsWith("Adicionar")) {
                            List<String> k = essKeys();
                            k.add(app.key);
                            saveEssKeys(k);
                            Toast.makeText(MainActivity.this, "Adicionado aos Essenciais", Toast.LENGTH_SHORT).show();
                        } else if (o.startsWith("Remover dos E")) {
                            List<String> k = essKeys();
                            k.remove(app.key);
                            saveEssKeys(k);
                        } else if (o.startsWith("Remover dos f")) {
                            usage.remove(app.key);
                            prefs.edit().remove("u:" + app.key).apply();
                            refreshRows();
                        } else {
                            openAppInfo(app);
                        }
                    }
                }).show();
    }

    // ---------- Essenciais ----------

    private List<String> essKeys() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString("ess", "");
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void saveEssKeys(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k);
        }
        prefs.edit().putString("ess", sb.toString()).apply();
        rebuildEssentials();
    }

    private void rebuildEssentials() {
        essItems.clear();
        for (String k : essKeys()) {
            if (k.startsWith("web:")) {
                String rest = k.substring(4);
                int bar = rest.indexOf('|');
                if (bar > 0) essItems.add(new Ess(k, rest.substring(bar + 1) + "  ↗", rest.substring(0, bar)));
            } else {
                App a = byKey.get(k);
                if (a != null) essItems.add(new Ess(k, a));
            }
        }
        essHint.setVisibility(essItems.isEmpty() ? View.VISIBLE : View.GONE);
        essAdapter.notifyDataSetChanged();
    }

    /** Primeira vez: já coloca os apps que você pediu, se estiverem instalados. */
    private void createDefaultEssentials() {
        String[] wanted = {"leve music", "ymusic", "nyx", "instagram", "threads", "flow", "claude", "chatgpt", "brave", "tiktok"};
        List<String> keys = new ArrayList<>();
        boolean flowFound = false;
        for (String w : wanted) {
            for (App a : allApps) {
                boolean match;
                if (w.equals("flow")) {
                    match = a.search.equals("flow") || a.search.contains("google flow") || a.search.startsWith("flow ");
                    if (match) flowFound = true;
                } else {
                    match = a.search.contains(w);
                }
                if (match && !keys.contains(a.key)) keys.add(a.key);
            }
        }
        if (!flowFound) keys.add("web:" + FLOW_URL + "|Google Flow");
        prefs.edit().putBoolean("ess_init", true).apply();
        saveEssKeys(keys);
    }

    private void showEssMenu(final int pos) {
        final Ess e = essItems.get(pos);
        final List<String> opts = new ArrayList<>();
        if (pos > 0) opts.add("Mover para cima");
        if (pos < essItems.size() - 1) opts.add("Mover para baixo");
        opts.add("Remover dos Essenciais");
        if (e.app != null) opts.add("Informações do app / desinstalar");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.label)
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String o = opts.get(which);
                        // Trabalha sobre a lista visível para manter a ordem certa
                        List<String> k = new ArrayList<>();
                        for (Ess it : essItems) k.add(it.key);
                        if (o.startsWith("Mover para cima")) {
                            Collections.swap(k, pos, pos - 1);
                        } else if (o.startsWith("Mover para baixo")) {
                            Collections.swap(k, pos, pos + 1);
                        } else if (o.startsWith("Remover")) {
                            k.remove(pos);
                        } else {
                            openAppInfo(e.app);
                            return;
                        }
                        saveEssKeys(k);
                    }
                }).show();
    }

    private App findApp(String keyword) {
        for (App a : allApps) if (!a.clone && a.search.contains(keyword)) return a;
        return null;
    }

    // ---------- Música ----------

    private TextView musicAppValue, musicAccessValue;

    private boolean musicAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    /** O player que está tocando agora (ou o último ativo). Precisa da permissão de notificações. */
    private android.media.session.MediaController activeController() {
        if (!musicAccess()) return null;
        try {
            android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager)
                    getSystemService(MEDIA_SESSION_SERVICE);
            List<android.media.session.MediaController> list =
                    msm.getActiveSessions(new ComponentName(this, MusicListener.class));
            if (list == null || list.isEmpty()) return null;
            for (android.media.session.MediaController mc : list) {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) return mc;
            }
            return list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** App escolhido em Sistema → App de música, ou o primeiro de música instalado. */
    private App musicApp() {
        App chosen = byKey.get(prefs.getString("music_app", ""));
        if (chosen != null) return chosen;
        App a = findApp("leve music");
        if (a == null) a = findAppPackage("com.gabriel.levemusic");
        if (a == null) a = findApp("ymusic");
        if (a == null) a = findApp("nyx");
        return a;
    }

    private App findAppPackage(String pkg) {
        for (App a : allApps) if (!a.clone && a.component.getPackageName().equals(pkg)) return a;
        return null;
    }

    /** Toque no nome da música: abre a tela do player que está tocando. */
    private void openMusicApp() {
        android.media.session.MediaController mc = activeController();
        if (mc != null && prefs.getString("music_app", "").isEmpty()) {
            android.app.PendingIntent pi = mc.getSessionActivity();
            if (pi != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                        o.setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        pi.send(this, 0, null, null, null, null, o.toBundle());
                    } else {
                        pi.send();
                    }
                    return;
                } catch (Exception ignored) { }
            }
            App playing = findAppPackage(mc.getPackageName());
            if (playing != null) { launch(playing); return; }
        }
        App a = musicApp();
        if (a != null) { launch(a); return; }
        if (!startQuiet(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))) {
            Toast.makeText(this, "Escolha o app em Sistema → App de música", Toast.LENGTH_LONG).show();
        }
    }

    private void updateNowPlaying() {
        android.media.session.MediaController mc = activeController();
        String text = null;
        boolean playing = false;
        if (mc != null) {
            android.media.session.PlaybackState ps = mc.getPlaybackState();
            playing = ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            android.media.MediaMetadata md = mc.getMetadata();
            if (md != null) {
                String title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (title != null && !title.isEmpty()) {
                    text = "♪  " + title + (artist != null && !artist.isEmpty() ? "  ·  " + artist : "");
                }
            }
        } else {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            playing = am != null && am.isMusicActive();
        }
        if (text == null) {
            App a = musicApp();
            text = playing ? "♪  Tocando agora" : "♪  " + (a != null ? a.label : "Música");
        }
        nowPlaying.setText(text);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setTextColor(playing ? ACCENT : MUTED);
    }

    private void updateMusicSettings() {
        if (musicAppValue == null) return;
        App chosen = byKey.get(prefs.getString("music_app", ""));
        musicAppValue.setText(chosen != null ? chosen.label + " ›" : "Automático ›");
        musicAccessValue.setText(musicAccess() ? "Ligado" : "Permitir ›");
        musicAccessValue.setTextColor(musicAccess() ? TEXT : ACCENT);
    }

    private void chooseMusicApp() {
        final List<App> apps = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("Automático (a música que estiver tocando)");
        // Apps de música primeiro
        for (String w : new String[]{"leve music", "ymusic", "nyx", "spotify", "deezer", "youtube music", "music"}) {
            for (App a : allApps) {
                if (!a.clone && a.search.contains(w) && !apps.contains(a)) {
                    apps.add(a);
                    names.add("♪  " + a.label);
                }
            }
        }
        for (App a : allApps) {
            if (!apps.contains(a)) {
                apps.add(a);
                names.add(a.display());
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("App do atalho de música")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) prefs.edit().remove("music_app").apply();
                        else prefs.edit().putString("music_app", apps.get(which - 1).key).apply();
                        updateMusicSettings();
                        updateNowPlaying();
                    }
                }).show();
    }

    /** Coloca o Leve Music nos Essenciais uma vez, quando ele estiver instalado. */
    private void addLeveMusicOnce() {
        if (prefs.getBoolean("ess_levemusic", false)) return;
        App lm = findAppPackage("com.gabriel.levemusic");
        if (lm == null) lm = findApp("leve music");
        if (lm == null) return;
        List<String> k = essKeys();
        if (!k.contains(lm.key)) {
            k.add(0, lm.key);
            prefs.edit().putString("ess", android.text.TextUtils.join("\n", k)).apply();
        }
        prefs.edit().putBoolean("ess_levemusic", true).apply();
    }

    // ---------- Frequência de uso ----------

    private void loadUsage() {
        usage.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith("u:") && e.getValue() instanceof Integer) {
                usage.put(e.getKey().substring(2), (Integer) e.getValue());
            }
        }
    }

    // ================================================================
    // Medições (RAM, CPU, bateria) — só com a tela visível
    // ================================================================

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            statsHandler.post(collect);
            updateNowPlaying();
            if (tab == TAB_BAT && SystemClock.uptimeMillis() - lastUsageAt > USAGE_EVERY_MS) {
                lastUsageAt = SystemClock.uptimeMillis();
                loadUsageRanking();
            }
            ui.postDelayed(this, STATS_EVERY_MS);
        }
    };

    private final Runnable collect = new Runnable() {
        @Override public void run() {
            final Stats s = readStats();
            ui.post(new Runnable() {
                @Override public void run() { showStats(s); }
            });
        }
    };

    private long[] cpuMax;
    private String[] thermalPaths;

    private Stats readStats() {
        Stats s = new Stats();
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.ramTotal = mi.totalMem;
            s.ramUsed = mi.totalMem - mi.availMem;
        } catch (Exception ignored) { }

        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            s.storFree = fs.getAvailableBytes();
            s.storTotal = fs.getTotalBytes();
        } catch (Exception ignored) { }

        // CPU: o Android não deixa apps lerem o uso real; estimamos pela
        // frequência atual de cada núcleo em relação à máxima.
        int cores = Runtime.getRuntime().availableProcessors();
        if (cpuMax == null) {
            cpuMax = new long[cores];
            for (int i = 0; i < cores; i++) {
                cpuMax[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            }
        }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < cores; i++) {
            if (cpuMax[i] <= 0) continue;
            long cur = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (cur < 0) continue;
            sum += Math.min(1.0, (double) cur / cpuMax[i]);
            n++;
        }
        if (n > 0) s.cpuPct = (int) Math.round(sum / n * 100);

        s.cpuTemp = readCpuTemp();

        Intent b;
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) b = registerReceiver(null, f, Context.RECEIVER_NOT_EXPORTED);
        else b = registerReceiver(null, f);
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) s.batt = Math.round(level * 100f / scale);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
            s.full = status == BatteryManager.BATTERY_STATUS_FULL;
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) s.battTemp = t / 10f;
            s.voltage = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            switch (b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                case BatteryManager.BATTERY_HEALTH_GOOD: s.health = "Boa"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: s.health = "Superaquecida"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: s.health = "Fria"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: s.health = "Desgastada"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: s.health = "Sobretensão"; break;
                default: s.health = "Normal";
            }
        }
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (now != Integer.MIN_VALUE && now != 0) {
                int abs = Math.abs(now);
                s.currentMa = abs > 20000 ? abs / 1000 : abs; // alguns aparelhos já mandam em mA
            }
            int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (cc > 0 && cc != Integer.MAX_VALUE) s.chargeCounterUah = cc > 100000 ? cc : cc * 1000L;
            if (Build.VERSION.SDK_INT >= 28) s.chargeTimeMs = bm.computeChargeTimeRemaining();
        } catch (Exception ignored) { }
        return s;
    }

    private float readCpuTemp() {
        if (thermalPaths == null) {
            List<String> found = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                String base = "/sys/class/thermal/thermal_zone" + i;
                String type = readString(base + "/type");
                if (type == null) {
                    if (i > 10) break;
                    continue;
                }
                String t = type.toLowerCase(Locale.ROOT);
                if (t.contains("cpu") || t.contains("soc") || t.contains("tsens") || t.contains("apss")) {
                    float v = scaleTemp(readLong(base + "/temp"));
                    if (v > 15 && v < 120) found.add(base + "/temp");
                }
            }
            thermalPaths = found.toArray(new String[0]);
        }
        float max = Float.NaN;
        for (String p : thermalPaths) {
            float v = scaleTemp(readLong(p));
            if (v > 15 && v < 120 && (Float.isNaN(max) || v > max)) max = v;
        }
        return max;
    }

    private static float scaleTemp(long raw) {
        if (raw <= 0) return Float.NaN;
        return raw > 1000 ? raw / 1000f : raw;
    }

    private static String readString(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path), 64);
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
        }
    }

    private static long readLong(String path) {
        String s = readString(path);
        if (s == null) return -1;
        try { return Long.parseLong(s); } catch (Exception e) { return -1; }
    }

    private void showStats(Stats s) {
        int ramPct = s.ramTotal > 0 ? (int) Math.round(s.ramUsed * 100.0 / s.ramTotal) : -1;
        StringBuilder line = new StringBuilder();
        line.append("RAM ").append(ramPct >= 0 ? ramPct + "%" : "—");
        line.append("  ·  CPU ").append(s.cpuPct >= 0 ? s.cpuPct + "%" : "—");
        line.append("  ·  ").append(s.charging ? "⚡" : "Bat. ").append(s.batt >= 0 ? s.batt + "%" : "—");
        if (!Float.isNaN(s.battTemp)) line.append("  ·  ").append(Math.round(s.battTemp)).append("°C");
        statsLine.setText(line);

        if (s.currentMa != Integer.MIN_VALUE && s.currentMa > 0) {
            avgMa = avgMa < 0 ? s.currentMa : avgMa * 0.7 + s.currentMa * 0.3;
        }
        if (tab == TAB_BAT) showBattery(s);
        if (tab != TAB_SYS) return;
        sRam.setText(s.ramTotal > 0
                ? String.format(BR, "%.1f de %.1f GB (%d%%)", gb(s.ramUsed), gb(s.ramTotal), ramPct) : "—");
        barRam.setValue(ramPct);
        sStor.setText(s.storTotal > 0
                ? String.format(BR, "%.0f de %.0f GB", gb(s.storFree), gb(s.storTotal)) : "—");
        sCpu.setText(s.cpuPct >= 0 ? s.cpuPct + "%" : "não disponível");
        barCpu.setValue(s.cpuPct);
        sCpuTemp.setText(!Float.isNaN(s.cpuTemp) ? Math.round(s.cpuTemp) + " °C" : "bloqueada pela Xiaomi");
        long min = SystemClock.elapsedRealtime() / 60000;
        long days = min / 1440, hours = (min % 1440) / 60, mins = min % 60;
        sUptime.setText(days > 0 ? days + " d " + hours + " h" : hours + " h " + mins + " min");
    }


    // ================================================================
    // Aba Bateria
    // ================================================================

    private View buildBatPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, dp(8), 0, dp(28));
        scroll.addView(c);

        bLevel = text(44, TEXT);
        bLevel.setText("—");
        bLevel.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        bLevel.setIncludeFontPadding(false);
        c.addView(bLevel);
        bStatus = text(14, MUTED);
        bStatus.setPadding(0, dp(2), 0, dp(10));
        c.addView(bStatus);
        barBatt = bar(c);

        bRemain = row(c, "Tempo restante");
        bCurrent = row(c, "Consumo agora");
        bTemp = row(c, "Temperatura");
        bVolt = row(c, "Voltagem");
        bHealth = row(c, "Saúde");

        section(c, "QUEM MAIS GASTOU HOJE");
        usageNote = text(12, FAINT);
        usageNote.setText("Tempo que cada app ficou ativo desde 0h, na tela e em segundo plano. "
                + "Quanto mais tempo, mais bateria. Toque num app para frear ele.");
        usageNote.setLineSpacing(dp(2), 1f);
        usageNote.setPadding(0, 0, 0, dp(8));
        c.addView(usageNote);

        usagePerm = text(14, ACCENT);
        usagePerm.setText("✦  Toque para liberar o \"Acesso ao uso\" para o Leve Launcher");
        usagePerm.setBackground(pill(0xFF0D1024, 0xFF2B3566));
        usagePerm.setPadding(dp(16), dp(10), dp(16), dp(10));
        usagePerm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Ative o Leve Launcher na lista", Toast.LENGTH_LONG).show();
                if (!startQuiet(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)))) {
                    tryStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
            }
        });
        usagePerm.setVisibility(View.GONE);
        c.addView(usagePerm, lp(MATCH, WRAP));

        usageBox = new LinearLayout(this);
        usageBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(usageBox, lp(MATCH, WRAP));

        section(c, "ATALHOS");
        TextView sysUsage = row(c, "Uso de bateria do sistema");
        sysUsage.setText("Abrir ›");
        ((View) sysUsage.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBattery(); }
        });
        TextView saver = row(c, "Economia de bateria");
        saver.setText("Abrir ›");
        ((View) saver.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!startQuiet(new Intent("android.settings.BATTERY_SAVER_SETTINGS"))) openBattery();
            }
        });
        return scroll;
    }

    private void showBattery(Stats s) {
        bLevel.setText(s.batt >= 0 ? s.batt + "%" : "—");
        barBatt.setValue(s.batt >= 0 ? s.batt : -1);
        String st = s.full ? "Cheia" : s.charging ? "Carregando" : "Descarregando";
        bStatus.setText(st);

        String remain = "—";
        if (s.charging && s.chargeTimeMs > 0) {
            remain = "cheia em " + formatDur(s.chargeTimeMs);
        } else if (!s.charging && s.chargeCounterUah > 0 && avgMa > 20) {
            double hours = (s.chargeCounterUah / 1000.0) / avgMa;
            if (hours > 0 && hours < 96) remain = "~" + formatDur((long) (hours * 3600000)) + " neste ritmo";
        }
        bRemain.setText(remain);
        bCurrent.setText(s.currentMa != Integer.MIN_VALUE
                ? s.currentMa + " mA " + (s.charging ? "entrando" : "saindo") : "—");
        bTemp.setText(!Float.isNaN(s.battTemp) ? String.format(BR, "%.1f °C", s.battTemp) : "—");
        bTemp.setTextColor(!Float.isNaN(s.battTemp) && s.battTemp >= 40 ? 0xFFFF8A80 : TEXT);
        bVolt.setText(s.voltage > 0 ? String.format(BR, "%.2f V", s.voltage / 1000f) : "—");
        bHealth.setText(s.health);
    }

    private static String formatDur(long ms) {
        long min = ms / 60000;
        if (min < 1) return "menos de 1 min";
        long h = min / 60, m = min % 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Drain {
        final String pkg, label;
        final long fg, bg;
        Drain(String pkg, String label, long fg, long bg) { this.pkg = pkg; this.label = label; this.fg = fg; this.bg = bg; }
        long total() { return fg + bg; }
    }

    /** Monta a lista de quem mais ficou ativo hoje (roda fora da tela principal). */
    private void loadUsageRanking() {
        if (!hasUsageAccess()) {
            usagePerm.setVisibility(View.VISIBLE);
            usageBox.removeAllViews();
            return;
        }
        usagePerm.setVisibility(View.GONE);
        final Map<String, String> labels = new HashMap<>();
        for (App a : allApps) if (!a.clone) labels.put(a.component.getPackageName(), a.label);
        statsHandler.post(new Runnable() {
            @Override public void run() {
                final List<Drain> out = new ArrayList<>();
                try {
                    UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(
                            cal.getTimeInMillis(), System.currentTimeMillis());
                    String self = getPackageName();
                    for (Map.Entry<String, UsageStats> e : m.entrySet()) {
                        String pkg = e.getKey();
                        if (pkg.equals(self)) continue;
                        UsageStats us = e.getValue();
                        long fg = us.getTotalTimeInForeground();
                        long bg = Build.VERSION.SDK_INT >= 29 ? us.getTotalTimeForegroundServiceUsed() : 0;
                        if (fg + bg < 60000) continue;
                        String label = labels.get(pkg);
                        if (label == null) continue; // só apps que aparecem na sua lista
                        out.add(new Drain(pkg, label, fg, bg));
                    }
                    Collections.sort(out, new Comparator<Drain>() {
                        @Override public int compare(Drain a, Drain b) { return Long.compare(b.total(), a.total()); }
                    });
                } catch (Exception ignored) { }
                ui.post(new Runnable() {
                    @Override public void run() { showUsageRanking(out); }
                });
            }
        });
    }

    private void showUsageRanking(List<Drain> list) {
        usageBox.removeAllViews();
        if (list.isEmpty()) {
            TextView t = text(14, MUTED);
            t.setText("Nada registrado hoje ainda.");
            t.setPadding(0, dp(6), 0, dp(6));
            usageBox.addView(t);
            return;
        }
        long max = list.get(0).total();
        int n = Math.min(10, list.size());
        for (int i = 0; i < n; i++) {
            final Drain d = list.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(8), 0, dp(8));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = text(16, TEXT);
            name.setText(d.label);
            name.setSingleLine(true);
            top.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView time = text(15, i < 3 ? ACCENT : TEXT);
            time.setText(formatDur(d.total()));
            top.addView(time, lp(WRAP, WRAP));
            item.addView(top);

            TextView detail = text(12, MUTED);
            detail.setText(d.bg > 0
                    ? "tela " + formatDur(d.fg) + "  ·  2º plano " + formatDur(d.bg)
                    : "tela " + formatDur(d.fg));
            detail.setPadding(0, dp(2), 0, dp(5));
            item.addView(detail);

            BarView b = new BarView(this);
            b.setValue((int) Math.max(1, d.total() * 100 / Math.max(1, max)));
            item.addView(b, lp(MATCH, dp(3)));

            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showDrainMenu(d); }
            });
            usageBox.addView(item, lp(MATCH, WRAP));
        }
    }

    private void showDrainMenu(final Drain d) {
        final String[] opts = {
                "Fechar agora (processos em segundo plano)",
                "Restringir bateria deste app",
                "Forçar parada (informações do app)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(d.label)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            try {
                                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                                am.killBackgroundProcesses(d.pkg);
                                Toast.makeText(MainActivity.this, d.label + " fechado em segundo plano",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Não deu para fechar", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 1) {
                            restrictBattery(d);
                        } else {
                            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", d.pkg, null)));
                        }
                    }
                }).show();
    }

    /** Abre a tela da Xiaomi de "Economia de bateria" do app (Restringir em segundo plano). */
    private void restrictBattery(Drain d) {
        Intent miui = new Intent().setComponent(new ComponentName(
                "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
        miui.putExtra("package_name", d.pkg);
        miui.putExtra("package_label", d.label);
        if (startQuiet(miui)) {
            Toast.makeText(this, "Escolha \"Restringir apps em segundo plano\"", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Toque em Bateria / Economia de bateria e restrinja", Toast.LENGTH_LONG).show();
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", d.pkg, null)));
    }

    private static double gb(long bytes) {
        return bytes / 1073741824.0;
    }

    // ================================================================
    // Animação do fundo
    // ================================================================

    private void updateAnimation() {
        boolean wanted = prefs.getBoolean("anim", true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean saver = pm != null && pm.isPowerSaveMode();
        if (animToggle != null) {
            animToggle.setText(!wanted ? "Desligada" : saver ? "Pausada (economia)" : "Ligada");
        }
        stars.setRunning(wanted && !saver && resumed);
    }

    // ================================================================
    // Acesso rápido
    // ================================================================

    private void openCleaner() {
        List<Intent> tries = new ArrayList<>();
        tries.add(new Intent("miui.intent.action.GARBAGE_CLEANUP"));
        tries.add(new Intent().setComponent(new ComponentName(
                "com.miui.cleanmaster", "com.miui.optimizecenter.main.MainActivity")));
        tries.add(new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter", "com.miui.optimizecenter.MainActivity")));
        tries.add(new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter", "com.miui.optimizecenter.main.MainActivity")));
        PackageManager pm = getPackageManager();
        Intent cm = pm.getLaunchIntentForPackage("com.miui.cleanmaster");
        if (cm != null) tries.add(cm);
        Intent sc = pm.getLaunchIntentForPackage("com.miui.securitycenter");
        if (sc != null) tries.add(sc);
        for (Intent i : tries) if (startQuiet(i)) return;
        Toast.makeText(this, "Limpador da Xiaomi não encontrado", Toast.LENGTH_SHORT).show();
        tryStart(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
    }

    private void openBattery() {
        if (startQuiet(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY))) return;
        if (startQuiet(new Intent("android.settings.BATTERY_SAVER_SETTINGS"))) return;
        tryStart(new Intent(Settings.ACTION_SETTINGS));
    }

    // ================================================================
    // Tela inicial padrão
    // ================================================================

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
        Toast.makeText(this, "Escolha \"Leve Launcher\" como tela inicial", Toast.LENGTH_LONG).show();
        if (startQuiet(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        if (startQuiet(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))) return;
        tryStart(new Intent(Settings.ACTION_SETTINGS));
    }

    // ================================================================
    // Utilidades
    // ================================================================

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
        Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void resetHome() {
        if (searchBox.length() > 0) searchBox.setText("");
        searchBox.clearFocus();
        hideKeyboard();
        int wanted = !essItems.isEmpty() ? TAB_ESS : !frequentApps().isEmpty() ? TAB_FREQ : TAB_ALL;
        if (tab != wanted) {
            tab = wanted;
            showTab(false);
        }
        refreshRows();
        if (list != null) list.setSelection(0);
        if (essList != null) essList.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // ================================================================
    // Adaptadores das listas
    // ================================================================

    private TextView appRowView() {
        TextView tv = text(19, TEXT);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(0, dp(11), 0, dp(11));
        return tv;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int p) { return rows.get(p) instanceof App; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            TextView tv = (TextView) convertView;
            if (o instanceof String) {
                if (tv == null) {
                    tv = text(12, ACCENT);
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    tv.setLetterSpacing(0.2f);
                    tv.setPadding(0, dp(16), 0, dp(4));
                }
                tv.setText((String) o);
            } else {
                if (tv == null) tv = appRowView();
                App app = (App) o;
                if (tab == TAB_ALL && sortMode == SORT_SIZE && app.size > 0
                        && searchBox.length() == 0) {
                    String sz = "  " + formatSize(app.size);
                    android.text.SpannableString sp = new android.text.SpannableString(app.display() + sz);
                    int start = sp.length() - sz.length();
                    sp.setSpan(new android.text.style.ForegroundColorSpan(MUTED), start, sp.length(), 0);
                    sp.setSpan(new android.text.style.RelativeSizeSpan(0.7f), start, sp.length(), 0);
                    tv.setText(sp);
                } else if (app.isNew() && tab != TAB_NEW) {
                    android.text.SpannableString sp = new android.text.SpannableString(app.display() + "  novo");
                    int start = sp.length() - 4;
                    sp.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), start, sp.length(), 0);
                    sp.setSpan(new android.text.style.RelativeSizeSpan(0.6f), start, sp.length(), 0);
                    tv.setText(sp);
                } else {
                    tv.setText(app.display());
                }
            }
            return tv;
        }
    }

    private final class EssAdapter extends BaseAdapter {
        @Override public int getCount() { return essItems.size() + 1; }
        @Override public Object getItem(int p) { return p < essItems.size() ? essItems.get(p) : null; }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return p < essItems.size() ? 0 : 1; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (position < essItems.size()) {
                if (tv == null) {
                    tv = appRowView();
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
                }
                tv.setText(essItems.get(position).label);
            } else {
                if (tv == null) {
                    tv = appRowView();
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setTextColor(FAINT);
                }
                tv.setText("+  Adicionar atalho");
            }
            return tv;
        }
    }

    // ================================================================
    // Barra fininha (RAM, CPU, bateria)
    // ================================================================

    private static final class BarView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int value = -1;

        BarView(Context c) {
            super(c);
            bg.setColor(0xFF1A1D2B);
            fg.setColor(ACCENT);
        }

        void setValue(int v) {
            if (v == value) return;
            value = v;
            fg.setColor(v >= 85 ? 0xFFFF8A80 : ACCENT);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, bg);
            if (value > 0) c.drawRoundRect(0, 0, w * Math.min(100, value) / 100f, h, r, r, fg);
        }
    }

    // ================================================================
    // Fundo espacial: estrelas fixas + leve cintilar + estrela cadente rara.
    // Redesenha ~1 vez por segundo; a estrela cadente dura menos de 1 s
    // a cada 20–35 s. Para tudo quando a tela inicial não está visível.
    // ================================================================

    private static final class StarsView extends View {
        private static final int N = 70, TWINKLE = 10;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final float density;
        private float[] xs, ys, rs;
        private int[] colors;
        private final int[] twAlpha = new int[TWINKLE];
        private boolean running;

        private long shootStart = -1;
        private float sx, sy, sdx, sdy;
        private static final long SHOOT_MS = 900;

        StarsView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            for (int i = 0; i < TWINKLE; i++) twAlpha[i] = 120;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            Random r = new Random(1987);
            xs = new float[N];
            ys = new float[N];
            rs = new float[N];
            colors = new int[N];
            for (int i = 0; i < N; i++) {
                xs[i] = r.nextFloat() * w;
                ys[i] = r.nextFloat() * h;
                float big = r.nextFloat();
                rs[i] = density * (i < TWINKLE ? 1.2f : big > 0.93f ? 1.2f : big > 0.6f ? 0.8f : 0.5f);
                int alpha = 40 + r.nextInt(110);
                boolean blue = r.nextFloat() > 0.7f;
                colors[i] = Color.argb(alpha, blue ? 170 : 230, blue ? 190 : 230, 255);
            }
        }

        void setRunning(boolean on) {
            if (on == running) return;
            running = on;
            removeCallbacks(twinkle);
            removeCallbacks(startShoot);
            removeCallbacks(shootFrame);
            shootStart = -1;
            if (on) {
                postDelayed(twinkle, 1000);
                postDelayed(startShoot, 6000 + rnd.nextInt(10000));
            }
            invalidate();
        }

        private final Runnable twinkle = new Runnable() {
            @Override public void run() {
                if (!running) return;
                for (int i = 0; i < TWINKLE; i++) {
                    if (rnd.nextFloat() < 0.45f) twAlpha[i] = 30 + rnd.nextInt(200);
                }
                invalidate();
                postDelayed(this, 1100);
            }
        };

        private final Runnable startShoot = new Runnable() {
            @Override public void run() {
                if (!running || getWidth() == 0) return;
                sx = getWidth() * (0.2f + rnd.nextFloat() * 0.7f);
                sy = getHeight() * rnd.nextFloat() * 0.45f;
                float len = getWidth() * (0.35f + rnd.nextFloat() * 0.25f);
                sdx = -len * 0.8f;
                sdy = len * 0.45f;
                shootStart = SystemClock.uptimeMillis();
                post(shootFrame);
                postDelayed(this, 20000 + rnd.nextInt(15000));
            }
        };

        private final Runnable shootFrame = new Runnable() {
            @Override public void run() {
                if (!running || shootStart < 0) return;
                invalidate();
                if (SystemClock.uptimeMillis() - shootStart < SHOOT_MS) postDelayed(this, 33);
                else { shootStart = -1; invalidate(); }
            }
        };

        @Override
        protected void onDraw(Canvas c) {
            if (xs == null) return;
            for (int i = 0; i < N; i++) {
                int col = colors[i];
                if (i < TWINKLE && running) col = (col & 0x00FFFFFF) | (twAlpha[i] << 24);
                paint.setColor(col);
                c.drawCircle(xs[i], ys[i], rs[i], paint);
            }
            if (shootStart >= 0) {
                float p = (SystemClock.uptimeMillis() - shootStart) / (float) SHOOT_MS;
                if (p > 1f) return;
                float fade = p < 0.2f ? p / 0.2f : 1f - (p - 0.2f) / 0.8f;
                float hx = sx + sdx * p, hy = sy + sdy * p;
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int k = 0; k < 8; k++) {
                    float t0 = k / 8f, t1 = (k + 1) / 8f;
                    float tail = 0.18f;
                    float ax = hx - sdx * tail * t0, ay = hy - sdy * tail * t0;
                    float bx = hx - sdx * tail * t1, by = hy - sdy * tail * t1;
                    paint.setStrokeWidth(density * 1.6f * (1f - t0));
                    paint.setColor(Color.argb((int) (220 * fade * (1f - t0)), 210, 220, 255));
                    c.drawLine(ax, ay, bx, by, paint);
                }
            }
        }
    }
}
