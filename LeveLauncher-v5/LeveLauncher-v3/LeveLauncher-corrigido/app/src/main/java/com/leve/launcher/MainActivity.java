package com.leve.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Leve Launcher v5 — tela inicial minimalista com tema espacial.
 *
 * Abas (toque no nome ou deslize para os lados):
 *   Essenciais  – seus atalhos organizados + controle de música
 *   Frequentes  – os apps que você mais abre
 *   Todos       – todos os apps (inclusive clones), separados por letra
 *   Sistema     – RAM, CPU, bateria, temperatura, armazenamento
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

    private static final int TAB_ESS = 0, TAB_FREQ = 1, TAB_ALL = 2, TAB_SYS = 3;
    private static final String[] TAB_NAMES = {"Essenciais", "Frequentes", "Todos", "Sistema"};

    private static final Locale BR = new Locale("pt", "BR");
    private static final String FLOW_URL = "https://labs.google/fx/tools/flow";

    // ---------- Modelos ----------

    private static final class App {
        final String label, search, key;
        final ComponentName component;
        final UserHandle user;
        final boolean clone;

        App(String label, ComponentName component, UserHandle user, long serial, boolean clone) {
            this.label = label;
            this.component = component;
            this.user = user;
            this.clone = clone;
            this.search = normalize(label);
            this.key = component.flattenToShortString() + "#" + serial;
        }

        String display() { return clone ? label + "  · clone" : label; }
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
    private final TextView[] tabViews = new TextView[4];
    private View pageEss, pageApps, pageSys;
    private EditText searchBox;
    private ListView list, essList;
    private RowAdapter adapter;
    private EssAdapter essAdapter;
    private GestureDetector swipe;
    // Sistema
    private TextView sRam, sStor, sCpu, sCpuTemp, sBatt, sBattStatus, sBattTemp, sCurrent, sVolt, sHealth, sUptime;
    private BarView barRam, barCpu, barBatt;

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
            @Override public void onClick(View v) { openBattery(); }
        }), quickParams(true));

        // --- Abas ---
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tp = lp(MATCH, WRAP);
        tp.topMargin = dp(20);
        root.addView(tabs, tp);
        for (int i = 0; i < 4; i++) {
            final int which = i;
            TextView t = text(14, FAINT);
            t.setText(TAB_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(which); }
            });
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }

        // --- Páginas ---
        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pageEss = buildEssPage();
        pageApps = buildAppsPage();
        pageSys = buildSysPage();
        pages.addView(pageEss, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageApps, new FrameLayout.LayoutParams(MATCH, MATCH));
        pages.addView(pageSys, new FrameLayout.LayoutParams(MATCH, MATCH));

        showTab(false);
        return frame;
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

        section(c, "BATERIA");
        sBatt = row(c, "Nível");
        barBatt = bar(c);
        sBattStatus = row(c, "Status");
        sBattTemp = row(c, "Temperatura");
        sCurrent = row(c, "Corrente agora");
        sVolt = row(c, "Voltagem");
        sHealth = row(c, "Saúde");

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
        if (t < 0 || t > 3 || t == tab) return;
        tab = t;
        hideKeyboard();
        if (searchBox.length() > 0) searchBox.setText("");
        showTab(true);
        refreshRows();
        if (t == TAB_SYS) {
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    private void showTab(boolean fade) {
        for (int i = 0; i < 4; i++) {
            tabViews[i].setTextColor(i == tab ? TEXT : FAINT);
        }
        View show = tab == TAB_ESS ? pageEss : tab == TAB_SYS ? pageSys : pageApps;
        pageEss.setVisibility(show == pageEss ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(show == pageApps ? View.VISIBLE : View.GONE);
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
                        rebuildEssentials();
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
        } else if (tab == TAB_FREQ) {
            rows.addAll(frequentApps());
            showEmpty = rows.isEmpty() && !allApps.isEmpty();
        } else if (tab == TAB_ALL) {
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
        emptyHint.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
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
        String[] wanted = {"ymusic", "nyx", "instagram", "threads", "flow", "claude", "chatgpt", "brave", "tiktok"};
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

    private void openMusicApp() {
        App y = findApp("ymusic");
        if (y == null) y = findApp("nyx");
        if (y != null) { launch(y); return; }
        if (!startQuiet(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))) {
            Toast.makeText(this, "Nenhum app de música encontrado", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateNowPlaying() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean playing = am != null && am.isMusicActive();
        nowPlaying.setText(playing ? "♪  Tocando agora" : "♪  Música");
        nowPlaying.setTextColor(playing ? ACCENT : MUTED);
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

        if (tab != TAB_SYS) return;
        sRam.setText(s.ramTotal > 0
                ? String.format(BR, "%.1f de %.1f GB (%d%%)", gb(s.ramUsed), gb(s.ramTotal), ramPct) : "—");
        barRam.setValue(ramPct);
        sStor.setText(s.storTotal > 0
                ? String.format(BR, "%.0f de %.0f GB", gb(s.storFree), gb(s.storTotal)) : "—");
        sCpu.setText(s.cpuPct >= 0 ? s.cpuPct + "%" : "não disponível");
        barCpu.setValue(s.cpuPct);
        sCpuTemp.setText(!Float.isNaN(s.cpuTemp) ? Math.round(s.cpuTemp) + " °C" : "bloqueada pela Xiaomi");
        sBatt.setText(s.batt >= 0 ? s.batt + "%" : "—");
        barBatt.setValue(s.batt);
        sBattStatus.setText(s.full ? "Cheia" : s.charging ? "Carregando" : "Descarregando");
        sBattTemp.setText(!Float.isNaN(s.battTemp) ? String.format(BR, "%.1f °C", s.battTemp) : "—");
        sBattTemp.setTextColor(!Float.isNaN(s.battTemp) && s.battTemp >= 40 ? 0xFFFF8A80 : TEXT);
        sCurrent.setText(s.currentMa != Integer.MIN_VALUE
                ? s.currentMa + " mA " + (s.charging ? "entrando" : "saindo") : "—");
        sVolt.setText(s.voltage > 0 ? String.format(BR, "%.2f V", s.voltage / 1000f) : "—");
        sHealth.setText(s.health);
        long min = SystemClock.elapsedRealtime() / 60000;
        long days = min / 1440, hours = (min % 1440) / 60, mins = min % 60;
        sUptime.setText(days > 0 ? days + " d " + hours + " h" : hours + " h " + mins + " min");
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
                tv.setText(((App) o).display());
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
