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
 * Leve Launcher v16 — tela inicial minimalista com tema espacial + barra do Google.
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
 * Economia: fundo preto puro, sem ícones, sem bibliotecas. A barra do Google é só um campo
 * de texto (nada rodando em segundo plano).
 * As medições e a animação do fundo só rodam com a tela inicial visível,
 * e a animação desliga sozinha no modo economia de bateria.
 */
public class MainActivity extends Activity {

    // ---------- Cores ----------
    // Fundo e superfícies sempre em preto puro: pixel apagado na tela AMOLED.
    static final int BG = 0xFF000000;
    static final int TEXT = 0xFFFFFFFF;
    static final int SURFACE = 0xFF000000;
    // Estas mudam conforme o tema escolhido em Sistema → Tema
    static int MUTED, FAINT, ACCENT, LINE, STAR_R, STAR_G, STAR_B;

    static final String[] THEME_NAMES = {"Espaço", "Aurora", "Sakura", "Âmbar", "Neon", "Grafite",
            "Matrix", "Oceano", "Lava", "Lua"};
    private static final int[][] THEMES = {
            // ACCENT,    LINE,       MUTED,      FAINT,      cor das estrelas (R, G, B)
            {0xFFB4C4FF, 0xFF555C7A, 0xFFC2C7D6, 0xFF8A90A8, 170, 190, 255},
            {0xFF7CF2C8, 0xFF3F6B5E, 0xFFBFD8CF, 0xFF7FA095, 140, 255, 210},
            {0xFFFFA8CF, 0xFF7A4B60, 0xFFE2C6D2, 0xFFA88A98, 255, 170, 210},
            {0xFFFFC870, 0xFF7A6240, 0xFFE3D3BC, 0xFFA8977E, 255, 210, 140},
            {0xFF6FF6FF, 0xFF2F6C78, 0xFFBFE9EE, 0xFF7FAFB5, 120, 240, 255},
            {0xFFFFFFFF, 0xFF5A5A5A, 0xFFC8C8C8, 0xFF8E8E8E, 225, 225, 225},
            {0xFF39FF7A, 0xFF1F6B3A, 0xFFB5E8C4, 0xFF6FA582, 60, 255, 120},
            {0xFF4FC3F7, 0xFF245A73, 0xFFBCDDEB, 0xFF7AA3B5, 90, 190, 255},
            {0xFFFF7A45, 0xFF7A3A22, 0xFFEACBBE, 0xFFAD8A7C, 255, 120, 70},
            {0xFFD8E2F0, 0xFF4E5868, 0xFFC9D0DA, 0xFF8A93A1, 210, 220, 240},
    };

    // Estilo da tela (Sistema → Estilo): Clássico, Compacto, Grande ou Foco
    static final String[] STYLE_NAMES = {"Clássico", "Compacto", "Grande", "Foco"};
    static int CLOCK_SP = 60, ROW_SP = 19, ROW_PAD = 11;
    static final int DARK_DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static boolean SHOW_STATS = true, SHOW_QUICK = true;

    static void applyStyle(int st) {
        switch (st) {
            case 1: CLOCK_SP = 40; ROW_SP = 16; ROW_PAD = 8; SHOW_STATS = true; SHOW_QUICK = true; break;
            case 2: CLOCK_SP = 72; ROW_SP = 24; ROW_PAD = 14; SHOW_STATS = true; SHOW_QUICK = true; break;
            case 3: CLOCK_SP = 84; ROW_SP = 21; ROW_PAD = 12; SHOW_STATS = false; SHOW_QUICK = false; break;
            default: CLOCK_SP = 60; ROW_SP = 19; ROW_PAD = 11; SHOW_STATS = true; SHOW_QUICK = true;
        }
    }

    static void applyTheme(int t) {
        int[] c = THEMES[Math.max(0, Math.min(THEMES.length - 1, t))];
        ACCENT = c[0]; LINE = c[1]; MUTED = c[2]; FAINT = c[3];
        STAR_R = c[4]; STAR_G = c[5]; STAR_B = c[6];
    }

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
    private static final String[] DOCK_SHORT = {"Telefone", "SMS", "Câmera", "Calc.", "Galeria"};

    private String dockDefaultName(int i) { return compact ? DOCK_SHORT[i] : DOCK_NAME[i]; }
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
        final String key, label, url, name, pkg;
        final App app;
        Ess(String key, App app) {
            this.key = key; this.app = app; this.label = app.display(); this.url = null; this.name = null; this.pkg = null;
        }
        Ess(String key, String name, String url, String pkg) {
            this.key = key; this.app = null; this.name = name; this.label = name + "  ↗"; this.url = url; this.pkg = pkg;
        }
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
    final List<Object> rows = new ArrayList<>();
    /** Linha "Pesquisar no Google" no fim da busca de apps. */
    static final Object GOOGLE_ROW = new Object();
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
        applyTheme(prefs.getInt("tema", 0));
        applyStyle(prefs.getInt("estilo", 0));
        boolean firstDetect = !prefs.contains("fraco");
        if (firstDetect) prefs.edit().putBoolean("fraco", isWeakPhone()).apply();
        lite = prefs.getBoolean("fraco", false);
        if (firstDetect && lite) {
            Toast.makeText(this, "Modo celular fraco ligado automaticamente (Sistema → Launcher)",
                    Toast.LENGTH_LONG).show();
        }
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) getSystemService(USER_SERVICE);
        loadUsage();
        sortMode = Math.max(0, Math.min(3, prefs.getInt("ordem", SORT_AZ)));
        setContentView(buildUi());

        statsThread = new HandlerThread("leve-stats", Process.THREAD_PRIORITY_BACKGROUND);
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
            // A Play Store atualiza vários apps de uma vez: junta tudo numa recarga só
            @Override public void onPackageRemoved(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackageAdded(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackageChanged(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { scheduleReload(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { scheduleReload(); }
        };
        if (launcherApps != null) launcherApps.registerCallback(appsCallback, ui);

        loadApps();

        if (!prefs.getBoolean("pediu_padrao", false) && !prefs.getBoolean("banner_off", false) && !isDefaultHome()) {
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
        updatePostCard();
        updateGratCard();
        updateDefaultBanner();
        updateProtectRows();
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
        // Não abre mais nada sozinho: só some com o aviso se deu certo
        if (requestCode == REQ_HOME) {
            if (resultCode == RESULT_OK) prefs.edit().putBoolean("banner_off", true).apply();
            updateDefaultBanner();
        }
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
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.5f)), stroke);
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
        getWindow().setBackgroundDrawable(null);
        stars = new StarsView(this);
        if (lite) stars.setVisibility(View.GONE);
        frame.addView(stars, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Tela pequena ou letra grande do sistema: tudo mais compacto para sobrar espaço pra lista
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float hDp = dm.heightPixels / dm.density, wDp = dm.widthPixels / dm.density;
        float fs = getResources().getConfiguration().fontScale;
        fontScale = fs;
        compact = hDp < 680 || hDp / Math.max(1f, fs) < 600 || wDp < 340;
        tight = hDp < 560 || (compact && fs >= 1.5f);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = wDp < 340 ? 14 : 22;
        root.setPadding(dp(side), dp(compact ? 12 : 24), dp(side), 0);
        frame.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));
        // O foco começa na tela, não num campo de texto (sem cursor piscando nem teclado do nada)
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        homeRoot = root;

        // --- Aviso de tela inicial padrão ---
        defaultBanner = text(14, ACCENT);
        defaultBanner.setText("✦  Toque para tornar o Leve sua tela inicial  ·  segure para esconder");
        defaultBanner.setBackground(pill(0xFF000000, ACCENT));
        defaultBanner.setPadding(dp(16), dp(compact ? 7 : 10), dp(16), dp(compact ? 7 : 10));
        if (compact) {
            defaultBanner.setText("✦  Tornar o Leve a tela inicial  ·  segure p/ esconder");
            hsize(defaultBanner, 13);
        }
        defaultBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDefaultHome(); }
        });
        defaultBanner.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                prefs.edit().putBoolean("banner_off", true).apply();
                defaultBanner.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Aviso escondido", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        LinearLayout.LayoutParams bp = lp(MATCH, WRAP);
        bp.bottomMargin = dp(compact ? 8 : 14);
        root.addView(defaultBanner, bp);

        // Telas compactas: relógio à esquerda e os botões rápidos (só o símbolo) à direita, na mesma linha
        LinearLayout head = null, headLeft = null;
        if (compact) {
            head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            headLeft = new LinearLayout(this);
            headLeft.setOrientation(LinearLayout.VERTICAL);
            head.addView(headLeft, new LinearLayout.LayoutParams(0, WRAP, 1f));
            root.addView(head, lp(MATCH, WRAP));
        }
        LinearLayout clockParent = compact ? headLeft : root;

        // --- Relógio, data e medidores ---
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(TEXT);
        if (compact) {
            // em dp: a letra grande do sistema não deixa o relógio engolir a tela
            clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.min(CLOCK_SP, hDp < 560 ? 40 : 48) * Math.min(fs, 1.15f));
        } else {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SP);
        }
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)); }
        });
        clockParent.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextColor(MUTED);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (compact) {
            hsize(date, 13.5f);
            date.setFormat24Hour("EEE, d 'de' MMM");
            date.setFormat12Hour("EEE, d 'de' MMM");
        }
        date.setSingleLine(true);
        date.setEllipsize(android.text.TextUtils.TruncateAt.END);
        date.setLetterSpacing(0.04f);
        clockParent.addView(date);

        statsLine = text(13, ACCENT);
        hsize(statsLine, 13);
        statsLine.setText("RAM —  ·  CPU —  ·  Bateria —");
        statsLine.setLetterSpacing(0.03f);
        statsLine.setPadding(0, dp(compact ? 4 : 8), 0, 0);
        statsLine.setSingleLine(true);
        statsLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statsLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(TAB_SYS); }
        });
        root.addView(statsLine);
        if (!SHOW_STATS) statsLine.setVisibility(View.GONE);

        // --- Cartão do Modo Proteção (só aparece com calor ou bateria baixa) ---
        protectCard = new LinearLayout(this);
        protectCard.setOrientation(LinearLayout.VERTICAL);
        protectCard.setBackground(pill(0xFF000000, 0xFFFF8A65));
        protectCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        protectTitle = text(14, 0xFFFFB199);
        protectTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        protectCard.addView(protectTitle);
        TextView protectHint = text(12.5f, MUTED);
        protectHint.setText("Animações pausadas e medições mais espaçadas. Toque para esfriar agora.");
        protectHint.setPadding(0, dp(2), 0, 0);
        protectCard.addView(protectHint);
        protectCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { coolDownNow(); }
        });
        protectCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams pcp = lp(MATCH, WRAP);
        pcp.topMargin = dp(12);
        root.addView(protectCard, pcp);

        // --- Widget do Google: digitar, falar ou Lens, direto da tela inicial ---
        googleBar = buildGoogleBar();
        LinearLayout.LayoutParams gp = lp(MATCH, WRAP);
        gp.topMargin = dp(compact ? 10 : 14);
        root.addView(googleBar, gp);
        updateGoogleBarVisibility();

        // --- Acesso rápido ---
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quickRow = quick;
        if (compact) {
            head.addView(quick, lp(WRAP, WRAP));
        } else {
            LinearLayout.LayoutParams qp = lp(MATCH, WRAP);
            qp.topMargin = dp(14);
            root.addView(quick, qp);
        }
        if (!SHOW_QUICK) quick.setVisibility(View.GONE);
        TextView clean = quickButton("◌  Limpar", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasXiaomiCleaner()) openCleaner(); else freeRam();
            }
        });
        clean.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { freeRam(); return true; }
        });
        quick.addView(clean, quickParams(false));
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
        FrameLayout tabFrame = new FrameLayout(this);
        tabFrame.addView(tabs);
        // Risquinho embaixo da aba escolhida (desliza ao trocar de aba)
        tabIndicator = new View(this);
        tabIndicator.setBackground(pill(ACCENT, 0));
        tabFrame.addView(tabIndicator, new FrameLayout.LayoutParams(0, dp(2.5f), Gravity.BOTTOM));
        tabScroll.addView(tabFrame);
        LinearLayout.LayoutParams tp = lp(MATCH, WRAP);
        tp.topMargin = dp(compact ? 8 : 16);
        root.addView(tabScroll, tp);
        for (int i = 0; i < TABS; i++) {
            final int which = i;
            TextView t = text(14, FAINT);
            hsize(t, 14);
            t.setText(TAB_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(compact ? 6 : 8), dp(compact ? 16 : 20), dp(compact ? 6 : 8));
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
        dock.setPadding(dp(4), dp(compact ? 3 : 6), dp(4), dp(compact ? 3 : 6));
        LinearLayout.LayoutParams dlp = lp(MATCH, WRAP);
        dlp.topMargin = dp(compact ? 6 : 8);
        dlp.bottomMargin = dp(compact ? 8 : 14);
        root.addView(dock, dlp);
        for (int i = 0; i < DOCK_NAME.length; i++) {
            dockViews[i] = dockButton(i);
            dock.addView(dockViews[i], new LinearLayout.LayoutParams(0, WRAP, 1f));
        }

        showTab(false);
        root.requestFocus();
        return frame;
    }

    private final LinearLayout[] dockViews = new LinearLayout[5];
    private LinearLayout homeRoot;

    // ================================================================
    // Widget do Google
    // ================================================================

    static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private boolean compact, tight;
    private float fontScale = 1f;
    LinearLayout googleBar;
    EditText googleInput;

    /**
     * Barra leve do Google: nada fica rodando, é só um campo de texto.
     * Digite e toque em buscar (ou Enter), 🎤 pesquisa por voz, ◉ abre o Lens.
     * Segure a barra para escolher onde abrir as pesquisas ou esconder.
     */
    private LinearLayout buildGoogleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(pill(SURFACE, LINE));
        bar.setPadding(dp(14), 0, dp(4), 0);
        bar.setMinimumHeight(dp(46));

        TextView g = text(18, TEXT);
        g.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        android.text.SpannableString gs = new android.text.SpannableString("G");
        gs.setSpan(new android.text.style.ForegroundColorSpan(0xFF8AB4F8), 0, 1, 0);
        g.setText(gs);
        g.setPadding(0, 0, dp(10), 0);
        g.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openGoogleApp(); }
        });
        bar.addView(g, lp(WRAP, WRAP));

        googleInput = new EditText(this);
        googleInput.setHint(compact ? "Buscar no Google" : "Pesquisar no Google");
        googleInput.setHintTextColor(FAINT);
        googleInput.setTextColor(TEXT);
        googleInput.setTextSize(compact ? TypedValue.COMPLEX_UNIT_DIP : TypedValue.COMPLEX_UNIT_SP,
                compact ? 15 * Math.min(fontScale, 1.1f) : 15);
        googleInput.setSingleLine(true);
        googleInput.setBackground(null);
        googleInput.setPadding(0, dp(10), 0, dp(10));
        googleInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        googleInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        googleInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                submitGoogle();
                return true;
            }
        });
        googleInput.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (googleInput.length() > 0) return false; // deixa colar/selecionar texto
                showGoogleMenu();
                return true;
            }
        });
        bar.addView(googleInput, new LinearLayout.LayoutParams(0, WRAP, 1f));

        bar.addView(googleIcon("🎤", "Pesquisar por voz", new View.OnClickListener() {
            @Override public void onClick(View v) { voiceSearch(); }
        }));
        bar.addView(googleIcon("◉", "Google Lens", new View.OnClickListener() {
            @Override public void onClick(View v) { openLens(); }
        }));
        bar.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showGoogleMenu(); return true; }
        });
        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focusGoogle(); }
        });
        return bar;
    }

    private TextView googleIcon(String symbol, String desc, View.OnClickListener click) {
        TextView b = text(18, MUTED);
        b.setText(symbol);
        b.setContentDescription(desc);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(44));
        b.setMinHeight(dp(44));
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    private void focusGoogle() {
        googleInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(googleInput, 0);
    }

    /** Pesquisa o que foi digitado. Vazio: abre a pesquisa do app Google (ou o google.com). */
    void submitGoogle() {
        String q = googleInput.getText().toString().trim();
        if (q.isEmpty()) { openGoogleApp(); return; }
        googleSearch(q);
        googleInput.setText("");
        googleInput.clearFocus();
        hideKeyboardOf(googleInput);
    }

    static boolean looksLikeUrl(String q) {
        if (q.contains(" ") || q.length() < 4) return false;
        if (q.startsWith("http://") || q.startsWith("https://")) return true;
        int dot = q.indexOf('.');
        return dot > 0 && dot < q.length() - 2 && q.matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?");
    }

    /** Abre o resultado onde você escolheu: app Google, navegador padrão ou um navegador fixo. */
    void googleSearch(String q) {
        if (looksLikeUrl(q)) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(q.startsWith("http") ? q : "https://" + q));
            String pkg = prefs.getString("google_open", "");
            if (pkg.length() > 1 && !pkg.equals(GOOGLE_APP) && startQuiet(new Intent(v).setPackage(pkg))) return;
            tryStart(v);
            return;
        }
        String where = prefs.getString("google_open", "");
        Uri web = Uri.parse("https://www.google.com/search?q=" + enc(q));
        if (where.isEmpty() || where.equals(GOOGLE_APP)) {
            Intent ws = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
                    .setPackage(GOOGLE_APP);
            if (startQuiet(ws)) return;
        } else if (!where.equals("-")) {
            if (startQuiet(new Intent(Intent.ACTION_VIEW, web).setPackage(where))) return;
        }
        // "-" = navegador padrão, ou plano B quando o escolhido não existe mais
        tryStart(new Intent(Intent.ACTION_VIEW, web));
    }

    private void openGoogleApp() {
        Intent s = new Intent("android.search.action.GLOBAL_SEARCH").setPackage(GOOGLE_APP);
        if (startQuiet(s)) return;
        Intent li = getPackageManager().getLaunchIntentForPackage(GOOGLE_APP);
        if (li != null && startQuiet(li)) return;
        tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")));
    }

    private void voiceSearch() {
        if (startQuiet(new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH))) return;
        if (startQuiet(new Intent(Intent.ACTION_VOICE_COMMAND))) return;
        if (startQuiet(new Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE"))) return;
        Toast.makeText(this, "Pesquisa por voz não disponível. Instale o app Google.", Toast.LENGTH_LONG).show();
    }

    private void openLens() {
        Intent lens = new Intent().setClassName(GOOGLE_APP, "com.google.android.apps.search.lens.LensExportedActivity");
        if (startQuiet(lens)) return;
        if (startQuiet(new Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")).setPackage(GOOGLE_APP))) return;
        Intent app = getPackageManager().getLaunchIntentForPackage("com.google.ar.lens");
        if (app != null && startQuiet(app)) return;
        tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")));
    }

    private void showGoogleMenu() {
        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        String cur = prefs.getString("google_open", "");
        pkgs.add(""); names.add((cur.isEmpty() ? "● " : "○ ") + "Automático (app Google, se tiver)");
        pkgs.add("-"); names.add((cur.equals("-") ? "● " : "○ ") + "Navegador padrão do celular");
        for (String w : new String[]{"chrome", "brave", "firefox", "opera", "edge", "duckduckgo", "navegador", "browser"}) {
            for (App a : allApps) {
                String p = a.component.getPackageName();
                if (!a.clone && a.search.contains(w) && !pkgs.contains(p)) {
                    pkgs.add(p);
                    names.add((cur.equals(p) ? "● " : "○ ") + a.label);
                }
            }
        }
        final int fixed = names.size();
        names.add("Esconder a barra do Google");
        new AlertDialog.Builder(this, DARK_DIALOG)
                .setTitle("Pesquisas do Google abrem em…")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == fixed) { setGoogleBar(false); return; }
                        prefs.edit().putString("google_open", pkgs.get(which)).apply();
                    }
                }).show();
    }

    /**
     * Em telas compactas a barra do Google some nas abas de apps: lá a própria busca de apps
     * já pesquisa no Google, e a lista ganha espaço.
     */
    private void updateGoogleBarVisibility() {
        if (googleBar == null) return;
        boolean appsTab = pageApps != null && pageApps.getVisibility() == View.VISIBLE;
        boolean on = prefs.getBoolean("google_bar", true) && !(compact && appsTab);
        googleBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (statsLine != null) statsLine.setVisibility(SHOW_STATS && !(tight && appsTab) ? View.VISIBLE : View.GONE);
    }

    void setGoogleBar(boolean on) {
        prefs.edit().putBoolean("google_bar", on).apply();
        updateGoogleBarVisibility();
        if (googleToggle != null) googleToggle.setText(on ? "Ligada" : "Desligada");
        if (!on) Toast.makeText(this, "Barra escondida. Volte em Sistema → Barra do Google", Toast.LENGTH_LONG).show();
    }

    private TextView googleToggle;

    private void hideKeyboardOf(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

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
        b.setPadding(0, dp(compact ? 3 : 6), 0, dp(compact ? 3 : 6));
        TextView sym = text(20, TEXT);
        if (compact) hsize(sym, 18);
        sym.setText(DOCK_SYMBOL[i]);
        sym.setGravity(Gravity.CENTER);
        sym.setIncludeFontPadding(false);
        // altura fixa: todos os nomes ficam alinhados, mesmo com símbolos de tamanhos diferentes
        b.addView(sym, lp(MATCH, dp(compact ? 24 : 28)));
        TextView name = text(10.5f, MUTED);
        if (compact) name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f * Math.min(fontScale, 1.15f));
        name.setText(dockDefaultName(i));
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= 26) {
            // nome comprido: a letra diminui um pouco para caber, em vez de cortar
            name.setMaxLines(1);
            name.setAutoSizeTextTypeUniformWithConfiguration(8, compact ? 13 : 12, 1, TypedValue.COMPLEX_UNIT_DIP);
        } else {
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        b.addView(name, lp(MATCH, WRAP));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDock(i); }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { chooseDockApp(i); return true; }
        });
        pressable(b);
        return b;
    }

    private void updateDockLabels() {
        for (int i = 0; i < dockViews.length; i++) {
            if (dockViews[i] == null) continue;
            App custom = byKey.get(prefs.getString("dock" + i, ""));
            TextView name = (TextView) dockViews[i].getChildAt(1);
            name.setText(custom != null ? custom.label : dockDefaultName(i));
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
        if (compact) {
            // só o símbolo, num botão redondo de 44 dp (o nome fica para leitores de tela)
            String[] parts = label.split("  ", 2);
            b.setContentDescription(parts.length > 1 ? parts[1] : label);
            b.setText(parts[0]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            b.setGravity(Gravity.CENTER);
            b.setBackground(pill(SURFACE, LINE));
            b.setMinWidth(dp(44));
            b.setMinHeight(dp(44));
            b.setPadding(0, 0, 0, 0);
            b.setOnClickListener(click);
            pressable(b);
            return b;
        }
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setBackground(pill(SURFACE, LINE));
        b.setPadding(dp(4), dp(compact ? 8 : 11), dp(4), dp(compact ? 8 : 11));
        b.setMinHeight(dp(44));
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    private LinearLayout.LayoutParams quickParams(boolean last) {
        if (compact) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
            p.leftMargin = dp(6);
            return p;
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (!last) p.rightMargin = dp(8);
        return p;
    }

    LinearLayout quickRow;

    /** Tamanho de letra do topo: em telas compactas a letra do sistema cresce no máximo 25%. */
    private void hsize(TextView t, float sp) {
        if (compact) t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * Math.min(fontScale, 1.25f));
        else t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
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

        // Caderno da Gratidão: primeiro cartão da tela, mostra uma gratidão sua sorteada
        gratCard = new LinearLayout(this);
        gratCard.setOrientation(LinearLayout.VERTICAL);
        gratCard.setBackground(pill(SURFACE, ACCENT));
        gratCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams gcp = lp(MATCH, WRAP);
        gcp.topMargin = dp(8);
        page.addView(gratCard, gcp);
        gratTitle = text(15, TEXT);
        gratTitle.setSingleLine(true);
        gratTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gratCard.addView(gratTitle);
        gratLine = text(13, MUTED);
        gratLine.setSingleLine(true);
        gratLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gratLine.setPadding(0, dp(3), 0, 0);
        gratCard.addView(gratLine);
        gratCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(MainActivity.this, GratidaoActivity.class)); }
                catch (Exception ignored) { }
            }
        });
        updateGratCard();

        // Mini player: controla a música que estiver tocando (YMusic, Nyx etc.)
        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.HORIZONTAL);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setBackground(pill(SURFACE, LINE));
        player.setPadding(dp(16), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams pp = lp(MATCH, WRAP);
        pp.topMargin = dp(6);
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

        // Leve Meditar: Wim Hof, meditação guiada e som para dormir
        TextView medit = text(14, TEXT);
        medit.setText("🌙  Meditar  ·  Wim Hof, acalmar e dormir");
        medit.setSingleLine(true);
        medit.setBackground(pill(SURFACE, LINE));
        medit.setPadding(dp(16), dp(14), dp(16), dp(14));
        medit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(MainActivity.this, MeditacaoActivity.class)); }
                catch (Exception ignored) { }
            }
        });
        LinearLayout.LayoutParams mlp = lp(MATCH, WRAP);
        mlp.bottomMargin = dp(4);
        page.addView(medit, mlp);

        // Post Leve: "Postar próximo" direto do launcher (só aparece com o app instalado)
        postCard = new LinearLayout(this);
        postCard.setOrientation(LinearLayout.HORIZONTAL);
        postCard.setGravity(Gravity.CENTER_VERTICAL);
        postCard.setBackground(pill(SURFACE, LINE));
        postCard.setPadding(dp(16), dp(4), dp(6), dp(4));
        LinearLayout.LayoutParams pcp = lp(MATCH, WRAP);
        pcp.bottomMargin = dp(4);
        page.addView(postCard, pcp);
        postInfo = text(14, MUTED);
        postInfo.setText("📸  Post Leve");
        postInfo.setSingleLine(true);
        postInfo.setPadding(0, dp(10), 0, dp(10));
        postInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPostLeve(); }
        });
        postCard.addView(postInfo, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView photoBtn = text(14, TEXT);
        photoBtn.setText("📸 Foto");
        photoBtn.setGravity(Gravity.CENTER);
        photoBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        photoBtn.setBackground(pill(SURFACE, LINE));
        photoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { postNext(false); }
        });
        LinearLayout.LayoutParams pbl = lp(WRAP, WRAP);
        pbl.rightMargin = dp(6);
        postCard.addView(photoBtn, pbl);
        TextView videoBtn = text(14, BG);
        videoBtn.setText("🎬 Vídeo");
        videoBtn.setGravity(Gravity.CENTER);
        videoBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        videoBtn.setBackground(pill(ACCENT, ACCENT));
        videoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { postNext(true); }
        });
        postCard.addView(videoBtn, lp(WRAP, WRAP));
        updatePostCard();

        essHint = text(14, MUTED);
        essHint.setText("Seus atalhos aparecem aqui.\nToque em \"+ Adicionar atalho\" para colocar apps, sites ou links.");
        essHint.setLineSpacing(dp(4), 1f);
        essHint.setPadding(0, dp(18), 0, 0);
        essHint.setVisibility(View.GONE);
        page.addView(essHint);

        essList = makeList();
        essAdapter = new EssAdapter();
        essList.setAdapter(essAdapter);
        essList.setLayoutAnimation(listEntrance());
        essList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= essItems.size()) {
                    showAddShortcut();
                    return;
                }
                Ess e = essItems.get(pos);
                if (e.app != null) launch(e.app);
                else openLink(e);
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

    // ---------- Post Leve ----------
    static final String POST_LEVE = "com.leve.analise";
    private LinearLayout gratCard;
    private TextView gratTitle, gratLine;

    /** Atualiza o cartão do Caderno da Gratidão (contagem de hoje e uma gratidão sorteada). */
    private void updateGratCard() {
        if (gratCard == null) return;
        org.json.JSONArray a = GratidaoActivity.load(this);
        int hoje = GratidaoActivity.countToday(a), seq = GratidaoActivity.streak(a);
        String t = "🙏  Caderno da Gratidão";
        if (hoje > 0) t += "  ·  hoje " + hoje;
        if (seq > 1) t += "  ·  🔥" + seq;
        gratTitle.setText(t);
        String l = GratidaoActivity.lembrete(a);
        gratLine.setText(l != null ? "Grato por  " + l : "Toque e anote pelo que você é grato hoje");
    }

    private LinearLayout postCard;
    private TextView postInfo;

    private boolean hasPostLeve() {
        try { getPackageManager().getPackageInfo(POST_LEVE, 0); return true; } catch (Exception e) { return false; }
    }

    /** Mostra quantas fotos estão prontas (o Post Leve informa por um provedor só de leitura). */
    private void updatePostCard() {
        if (postCard == null) return;
        if (!hasPostLeve()) { postCard.setVisibility(View.GONE); return; }
        postCard.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                String label = "📸  Post Leve";
                try {
                    android.database.Cursor c = getContentResolver().query(
                            android.net.Uri.parse("content://com.leve.analise.status/fila"), null, null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) {
                            int n = c.getInt(c.getColumnIndexOrThrow("prontas"));
                            int iv = c.getColumnIndex("videos");
                            int ip = c.getColumnIndex("produzindo");
                            int nv = iv >= 0 ? c.getInt(iv) : 0;
                            boolean prod = ip >= 0 && c.getInt(ip) == 1;
                            int ih = c.getColumnIndex("hoje"), im = c.getColumnIndex("meta");
                            int meta = im >= 0 ? c.getInt(im) : 0, hoje = ih >= 0 ? c.getInt(ih) : 0;
                            label = "📸 " + n + "  ·  🎬 " + nv + (meta > 0 ? "  ·  hoje " + hoje + "/" + meta : "") + (prod ? "  ·  🤖" : "");
                        }
                        c.close();
                    }
                } catch (Exception ignored) { }
                final String l = label;
                ui.post(new Runnable() { @Override public void run() { if (postInfo != null) postInfo.setText(l); } });
            }
        }).start();
    }

    /** Foto: sorteia foto + legenda e abre o Threads. Vídeo: sorteia vídeo pronto + legenda e abre o Reels. */
    private void postNext(boolean video) {
        try {
            Intent i = new Intent(video ? "com.leve.analise.POSTAR_VIDEO" : "com.leve.analise.POSTAR_PROXIMO");
            i.setPackage(POST_LEVE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Atualize o Post Leve pra usar esse botão", Toast.LENGTH_LONG).show();
        }
    }

    private void openPostLeve() {
        Intent i = getPackageManager().getLaunchIntentForPackage(POST_LEVE);
        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
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
        searchBox.setHint(compact ? "Buscar app ou no Google…" : "Buscar app…");
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
                    if ((mode == SORT_SIZE && hasUsageAccess()) || (lite && mode != SORT_AZ && !metaLoaded)) loadApps();
                }
            });
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP);
            cp.rightMargin = dp(8);
            chips.addView(c, cp);
            pressable(c);
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
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                for (Object o : rows) {
                    if (o instanceof App) { launch((App) o); return true; }
                }
                String q = searchBox.getText().toString().trim();
                if (!q.isEmpty()) { googleSearch(q); searchBox.setText(""); hideKeyboard(); }
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
        list.setLayoutAnimation(listEntrance());
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= rows.size()) return;
                Object o = rows.get(pos);
                if (o instanceof App) launch((App) o);
                else if (o == GOOGLE_ROW) {
                    String q = searchBox.getText().toString().trim();
                    if (!q.isEmpty()) { googleSearch(q); searchBox.setText(""); hideKeyboard(); }
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= rows.size()) return true;
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
        liteToggle = row(c, "Modo celular fraco");
        liteToggle.setTextColor(ACCENT);
        ((View) liteToggle.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("fraco", !lite).apply();
                Toast.makeText(MainActivity.this, !lite ? "Modo celular fraco ligado" : "Modo celular fraco desligado",
                        Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        TextView ramBtn = row(c, "Liberar memória (RAM)");
        ramBtn.setText("Liberar ›");
        ((View) ramBtn.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { freeRam(); }
        });
        TextView styleValue = row(c, "Estilo da tela");
        styleValue.setText(STYLE_NAMES[Math.max(0, Math.min(3, prefs.getInt("estilo", 0)))] + " ›");
        styleValue.setTextColor(ACCENT);
        ((View) styleValue.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this, DARK_DIALOG)
                        .setTitle("Estilo da tela")
                        .setSingleChoiceItems(new String[]{
                                "Clássico — como sempre foi",
                                "Compacto — cabe mais apps na tela",
                                "Grande — letras maiores, fácil de ler",
                                "Foco — só relógio e apps, sem distrações"},
                                prefs.getInt("estilo", 0), new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        d.dismiss();
                                        prefs.edit().putInt("estilo", which).apply();
                                        recreate();
                                    }
                                }).show();
            }
        });
        themeValue = row(c, "Tema");
        themeValue.setText(THEME_NAMES[Math.max(0, Math.min(THEME_NAMES.length - 1, prefs.getInt("tema", 0)))] + " ›");
        themeValue.setTextColor(ACCENT);
        ((View) themeValue.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseTheme(); }
        });
        googleToggle = row(c, "Barra do Google");
        googleToggle.setText(prefs.getBoolean("google_bar", true) ? "Ligada" : "Desligada");
        ((View) googleToggle.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGoogleBar(!prefs.getBoolean("google_bar", true)); }
        });
        uiAnimToggle = row(c, "Animações da interface");
        ((View) uiAnimToggle.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("anim_ui", !prefs.getBoolean("anim_ui", true)).apply();
                updateAnimation();
            }
        });
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
        updateMusicSettings();
        TextView upd = row(c, "Atualizar o launcher");
        upd.setText("v" + Updater.installedVersion(this) + "  ·  Verificar ›");
        upd.setTextColor(ACCENT);
        ((View) upd.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updater.check(); }
        });
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
        slideDir = t > tab ? 1 : -1;
        tab = t;
        if (lite && t == TAB_NEW && !metaLoaded) loadApps();
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
        final boolean animate = fade && uiAnim;
        tabScroll.post(new Runnable() {
            @Override public void run() {
                int x = current.getLeft() - (tabScroll.getWidth() - current.getWidth()) / 2;
                tabScroll.smoothScrollTo(Math.max(0, x), 0);
                int w = Math.max(dp(12), current.getWidth() - dp(20));
                ViewGroup.LayoutParams lpI = tabIndicator.getLayoutParams();
                if (lpI.width != w) {
                    lpI.width = w;
                    tabIndicator.setLayoutParams(lpI);
                }
                if (animate) {
                    tabIndicator.animate().translationX(current.getLeft()).setDuration(220)
                            .setInterpolator(EASE).start();
                } else {
                    tabIndicator.setTranslationX(current.getLeft());
                }
            }
        });
        View show = tab == TAB_ESS ? pageEss : tab == TAB_SYS ? pageSys : tab == TAB_BAT ? pageBat : pageApps;
        pageEss.setVisibility(show == pageEss ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(show == pageApps ? View.VISIBLE : View.GONE);
        pageBat.setVisibility(show == pageBat ? View.VISIBLE : View.GONE);
        pageSys.setVisibility(show == pageSys ? View.VISIBLE : View.GONE);
        updateGoogleBarVisibility();
        if (fade && uiAnim) {
            // Desliza um pouquinho para o lado da aba e aparece (só propriedades da GPU, bem leve)
            show.setAlpha(0f);
            show.setTranslationX(slideDir * dp(28));
            show.animate().alpha(1f).translationX(0).setDuration(200).setInterpolator(EASE).start();
            if (show == pageApps && list != null) list.scheduleLayoutAnimation();
            if (show == pageEss && essList != null) essList.scheduleLayoutAnimation();
        } else {
            show.setAlpha(1f);
            show.setTranslationX(0);
        }
        if (list != null) list.setSelection(0);
    }

    // ---------- Animações leves ----------
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.DecelerateInterpolator(1.6f);
    private View tabIndicator;
    private int slideDir = 1;
    /** Animações de interface (desligam sozinhas no modo economia de bateria). */
    private boolean uiAnim = true;

    /** Itens da lista entram em cascata rápida (só os visíveis, ~200 ms). */
    private android.view.animation.LayoutAnimationController listEntrance() {
        android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(0f, 1f);
        android.view.animation.TranslateAnimation tr = new android.view.animation.TranslateAnimation(0, 0, dp(10), 0);
        set.addAnimation(a);
        set.addAnimation(tr);
        set.setDuration(180);
        set.setInterpolator(EASE);
        android.view.animation.LayoutAnimationController c = new android.view.animation.LayoutAnimationController(set, 0.08f);
        c.setOrder(android.view.animation.LayoutAnimationController.ORDER_NORMAL);
        return c;
    }

    /** Botão "afunda" um pouquinho ao tocar e volta ao soltar. */
    private void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (!uiAnim) return false;
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(EASE).start();
                }
                return false;
            }
        });
    }

    private void chooseTheme() {
        int current = prefs.getInt("tema", 0);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Tema (fundo sempre preto AMOLED)")
                .setSingleChoiceItems(THEME_NAMES, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == prefs.getInt("tema", 0)) return;
                        prefs.edit().putInt("tema", which).apply();
                        recreate();
                    }
                }).show();
    }

    // ================================================================
    // Apps
    // ================================================================

    private final Runnable reload = new Runnable() {
        @Override public void run() { loadApps(); }
    };

    /** Várias mudanças seguidas (instalar/atualizar) viram uma recarga só, 400 ms depois da última. */
    void scheduleReload() {
        ui.removeCallbacks(reload);
        ui.postDelayed(reload, 400);
    }

    /** Uma única linha de trabalho para ler os apps: nunca duas leituras pesadas ao mesmo tempo. */
    private static final java.util.concurrent.ExecutorService APPS_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "leve-apps");
                    t.setDaemon(true);
                    return t;
                }
            });
    /** Quantas leituras completas de apps já rodaram (para os testes de estresse). */
    static final java.util.concurrent.atomic.AtomicInteger LOADS_RUN = new java.util.concurrent.atomic.AtomicInteger();

    void loadApps() {
        final int gen = ++loadGeneration;
        // No modo celular fraco, data/tamanho/categoria só são lidos quando alguma tela precisa
        final boolean wantMeta = !lite || tab == TAB_NEW || sortMode != SORT_AZ;
        final boolean wantFullSize = sortMode == SORT_SIZE;
        APPS_IO.execute(new Runnable() {
            @Override public void run() {
                if (gen != loadGeneration) return; // já pediram outra mais nova: pula esta
                LOADS_RUN.incrementAndGet();
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception ignored) { }
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
                boolean fullSize = wantFullSize && hasUsageAccess();
                for (App a : result) {
                    if (!wantMeta) break;
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
                metaLoaded = wantMeta;
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
                        if (gen != loadGeneration || isFinishing() || isDestroyed()) return;
                        musicCache = null;
                        musicCached = false;
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
        });
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
            // no fim da busca: pesquisar o mesmo texto no Google (1 toque)
            if (prefs.getBoolean("google_bar", true) || rows.isEmpty()) rows.add(GOOGLE_ROW);
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
                // Formato: web:<link>|<nome>[|<pacote do app que abre>]
                String[] parts = k.substring(4).split("\\|", -1);
                if (parts.length >= 2 && !parts[0].isEmpty()) {
                    String pkg = parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : null;
                    essItems.add(new Ess(k, parts[1], parts[0], pkg));
                }
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
        if (e.app == null) {
            opts.add("Editar nome e link");
            opts.add("Abrir com outro app");
        }
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
                        } else if (o.startsWith("Editar")) {
                            editLink(pos, e);
                            return;
                        } else if (o.startsWith("Abrir com")) {
                            chooseOpener(pos, e);
                            return;
                        } else {
                            openAppInfo(e.app);
                            return;
                        }
                        saveEssKeys(k);
                    }
                }).show();
    }


    // ---------- Atalhos de link (sites, WhatsApp, pesquisas, rotas) ----------

    private static String webKey(String url, String name, String pkg) {
        String u = url.replace("|", "%7C").replace("\n", "");
        String n = name.replace("|", "/").replace("\n", " ").trim();
        return "web:" + u + "|" + n + (pkg != null ? "|" + pkg : "");
    }

    private void addEssKey(String key) {
        List<String> k = new ArrayList<>();
        for (Ess it : essItems) k.add(it.key);
        k.add(key);
        saveEssKeys(k);
        Toast.makeText(this, "Atalho criado", Toast.LENGTH_SHORT).show();
    }

    private void replaceEssKey(int pos, String key) {
        List<String> k = new ArrayList<>();
        for (Ess it : essItems) k.add(it.key);
        if (pos >= 0 && pos < k.size()) k.set(pos, key); else k.add(key);
        saveEssKeys(k);
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s.trim(), "UTF-8"); } catch (Exception e) { return s.trim(); }
    }

    /** Aceita "site.com", "https://...", "whatsapp://..." e outros links de apps. */
    private static String fixUrl(String raw) {
        String u = raw.trim();
        if (u.isEmpty()) return u;
        if (u.contains("://") || u.startsWith("intent:") || u.startsWith("tel:") || u.startsWith("mailto:")
                || u.startsWith("geo:") || u.startsWith("sms:")) return u;
        return "https://" + u;
    }

    private static String hostName(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "Link";
            if (h.startsWith("www.")) h = h.substring(4);
            int dot = h.indexOf('.');
            String n = dot > 0 ? h.substring(0, dot) : h;
            return n.isEmpty() ? "Link" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
        } catch (Exception e) {
            return "Link";
        }
    }

    private void showAddShortcut() {
        final String[] opts = {
                "▢  App instalado",
                "↗  Site ou link",
                "✆  Conversa do WhatsApp (número)",
                "▶  Pesquisa no YouTube",
                "⌕  Pesquisa no Google",
                "⌖  Rota no Maps (endereço)"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Novo atalho")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: pickAppForEss(); break;
                            case 1:
                                linkForm("Site ou link", "Nome (ex.: Meu banco)", "Link (ex.: site.com.br)",
                                        "", "", android.text.InputType.TYPE_TEXT_VARIATION_URI, new LinkDone() {
                                            @Override public void done(String name, String value) {
                                                String url = fixUrl(value);
                                                addEssKey(webKey(url, name.isEmpty() ? hostName(url) : name, null));
                                            }
                                        });
                                break;
                            case 2:
                                linkForm("Conversa do WhatsApp", "Nome (ex.: Mãe)", "Número com DDD (ex.: 11 91234-5678)",
                                        "", "", android.text.InputType.TYPE_CLASS_PHONE, new LinkDone() {
                                            @Override public void done(String name, String value) {
                                                String digits = value.replaceAll("[^0-9]", "");
                                                if (digits.length() <= 11) digits = "55" + digits;
                                                addEssKey(webKey("https://wa.me/" + digits,
                                                        name.isEmpty() ? "WhatsApp " + value.trim() : name, null));
                                            }
                                        });
                                break;
                            case 3:
                                linkForm("Pesquisa no YouTube", "Nome (ex.: Lofi)", "O que pesquisar",
                                        "", "", android.text.InputType.TYPE_CLASS_TEXT, new LinkDone() {
                                            @Override public void done(String name, String value) {
                                                addEssKey(webKey("https://www.youtube.com/results?search_query=" + enc(value),
                                                        name.isEmpty() ? "YouTube: " + value.trim() : name, null));
                                            }
                                        });
                                break;
                            case 4:
                                linkForm("Pesquisa no Google", "Nome (ex.: Cotação do dólar)", "O que pesquisar",
                                        "", "", android.text.InputType.TYPE_CLASS_TEXT, new LinkDone() {
                                            @Override public void done(String name, String value) {
                                                addEssKey(webKey("https://www.google.com/search?q=" + enc(value),
                                                        name.isEmpty() ? value.trim() : name, null));
                                            }
                                        });
                                break;
                            default:
                                linkForm("Rota no Maps", "Nome (ex.: Casa)", "Endereço de destino",
                                        "", "", android.text.InputType.TYPE_CLASS_TEXT, new LinkDone() {
                                            @Override public void done(String name, String value) {
                                                addEssKey(webKey("https://www.google.com/maps/dir/?api=1&destination=" + enc(value),
                                                        name.isEmpty() ? "Rota: " + value.trim() : name, null));
                                            }
                                        });
                                break;
                        }
                    }
                }).show();
    }

    private interface LinkDone { void done(String name, String value); }

    private void linkForm(String title, String nameHint, String valueHint, String name, String value,
                          int valueType, final LinkDone cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        final EditText n = new EditText(this);
        n.setHint(nameHint);
        n.setSingleLine(true);
        n.setText(name);
        box.addView(n, lp(MATCH, WRAP));
        final EditText v = new EditText(this);
        v.setHint(valueHint);
        v.setSingleLine(true);
        v.setInputType(valueType == android.text.InputType.TYPE_TEXT_VARIATION_URI
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                : valueType);
        v.setText(value);
        box.addView(v, lp(MATCH, WRAP));
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View b) {
                        String val = v.getText().toString().trim();
                        if (val.isEmpty()) {
                            v.setError("Preencha aqui");
                            return;
                        }
                        cb.done(n.getText().toString().trim(), val);
                        dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
        v.requestFocus();
    }

    private void pickAppForEss() {
        final List<App> apps = new ArrayList<>(allApps);
        final String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).display();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escolha o app")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String key = apps.get(which).key;
                        if (essKeys().contains(key)) {
                            Toast.makeText(MainActivity.this, "Já está nos Essenciais", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        addEssKey(key);
                    }
                }).show();
    }

    private void editLink(final int pos, final Ess e) {
        linkForm("Editar atalho", "Nome", "Link", e.name, e.url,
                android.text.InputType.TYPE_TEXT_VARIATION_URI, new LinkDone() {
                    @Override public void done(String name, String value) {
                        String url = fixUrl(value);
                        replaceEssKey(pos, webKey(url, name.isEmpty() ? hostName(url) : name, e.pkg));
                    }
                });
    }

    /** Escolher qual app abre o link (ex.: sempre no Brave). */
    private void chooseOpener(final int pos, final Ess e) {
        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        pkgs.add(null);
        names.add("Padrão do celular");
        for (String w : new String[]{"brave", "chrome", "firefox", "opera", "edge", "navegador", "browser",
                "youtube", "whatsapp", "maps"}) {
            for (App a : allApps) {
                String p = a.component.getPackageName();
                if (!a.clone && a.search.contains(w) && !pkgs.contains(p)) {
                    pkgs.add(p);
                    names.add(a.label);
                }
            }
        }
        for (App a : allApps) {
            String p = a.component.getPackageName();
            if (!a.clone && !pkgs.contains(p)) {
                pkgs.add(p);
                names.add(a.label);
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Abrir \"" + e.name + "\" com")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        replaceEssKey(pos, webKey(e.url, e.name, pkgs.get(which)));
                    }
                }).show();
    }

    private void openLink(Ess e) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(e.url));
        if (e.pkg != null) {
            Intent withApp = new Intent(i).setPackage(e.pkg);
            if (startQuiet(withApp)) return;
        }
        tryStart(i);
    }

    private App findApp(String keyword) {
        for (App a : allApps) if (!a.clone && a.search.contains(keyword)) return a;
        return null;
    }

    // ---------- Música ----------

    private TextView musicAppValue;
    private final Updater updater = new Updater(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);

    /**
     * Ler o player ativo exige a permissão de notificações, que faz o Play Protect
     * bloquear a instalação fora da Play Store. Por isso fica desligado: os botões
     * usam as teclas de mídia, que funcionam com qualquer player.
     */
    private android.media.session.MediaController activeController() {
        return null;
    }

    /** App escolhido em Sistema → App de música, ou o primeiro de música instalado. */
    private App musicCache;
    private boolean musicCached;

    private App musicApp() {
        App chosen = byKey.get(prefs.getString("music_app", ""));
        if (chosen != null) return chosen;
        // guardado: o relógio de 3 s não precisa varrer a lista de apps toda vez
        if (musicCached && (musicCache == null || byKey.get(musicCache.key) == musicCache)) return musicCache;
        App a = findApp("leve music");
        if (a == null) a = findAppPackage("com.gabriel.levemusic");
        if (a == null) a = findApp("ymusic");
        if (a == null) a = findApp("nyx");
        musicCache = a;
        musicCached = true;
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
    }

    private void chooseMusicApp() {
        final List<App> apps = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("Automático (Leve Music, YMusic ou Nyx)");
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
            ui.postDelayed(this, (lite || protectMode) && tab != TAB_SYS && tab != TAB_BAT ? 10000 : STATS_EVERY_MS);
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
        int cores = lite && tab != TAB_SYS ? 0 : Runtime.getRuntime().availableProcessors();
        // Núcleos ligam/desligam sozinhos no celular: se aparecer núcleo novo, mede de novo (antes fechava o app)
        if (cores > 0 && (cpuMax == null || cpuMax.length < cores)) cpuMax = null;
        if (cpuMax == null && cores > 0) {
            cpuMax = new long[cores];
            for (int i = 0; i < cores; i++) {
                cpuMax[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            }
        }
        double sum = 0;
        int n = 0;
        long[] maxes = cpuMax;
        for (int i = 0; i < cores && maxes != null && i < maxes.length; i++) {
            if (maxes[i] <= 0) continue;
            long cur = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (cur < 0) continue;
            sum += Math.min(1.0, (double) cur / maxes[i]);
            n++;
        }
        if (n > 0) s.cpuPct = (int) Math.round(sum / n * 100);

        if (!lite || tab == TAB_SYS) s.cpuTemp = readCpuTemp();

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
        if (!lite) line.append("  ·  CPU ").append(s.cpuPct >= 0 ? s.cpuPct + "%" : "—");
        line.append("  ·  ").append(s.charging ? "⚡" : "Bat. ").append(s.batt >= 0 ? s.batt + "%" : "—");
        if (!Float.isNaN(s.battTemp)) line.append("  ·  ").append(Math.round(s.battTemp)).append("°C");
        statsLine.setText(line);
        checkProtection(s);
        trackDrain(s);

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

        bDrain = row(c, "Gasto médio");

        section(c, "MODO PROTEÇÃO (CALOR E BATERIA BAIXA)");
        TextView pNote = text(12, FAINT);
        pNote.setText("Quando o celular esquenta ou a bateria fica baixa, o launcher pausa animações, "
                + "mede menos e pode baixar o brilho e o tempo de tela sozinho. Volta ao normal quando esfria.");
        pNote.setLineSpacing(dp(2), 1f);
        pNote.setPadding(0, 0, 0, dp(6));
        c.addView(pNote);
        pStatus = row(c, "Proteção automática");
        ((View) pStatus.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("protecao", !prefs.getBoolean("protecao", true)).apply();
                if (!prefs.getBoolean("protecao", true) && protectMode) setProtect(false, "");
                updateProtectRows();
            }
        });
        pLimit = row(c, "Ligar a partir de");
        ((View) pLimit.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = prefs.getInt("limite", 40);
                int next = cur >= 42 ? 37 : cur + 1;
                prefs.edit().putInt("limite", next).apply();
                updateProtectRows();
            }
        });
        pBright = row(c, "Baixar brilho e tempo de tela");
        ((View) pBright.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!canWriteSettings()) { askWriteSettings(); return; }
                prefs.edit().putBoolean("auto_brilho", !prefs.getBoolean("auto_brilho", true)).apply();
                updateProtectRows();
            }
        });
        TextView coolBtn = row(c, "Esfriar agora");
        coolBtn.setText("Esfriar ›");
        coolBtn.setTextColor(ACCENT);
        ((View) coolBtn.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { coolDownNow(); }
        });

        section(c, "QUEM MAIS GASTOU HOJE");
        usageNote = text(12, FAINT);
        usageNote.setText("Tempo que cada app ficou ativo desde 0h, na tela e em segundo plano. "
                + "Quanto mais tempo, mais bateria. Toque num app para frear ele.");
        usageNote.setLineSpacing(dp(2), 1f);
        usageNote.setPadding(0, 0, 0, dp(8));
        c.addView(usageNote);

        usagePerm = text(14, ACCENT);
        usagePerm.setText("✦  Toque para liberar o \"Acesso ao uso\" para o Leve Launcher");
        usagePerm.setBackground(pill(0xFF000000, ACCENT));
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

    private int lastBatt = -1;

    private void showBattery(Stats s) {
        lastBatt = s.batt;
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
        if (bDrain != null) bDrain.setText(drainText());
        if (pStatus != null) updateProtectRows();
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

    private TextView themeValue, uiAnimToggle;

    private void updateAnimation() {
        boolean wanted = prefs.getBoolean("anim", true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean saver = pm != null && pm.isPowerSaveMode();
        boolean uiWanted = prefs.getBoolean("anim_ui", true);
        uiAnim = uiWanted && !saver && !lite && !protectMode;
        // sem animação: a lista nem prepara a entrada em cascata (economiza no celular fraco)
        if (list != null && (list.getLayoutAnimation() != null) != uiAnim) list.setLayoutAnimation(uiAnim ? listEntrance() : null);
        if (essList != null && (essList.getLayoutAnimation() != null) != uiAnim) essList.setLayoutAnimation(uiAnim ? listEntrance() : null);
        if (uiAnimToggle != null) {
            uiAnimToggle.setText(!uiWanted ? "Desligadas" : saver ? "Pausadas (economia)" : "Ligadas");
        }
        if (animToggle != null) {
            animToggle.setText(!wanted ? "Desligada" : saver ? "Pausada (economia)" : "Ligada");
        }
        if (liteToggle != null) liteToggle.setText(lite ? "Ligado" : "Desligado");
        stars.setRunning(wanted && !saver && resumed && !lite && !protectMode);
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

    // ---------- Modo celular fraco ----------

    private volatile boolean lite;
    private volatile boolean metaLoaded;
    private TextView liteToggle;

    /** Celular simples: o Android marca como "pouca RAM", tem até 3 GB ou até 4 núcleos. */
    private boolean isWeakPhone() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am.isLowRamDevice()) return true;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0 && mi.totalMem <= 3.3 * 1024 * 1024 * 1024) return true;
        } catch (Exception ignored) { }
        return Runtime.getRuntime().availableProcessors() <= 4;
    }

    private boolean hasXiaomiCleaner() {
        PackageManager pm = getPackageManager();
        return pm.getLaunchIntentForPackage("com.miui.cleanmaster") != null
                || pm.getLaunchIntentForPackage("com.miui.securitycenter") != null;
    }

    /** Fecha os apps parados em segundo plano e mostra quanto de RAM sobrou livre. */
    private void freeRam() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final List<String> pkgs = new ArrayList<>();
        for (App a : allApps) {
            String p = a.component.getPackageName();
            if (!pkgs.contains(p)) pkgs.add(p);
        }
        Toast.makeText(this, "Liberando memória…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(before);
                for (String p : pkgs) {
                    try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
                }
                try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                final ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(after);
                final long freed = Math.max(0, after.availMem - before.availMem) / (1024 * 1024);
                ui.post(new Runnable() {
                    @Override public void run() {
                        String free = String.format(BR, "%.1f GB", after.availMem / 1073741824.0);
                        Toast.makeText(MainActivity.this,
                                (freed > 0 ? "Liberados " + freed + " MB. " : "Memória já estava limpa. ")
                                        + "Livre agora: " + free, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** Quando o Android pede memória de volta, o launcher solta o que não está na tela. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && usageBox != null) usageBox.removeAllViews();
        if (lite && level >= TRIM_MEMORY_UI_HIDDEN) {
            cpuMax = null;
            thermalPaths = null;
        }
    }

    // ---------- Modo Proteção: calor e bateria baixa ----------

    private boolean protectMode;
    private LinearLayout protectCard;
    private TextView protectTitle, bDrain, pStatus, pLimit, pBright;

    private void checkProtection(Stats s) {
        if (!prefs.getBoolean("protecao", true)) return;
        int limit = prefs.getInt("limite", 40);
        boolean hot = !Float.isNaN(s.battTemp) && s.battTemp >= limit;
        boolean cool = Float.isNaN(s.battTemp) || s.battTemp <= limit - 2;
        boolean low = s.batt >= 0 && s.batt <= 15 && !s.charging;
        if (!protectMode && (hot || low)) {
            setProtect(true, hot ? "🌡  Celular quente (" + Math.round(s.battTemp) + "°C) — Modo Proteção ligado"
                    : "🔋  Bateria baixa (" + s.batt + "%) — Modo Proteção ligado");
            if (hot) applyCoolScreen();
        } else if (protectMode && cool && !low) {
            setProtect(false, "");
            Toast.makeText(this, "Celular normal de novo — tudo voltou como antes", Toast.LENGTH_SHORT).show();
        } else if (protectMode && hot) {
            protectTitle.setText("🌡  Celular quente (" + Math.round(s.battTemp) + "°C) — Modo Proteção ligado");
        }
    }

    private void setProtect(boolean on, String title) {
        protectMode = on;
        protectCard.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) protectTitle.setText(title);
        else restoreScreen();
        updateAnimation();
        updateProtectRows();
    }

    /** Toque no cartão: fecha apps parados e baixa o brilho na hora. */
    private void coolDownNow() {
        freeRam();
        if (canWriteSettings()) {
            applyCoolScreen();
            Toast.makeText(this, "Brilho e tempo de tela reduzidos até o celular esfriar", Toast.LENGTH_LONG).show();
        } else {
            askWriteSettings();
        }
    }

    private boolean canWriteSettings() {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
    }

    private void askWriteSettings() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Baixar brilho sozinho")
                .setMessage("Para baixar o brilho e o tempo de tela quando o celular esquentar, "
                        + "permita \"Alterar configurações do sistema\" para o Leve Launcher. "
                        + "Tudo volta ao normal quando ele esfriar.")
                .setPositiveButton("Permitir", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        tryStart(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Agora não", null)
                .show();
    }

    /** Brilho mais baixo e tela apagando em 30 s; guarda como estava para devolver depois. */
    private void applyCoolScreen() {
        if (!prefs.getBoolean("auto_brilho", true) || !canWriteSettings()) return;
        if (prefs.getBoolean("tela_reduzida", false)) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int bright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            int timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60000);
            prefs.edit().putBoolean("tela_reduzida", true).putInt("old_mode", mode)
                    .putInt("old_bright", bright).putInt("old_timeout", timeout).apply();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(20, Math.min(bright, 255) * 45 / 100));
            if (timeout > 30000) Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
        } catch (Exception ignored) { }
    }

    private void restoreScreen() {
        if (!prefs.getBoolean("tela_reduzida", false) || !canWriteSettings()) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("old_bright", 128));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("old_mode", 1));
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt("old_timeout", 60000));
        } catch (Exception ignored) { }
        prefs.edit().putBoolean("tela_reduzida", false).apply();
    }

    private void updateProtectRows() {
        if (pStatus == null) return;
        boolean on = prefs.getBoolean("protecao", true);
        pStatus.setText(!on ? "Desligada" : protectMode ? "ATIVA agora" : "Ligada");
        pStatus.setTextColor(protectMode ? 0xFFFFB199 : TEXT);
        pLimit.setText(prefs.getInt("limite", 40) + " °C ou bateria ≤ 15% ›");
        pBright.setText(!canWriteSettings() ? "Permitir ›"
                : prefs.getBoolean("auto_brilho", true) ? "Sim" : "Não");
        pBright.setTextColor(!canWriteSettings() ? ACCENT : TEXT);
    }

    /** Gasto médio em %/hora, medido enquanto a bateria descarrega (só com o launcher aberto). */
    private void trackDrain(Stats s) {
        if (s.batt < 0) return;
        long now = System.currentTimeMillis();
        if (s.charging || s.full) {
            prefs.edit().remove("drain_t").remove("drain_l").apply();
            return;
        }
        if (!prefs.contains("drain_t") || s.batt > prefs.getInt("drain_l", 0)) {
            prefs.edit().putLong("drain_t", now).putInt("drain_l", s.batt).apply();
        }
    }

    private String drainText() {
        if (!prefs.contains("drain_t")) return "medindo… (desconectado do carregador)";
        long t0 = prefs.getLong("drain_t", 0);
        double hours = (System.currentTimeMillis() - t0) / 3600000.0;
        int lost = prefs.getInt("drain_l", 0) - lastBatt;
        if (hours < 0.25 || lost <= 0 || lastBatt < 0) return "medindo…";
        double rate = lost / hours;
        String txt = String.format(BR, "%.1f%% por hora", rate);
        if (rate >= 15) txt += "  ⚠ alto";
        return txt;
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
        // Android 10+: pergunta direto ao sistema quem é a tela inicial (o jeito antigo erra no Samsung/Xiaomi)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(RoleManager.ROLE_HOME)) return true;
                }
            } catch (Exception ignored) { }
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** O aviso só aparece se o Leve não for a tela inicial E você não tiver escondido. */
    private void updateDefaultBanner() {
        boolean isDefault = isDefaultHome();
        if (isDefault) prefs.edit().putBoolean("banner_off", true).apply();
        defaultBanner.setVisibility(isDefault || prefs.getBoolean("banner_off", false) ? View.GONE : View.VISIBLE);
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
        if (googleInput != null) {
            if (googleInput.length() > 0) googleInput.setText("");
            googleInput.clearFocus();
            hideKeyboardOf(googleInput);
        }
        if (homeRoot != null) homeRoot.requestFocus();
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
        TextView tv = text(ROW_SP, TEXT);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(0, dp(ROW_PAD), 0, dp(ROW_PAD));
        return tv;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int p) {
            Object o = rows.get(p);
            return o instanceof String ? 0 : o == GOOGLE_ROW ? 2 : 1;
        }
        @Override public boolean isEnabled(int p) { return !(rows.get(p) instanceof String); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            TextView tv = (TextView) convertView;
            if (o == GOOGLE_ROW) {
                if (tv == null) {
                    tv = appRowView();
                    tv.setTextColor(ACCENT);
                    tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                tv.setText("⌕  Pesquisar \u201C" + searchBox.getText().toString().trim() + "\u201D no Google");
            } else if (o instanceof String) {
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
                tv.setText("+  Adicionar atalho  (app, site, WhatsApp…)");
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
            bg.setColor(0xFF3A3F55);
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
                boolean tinted = r.nextFloat() > 0.7f;
                colors[i] = tinted ? Color.argb(alpha, STAR_R, STAR_G, STAR_B) : Color.argb(alpha, 230, 230, 240);
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
