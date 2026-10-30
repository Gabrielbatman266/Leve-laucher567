package com.leve.profissional;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
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
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Launcher Leve Profissional — tela inicial limpa, com letras e botões grandes.
 * Pensado para quem quer facilidade e rapidez (e para pessoas mais velhas).
 *
 * Tela inicial: relógio grande, dia, tempo, bateria; botões Limpar celular,
 * Não perturbe, Carregar melhor e Configurações; grade com os apps principais
 * (telefone, WhatsApp, YouTube, Facebook, Kwai, bancos...) e "Todos os aplicativos".
 */
public class MainActivity extends Activity {

    // ---------- Cores claras, bem nítidas ----------
    static final int BG = 0xFFF3F6FC;
    static final int TILE = 0xFFFFFFFF;
    static final int TILE_LINE = 0xFFE1E7F2;
    static final int TEXT = 0xFF111827;
    static final int MUTED = 0xFF4B5567;
    static final int ACCENT = 0xFF1E5BD8;
    static final int ON_ACCENT = 0xFFFFFFFF;
    static final int GOOD = 0xFF13803B;
    static final int WARN = 0xFFC62828;

    private static final int REQ_HOME = 7, REQ_LOCATION = 8;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final float[] SCALES = {1.0f, 1.15f, 1.3f};
    private static final String[] SCALE_NAMES = {"Normal", "Grande", "Muito grande"};

    private static final class App {
        final String label, search, key;
        final ComponentName component;
        final UserHandle user;
        final boolean clone;
        final LauncherActivityInfo info;

        App(LauncherActivityInfo info, String label, UserHandle user, long serial, boolean clone) {
            this.info = info;
            this.label = label;
            this.component = info.getComponentName();
            this.user = user;
            this.clone = clone;
            this.search = normalize(label);
            this.key = component.flattenToShortString() + "#" + serial;
        }

        String display() { return clone ? label + " (2)" : label; }
    }

    private final List<App> allApps = new ArrayList<>();
    private final Map<String, App> byKey = new HashMap<>();
    private final List<App> shown = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(90);

    private SharedPreferences prefs;
    private LauncherApps launcherApps;
    private UserManager userManager;
    private LauncherApps.Callback appsCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread workThread;
    private Handler work;
    private float scale = 1f;
    private volatile int loadGeneration = 0;
    private boolean onAllApps = false;

    // Views
    private View homePage, allPage;
    private TextView defaultBanner, weatherLine, batteryLine, dndLabel, dndIcon;
    private LinearLayout grid;
    private EditText searchBox;
    private ListView list;
    private ListAdapter adapter;
    private BroadcastReceiver batteryReceiver;

    // ================================================================
    // Ciclo de vida
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pro", MODE_PRIVATE);
        scale = SCALES[Math.max(0, Math.min(2, prefs.getInt("letra", 1)))];
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) getSystemService(USER_SERVICE);

        workThread = new HandlerThread("pro-work");
        workThread.start();
        work = new Handler(workThread.getLooper());

        setContentView(buildUi());

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
        defaultBanner.setVisibility(isDefaultHome() ? View.GONE : View.VISIBLE);
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { showBattery(i); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = Build.VERSION.SDK_INT >= 33
                ? registerReceiver(batteryReceiver, f, Context.RECEIVER_EXPORTED)
                : registerReceiver(batteryReceiver, f);
        if (sticky != null) showBattery(sticky);
        updateDnd();
        refreshWeather(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
            batteryReceiver = null;
        }
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
        goHome();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME && !isDefaultHome()) openHomeSettings();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION) refreshWeather(true);
    }

    // ================================================================
    // Interface
    // ================================================================

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** Cartão branco com borda fina e sombra suave. */
    private void card(View v, float radius) {
        v.setBackground(box(TILE, TILE_LINE, radius));
        v.setElevation(dp(3));
    }

    /** Texto com tamanho já ajustado ao "Tamanho da letra" escolhido. */
    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        t.setTextColor(color);
        return t;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new SkyDrawable());
        getWindow().setStatusBarColor(0xFFDDE8FF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        homePage = buildHome();
        allPage = buildAllApps();
        frame.addView(homePage, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.addView(allPage, new FrameLayout.LayoutParams(MATCH, MATCH));
        allPage.setVisibility(View.GONE);
        return frame;
    }

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(20), dp(18), dp(28));
        c.setClipChildren(false);
        scroll.addView(c);

        defaultBanner = text(17, ON_ACCENT);
        defaultBanner.setText("Toque aqui para usar este launcher como tela inicial");
        defaultBanner.setTypeface(Typeface.DEFAULT_BOLD);
        defaultBanner.setGravity(Gravity.CENTER);
        defaultBanner.setBackground(box(ACCENT, 0, 16));
        defaultBanner.setElevation(dp(4));
        defaultBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        defaultBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDefaultHome(); }
        });
        LinearLayout.LayoutParams bp = lp(MATCH, WRAP);
        bp.bottomMargin = dp(16);
        c.addView(defaultBanner, bp);

        // ----- Relógio grande -----
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(TEXT);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 92 * Math.min(scale, 1.15f));
        clock.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)); }
        });
        c.addView(clock, lp(MATCH, WRAP));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextColor(ACCENT);
        date.setTypeface(Typeface.DEFAULT_BOLD);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22 * scale);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(date, lp(MATCH, WRAP));

        // ----- Tempo e bateria -----
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setClipChildren(false);
        LinearLayout.LayoutParams ip = lp(MATCH, WRAP);
        ip.topMargin = dp(16);
        c.addView(info, ip);

        weatherLine = text(19, TEXT);
        weatherLine.setText("☁  Ver o tempo");
        weatherLine.setGravity(Gravity.CENTER);
        card(weatherLine, 18);
        weatherLine.setPadding(dp(10), dp(14), dp(10), dp(14));
        weatherLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onWeatherTap(); }
        });
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        wp.rightMargin = dp(10);
        info.addView(weatherLine, wp);

        batteryLine = text(19, TEXT);
        batteryLine.setText("🔋  —");
        batteryLine.setGravity(Gravity.CENTER);
        card(batteryLine, 18);
        batteryLine.setPadding(dp(10), dp(14), dp(10), dp(14));
        batteryLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!startQuiet(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY))) tryStart(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        info.addView(batteryLine, new LinearLayout.LayoutParams(0, MATCH, 1f));

        // ----- Botões rápidos 2x2 -----
        c.addView(sectionTitle("Atalhos rápidos"));
        LinearLayout r1 = row(c);
        r1.addView(quickTile("🧹", "Limpar celular", new View.OnClickListener() {
            @Override public void onClick(View v) { openCleaner(); }
        }), half(true));
        View dnd = quickTile("🔕", "Não perturbe", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleDnd(); }
        });
        dndIcon = (TextView) ((LinearLayout) dnd).getChildAt(0);
        dndLabel = (TextView) ((LinearLayout) dnd).getChildAt(1);
        r1.addView(dnd, half(false));

        LinearLayout r2 = row(c);
        r2.addView(quickTile("⚡", "Carregar melhor", new View.OnClickListener() {
            @Override public void onClick(View v) { optimizeCharging(); }
        }), half(true));
        r2.addView(quickTile("⚙️", "Configurações", new View.OnClickListener() {
            @Override public void onClick(View v) { tryStart(new Intent(Settings.ACTION_SETTINGS)); }
        }), half(false));

        // ----- Meus aplicativos -----
        c.addView(sectionTitle("Meus aplicativos"));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        c.addView(grid, lp(MATCH, WRAP));

        TextView all = text(20, ON_ACCENT);
        all.setText("Todos os aplicativos  ›");
        all.setTypeface(Typeface.DEFAULT_BOLD);
        all.setGravity(Gravity.CENTER);
        all.setBackground(box(ACCENT, 0, 18));
        all.setElevation(dp(4));
        all.setPadding(dp(16), dp(18), dp(16), dp(18));
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAllApps(); }
        });
        LinearLayout.LayoutParams ap = lp(MATCH, WRAP);
        ap.topMargin = dp(14);
        c.addView(all, ap);

        // ----- Ajustes -----
        LinearLayout r3 = row(c);
        ((LinearLayout.LayoutParams) r3.getLayoutParams()).topMargin = dp(22);
        TextView size = smallButton("Aa  Tamanho da letra");
        size.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseTextSize(); }
        });
        r3.addView(size, half(true));
        TextView home = smallButton("⌂  Tela inicial");
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openHomeSettings(); }
        });
        r3.addView(home, half(false));

        TextView upd = smallButton("⟳  Atualizar o launcher  (v" + Updater.installedVersion(this) + ")");
        upd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Updater(MainActivity.this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).check();
            }
        });
        LinearLayout.LayoutParams up = lp(MATCH, WRAP);
        up.topMargin = dp(2);
        c.addView(upd, up);

        TextView tip = text(14, MUTED);
        tip.setText("Dica: toque em \"+ Adicionar aplicativo\" para escolher seus apps. "
                + "Segure o dedo num app para tirar ou mudar de lugar.");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(16), 0, 0);
        c.addView(tip, lp(MATCH, WRAP));
        return scroll;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(16, ACCENT);
        t.setText(s.toUpperCase(new Locale("pt", "BR")));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(24), 0, dp(10));
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = dp(12);
        parent.addView(r, p);
        return r;
    }

    private LinearLayout.LayoutParams half(boolean left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, 1f);
        if (left) p.rightMargin = dp(10);
        return p;
    }

    private View quickTile(String emoji, String label, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        card(t, 20);
        t.setPadding(dp(8), dp(16), dp(8), dp(16));
        TextView e = text(30, TEXT);
        e.setText(emoji);
        e.setGravity(Gravity.CENTER);
        t.addView(e);
        TextView l = text(18, TEXT);
        l.setText(label);
        l.setGravity(Gravity.CENTER);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(0, dp(6), 0, 0);
        t.addView(l);
        t.setOnClickListener(click);
        return t;
    }

    private TextView smallButton(String s) {
        TextView b = text(16, TEXT);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        card(b, 16);
        b.setPadding(dp(8), dp(14), dp(8), dp(14));
        return b;
    }

    // ---------- Grade de apps da tela inicial ----------

    private void rebuildGrid() {
        grid.removeAllViews();
        List<App> apps = new ArrayList<>();
        for (String k : homeKeys()) {
            App a = byKey.get(k);
            if (a != null) apps.add(a);
        }
        LinearLayout r = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 2 == 0) r = row(grid);
            final App a = apps.get(i);
            final int pos = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            card(tile, 20);
            tile.setPadding(dp(8), dp(14), dp(8), dp(14));
            ImageView icon = new ImageView(this);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(56 * Math.min(scale, 1.15f)), dp(56 * Math.min(scale, 1.15f))));
            loadIcon(a, icon);
            TextView l = text(18, TEXT);
            l.setText(a.display());
            l.setGravity(Gravity.CENTER);
            l.setMaxLines(2);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setPadding(0, dp(8), 0, 0);
            tile.addView(l, lp(MATCH, WRAP));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { launch(a); }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { homeTileMenu(a, pos); return true; }
            });
            r.addView(tile, half(i % 2 == 0));
        }
        // Último quadrado: adicionar aplicativos
        if (apps.size() % 2 == 0) r = row(grid);
        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.VERTICAL);
        add.setGravity(Gravity.CENTER);
        GradientDrawable dash = box(0xFFEAF1FF, 0, 20);
        dash.setStroke(dp(2), ACCENT, dp(8), dp(6));
        add.setBackground(dash);
        add.setPadding(dp(8), dp(14), dp(8), dp(14));
        TextView plus = text(40, ACCENT);
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        plus.setIncludeFontPadding(false);
        add.addView(plus);
        TextView al = text(18, ACCENT);
        al.setText("Adicionar\naplicativo");
        al.setGravity(Gravity.CENTER);
        al.setTypeface(Typeface.DEFAULT_BOLD);
        al.setPadding(0, dp(4), 0, 0);
        add.addView(al);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickApps(); }
        });
        boolean left = apps.size() % 2 == 0;
        r.addView(add, half(left));
        if (left) r.addView(new View(this), half(false));
    }

    /** Lista com caixinhas para marcar quais apps ficam na tela inicial. */
    private void pickApps() {
        if (allApps.isEmpty()) return;
        final List<String> current = currentGridKeys();
        final String[] names = new String[allApps.size()];
        final boolean[] checked = new boolean[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) {
            names[i] = allApps.get(i).display();
            checked[i] = current.contains(allApps.get(i).key);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Marque os aplicativos da tela inicial")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<String> keys = new ArrayList<>();
                        for (String k : current) {
                            int idx = indexOfKey(k);
                            if (idx >= 0 && checked[idx]) keys.add(k);
                        }
                        for (int i = 0; i < allApps.size(); i++) {
                            String k = allApps.get(i).key;
                            if (checked[i] && !keys.contains(k)) keys.add(k);
                        }
                        saveHomeKeys(keys);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < allApps.size(); i++) if (allApps.get(i).key.equals(key)) return i;
        return -1;
    }

    private void homeTileMenu(final App a, final int pos) {
        final List<String> keys = currentGridKeys();
        final List<String> opts = new ArrayList<>();
        if (pos > 0) opts.add("Mover para trás");
        if (pos < keys.size() - 1) opts.add("Mover para frente");
        opts.add("Tirar da tela inicial");
        opts.add("Informações do app");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(a.display())
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String o = opts.get(which);
                        if (o.startsWith("Mover para trás")) Collections.swap(keys, pos, pos - 1);
                        else if (o.startsWith("Mover para frente")) Collections.swap(keys, pos, pos + 1);
                        else if (o.startsWith("Tirar")) keys.remove(pos);
                        else { openAppInfo(a); return; }
                        saveHomeKeys(keys);
                    }
                }).show();
    }

    /** Chaves na ordem em que aparecem (ignora apps desinstalados). */
    private List<String> currentGridKeys() {
        List<String> k = new ArrayList<>();
        for (String s : homeKeys()) if (byKey.containsKey(s)) k.add(s);
        return k;
    }

    private List<String> homeKeys() {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString("home", "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void saveHomeKeys(List<String> keys) {
        prefs.edit().putString("home", TextUtils.join("\n", keys)).apply();
        rebuildGrid();
    }

    // ---------- Página "Todos os aplicativos" ----------

    private View buildAllApps() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), 0);

        TextView back = text(20, ACCENT);
        back.setText("‹  Voltar");
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(4), dp(10), dp(4), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goHome(); }
        });
        page.addView(back);

        searchBox = new EditText(this);
        searchBox.setHint("🔍  Procurar aplicativo");
        searchBox.setHintTextColor(MUTED);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * scale);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        card(searchBox, 18);
        searchBox.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP);
        sp.topMargin = dp(6);
        sp.bottomMargin = dp(8);
        page.addView(searchBox, sp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (!shown.isEmpty()) launch(shown.get(0));
                return true;
            }
        });

        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        adapter = new ListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { launch(shown.get(pos)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                listMenu(shown.get(pos));
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { if (s != SCROLL_STATE_IDLE) hideKeyboard(); }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        page.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return page;
    }

    private void listMenu(final App a) {
        final List<String> keys = currentGridKeys();
        final boolean onHome = keys.contains(a.key);
        final String[] opts = {
                onHome ? "Tirar da tela inicial" : "Colocar na tela inicial",
                "Informações do app / desinstalar"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(a.display())
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            if (onHome) keys.remove(a.key); else keys.add(a.key);
                            saveHomeKeys(keys);
                            Toast.makeText(MainActivity.this,
                                    onHome ? "Tirado da tela inicial" : "Colocado na tela inicial",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            openAppInfo(a);
                        }
                    }
                }).show();
    }

    private void showAllApps() {
        onAllApps = true;
        homePage.setVisibility(View.GONE);
        allPage.setVisibility(View.VISIBLE);
        filter();
        list.setSelection(0);
    }

    private void goHome() {
        if (searchBox.length() > 0) searchBox.setText("");
        hideKeyboard();
        if (onAllApps) {
            onAllApps = false;
            allPage.setVisibility(View.GONE);
            homePage.setVisibility(View.VISIBLE);
        } else if (homePage instanceof ScrollView) {
            ((ScrollView) homePage).smoothScrollTo(0, 0);
        }
    }

    private void filter() {
        shown.clear();
        String q = normalize(searchBox.getText().toString());
        if (q.isEmpty()) {
            shown.addAll(allApps);
        } else {
            List<App> contains = new ArrayList<>();
            for (App a : allApps) {
                if (a.search.startsWith(q)) shown.add(a);
                else if (a.search.contains(q)) contains.add(a);
            }
            shown.addAll(contains);
        }
        adapter.notifyDataSetChanged();
    }

    private final class ListAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                ImageView icon = new ImageView(MainActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
                TextView name = text(22, TEXT);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(16), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
            }
            App a = shown.get(position);
            ((TextView) row.getChildAt(1)).setText(a.display());
            loadIcon(a, (ImageView) row.getChildAt(0));
            return row;
        }
    }

    // ================================================================
    // Apps
    // ================================================================

    private void loadApps() {
        final int gen = ++loadGeneration;
        work.post(new Runnable() {
            @Override public void run() {
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
                final Collator col = Collator.getInstance(new Locale("pt", "BR"));
                col.setStrength(Collator.PRIMARY);
                Collections.sort(result, new Comparator<App>() {
                    @Override public int compare(App a, App b) {
                        int c = col.compare(a.label, b.label);
                        return c != 0 ? c : (a.clone ? 1 : 0) - (b.clone ? 1 : 0);
                    }
                });
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGeneration || isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(result);
                        byKey.clear();
                        for (App a : allApps) byKey.put(a.key, a);
                        if (!prefs.getBoolean("home_init", false) && !allApps.isEmpty()) createDefaultHome();
                        rebuildGrid();
                        if (onAllApps) filter();
                    }
                });
            }
        });
    }

    /** Primeira vez: monta a tela com os apps mais importantes que estiverem instalados. */
    private void createDefaultHome() {
        List<String> keys = new ArrayList<>();
        // Telefone, mensagens e câmera padrão do aparelho
        addPackage(keys, defaultPackage(new Intent(Intent.ACTION_DIAL)));
        addLabel(keys, "whatsapp");
        String sms = null;
        try { sms = Telephony.Sms.getDefaultSmsPackage(this); } catch (Exception ignored) { }
        addPackage(keys, sms);
        addPackage(keys, defaultPackage(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
        addLabel(keys, "galeria");
        addLabel(keys, "youtube");
        addLabel(keys, "facebook");
        addLabel(keys, "kwai");
        addLabel(keys, "instagram");
        // Bancos
        for (App a : allApps) {
            if (!a.clone && isBank(a.search) && !keys.contains(a.key)) keys.add(a.key);
        }
        prefs.edit().putBoolean("home_init", true).putString("home", TextUtils.join("\n", keys)).apply();
    }

    private static final String[] BANKS = {
            "nubank", "itau", "bradesco", "caixa", "banco do brasil", "santander", "banco inter",
            "inter&co", "picpay", "mercado pago", "c6 bank", "sicredi", "sicoob", "pagbank",
            "pagseguro", "neon", "banrisul", "banco pan", "bmg", "will bank", "btg", "banco original",
            "digio", "agibank", "nomad", "recargapay", "superdigital", "mercantil", "daycoval", "safra",
            "banco do nordeste", "brb", "banestes", "cresol", "sofisa", "next", "ame digital"
    };
    private static final String[] BANKS_EXACT = {"bb", "inter", "c6", "pan", "next", "itau", "caixa tem"};

    private static boolean isBank(String s) {
        for (String e : BANKS_EXACT) if (s.equals(e)) return true;
        for (String b : BANKS) {
            if (b.equals("next") || b.equals("neon")) {
                if (s.equals(b) || s.startsWith(b + " ")) return true;
            } else if (s.contains(b)) return true;
        }
        return false;
    }

    private String defaultPackage(Intent i) {
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                return ri.activityInfo.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void addPackage(List<String> keys, String pkg) {
        if (pkg == null) return;
        for (App a : allApps) {
            if (!a.clone && a.component.getPackageName().equals(pkg)) {
                if (!keys.contains(a.key)) keys.add(a.key);
                return;
            }
        }
    }

    private void addLabel(List<String> keys, String word) {
        for (App a : allApps) {
            if (!a.clone && a.search.contains(word)) {
                if (!keys.contains(a.key)) keys.add(a.key);
                return;
            }
        }
    }

    private void loadIcon(final App a, final ImageView view) {
        view.setTag(a.key);
        Drawable d = icons.get(a.key);
        if (d != null) {
            view.setImageDrawable(d);
            return;
        }
        view.setImageDrawable(null);
        work.post(new Runnable() {
            @Override public void run() {
                Drawable icon;
                try {
                    icon = a.info.getBadgedIcon(0);
                } catch (Exception e) {
                    icon = null;
                }
                final Drawable fin = icon;
                if (fin != null) icons.put(a.key, fin);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (a.key.equals(view.getTag())) view.setImageDrawable(fin);
                    }
                });
            }
        });
    }

    private void launch(App a) {
        try {
            launcherApps.startMainActivity(a.component, a.user, null, null);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
            return;
        }
        goHome();
    }

    private void openAppInfo(App a) {
        try {
            launcherApps.startAppDetailsActivity(a.component, a.user, null, null);
        } catch (Exception e) {
            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.component.getPackageName(), null)));
        }
    }

    // ================================================================
    // Bateria
    // ================================================================

    private void showBattery(Intent b) {
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level >= 0 && sc > 0 ? Math.round(level * 100f / sc) : -1;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = status == BatteryManager.BATTERY_STATUS_FULL;
        String s;
        if (pct < 0) s = "🔋  —";
        else if (full) s = "🔋  " + pct + "%\nCarregada";
        else if (charging) {
            String extra = "Carregando";
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    long ms = ((BatteryManager) getSystemService(BATTERY_SERVICE)).computeChargeTimeRemaining();
                    if (ms > 0) extra = "Cheia em " + formatDur(ms);
                } catch (Exception ignored) { }
            }
            s = "⚡  " + pct + "%\n" + extra;
        } else s = "🔋  " + pct + "%";
        batteryLine.setText(s);
        batteryLine.setTextColor(charging || full ? GOOD : pct >= 0 && pct <= 15 ? WARN : TEXT);
    }

    private static String formatDur(long ms) {
        long min = ms / 60000;
        if (min < 1) return "1 min";
        long h = min / 60, m = min % 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** Fecha o que está rodando em segundo plano e dá dicas para carregar mais rápido. */
    private void optimizeCharging() {
        int closed = closeBackgroundApps();
        String msg = (closed > 0 ? "Fechei " + closed + " aplicativos que estavam em segundo plano.\n\n" : "")
                + "Para carregar mais rápido:\n\n"
                + "•  Use o carregador e o cabo originais\n"
                + "•  Deixe a tela desligada\n"
                + "•  Ligue o modo avião se não precisar receber ligações\n"
                + "•  Tire a capinha se o celular esquentar";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("⚡  Carregar melhor")
                .setMessage(msg)
                .setPositiveButton("Ligar modo avião", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        tryStart(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                    }
                })
                .setNegativeButton("Pronto", null)
                .show();
    }

    /** Fecha os processos em segundo plano (em outra thread para não travar a tela). */
    private int closeBackgroundApps() {
        final List<String> pkgs = new ArrayList<>();
        for (App a : allApps) {
            String p = a.component.getPackageName();
            if (!pkgs.contains(p)) pkgs.add(p);
        }
        work.post(new Runnable() {
            @Override public void run() {
                try {
                    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    for (String p : pkgs) am.killBackgroundProcesses(p);
                } catch (Exception ignored) { }
            }
        });
        return pkgs.size();
    }

    // ================================================================
    // Não perturbe
    // ================================================================

    private void toggleDnd() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!nm.isNotificationPolicyAccessGranted()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("🔕  Não perturbe")
                    .setMessage("Para ligar e desligar o Não perturbe com um toque, permita o acesso "
                            + "para \"Launcher Leve Profissional\" na próxima tela. Só precisa fazer isso uma vez.")
                    .setPositiveButton("Permitir", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            tryStart(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                        }
                    })
                    .setNegativeButton("Agora não", null)
                    .show();
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        try {
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                    : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            Toast.makeText(this, on ? "Não perturbe DESLIGADO" : "Não perturbe LIGADO", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tryStart(new Intent(Settings.ACTION_SETTINGS));
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { updateDnd(); }
        }, 300);
    }

    private void updateDnd() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL
                && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        dndLabel.setText(on ? "Não perturbe\nLIGADO" : "Não perturbe");
        dndLabel.setTextColor(on ? ACCENT : TEXT);
        dndIcon.setText(on ? "🔕" : "🔔");
    }

    // ================================================================
    // Tempo (Open-Meteo, grátis; só a cidade aproximada)
    // ================================================================

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void onWeatherTap() {
        if (!hasLocation()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        Intent w = getPackageManager().getLaunchIntentForPackage("com.miui.weather2");
        if (w != null && startQuiet(w)) return;
        refreshWeather(true);
    }

    private void refreshWeather(boolean force) {
        String cached = prefs.getString("tempo_txt", null);
        long at = prefs.getLong("tempo_at", 0);
        if (cached != null) weatherLine.setText(cached);
        if (!hasLocation()) return;
        if (!force && System.currentTimeMillis() - at < WEATHER_EVERY_MS) return;

        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location loc = null;
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (loc == null || l.getTime() > loc.getTime())) loc = l;
            }
        } catch (SecurityException ignored) { }
        if (loc != null) {
            fetchWeather(loc.getLatitude(), loc.getLongitude());
            return;
        }
        // Sem localização guardada: pede uma só vez pela rede (sem GPS)
        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                @Override public void onLocationChanged(Location l) { fetchWeather(l.getLatitude(), l.getLongitude()); }
                @Override public void onStatusChanged(String p, int s, Bundle e) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            }, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    private void fetchWeather(final double lat, final double lon) {
        work.post(new Runnable() {
            @Override public void run() {
                HttpURLConnection con = null;
                try {
                    URL url = new URL(String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                    + "&current=temperature_2m,weather_code"
                                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                            lat, lon));
                    con = (HttpURLConnection) url.openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject j = new JSONObject(sb.toString());
                    JSONObject cur = j.getJSONObject("current");
                    int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                    int code = cur.getInt("weather_code");
                    JSONObject daily = j.getJSONObject("daily");
                    int max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
                    int min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
                    final String txt = weatherEmoji(code) + "  " + temp + "°C\n" + weatherName(code)
                            + "\n↑" + max + "°  ↓" + min + "°";
                    prefs.edit().putString("tempo_txt", txt).putLong("tempo_at", System.currentTimeMillis()).apply();
                    ui.post(new Runnable() {
                        @Override public void run() { weatherLine.setText(txt); }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (con != null) con.disconnect();
                }
            }
        });
    }

    private static String weatherEmoji(int c) {
        if (c == 0) return "☀️";
        if (c <= 2) return "⛅";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c >= 51 && c <= 67) return "🌧️";
        if (c >= 71 && c <= 77) return "❄️";
        if (c >= 80 && c <= 82) return "🌦️";
        if (c >= 95) return "⛈️";
        return "☁️";
    }

    private static String weatherName(int c) {
        if (c == 0) return "Céu limpo";
        if (c <= 2) return "Poucas nuvens";
        if (c == 3) return "Nublado";
        if (c == 45 || c == 48) return "Neblina";
        if (c >= 51 && c <= 57) return "Garoa";
        if (c >= 61 && c <= 67) return "Chuva";
        if (c >= 71 && c <= 77) return "Neve";
        if (c >= 80 && c <= 82) return "Pancadas de chuva";
        if (c >= 95) return "Tempestade";
        return "Nublado";
    }

    // ================================================================
    // Limpar celular (limpador da própria Xiaomi)
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
        int n = closeBackgroundApps();
        Toast.makeText(this, "Fechei " + n + " apps em segundo plano", Toast.LENGTH_LONG).show();
    }

    // ================================================================
    // Tamanho da letra
    // ================================================================

    private void chooseTextSize() {
        int current = prefs.getInt("letra", 1);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Tamanho da letra")
                .setSingleChoiceItems(SCALE_NAMES, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs.edit().putInt("letra", which).apply();
                        d.dismiss();
                        recreate();
                    }
                }).show();
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
        Toast.makeText(this, "Escolha \"Launcher Leve Profissional\"", Toast.LENGTH_LONG).show();
        if (startQuiet(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
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

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // ================================================================
    // Fundo: degradê azul-claro para branco com dois brilhos suaves.
    // Desenhado só quando a tela muda de tamanho — não gasta bateria.
    // ================================================================

    private static final class SkyDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float w, h;

        @Override
        protected void onBoundsChange(Rect b) {
            w = b.width();
            h = b.height();
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xFFDDE8FF, 0xFFEFF4FF, 0xFFFFFFFF}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            glow1.setShader(new RadialGradient(w * 0.85f, h * 0.12f, w * 0.7f,
                    0x66C9D9FF, 0x00C9D9FF, Shader.TileMode.CLAMP));
            glow2.setShader(new RadialGradient(w * 0.1f, h * 0.55f, w * 0.8f,
                    0x40E6DCFF, 0x00E6DCFF, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            c.drawRect(0, 0, w, h, paint);
            c.drawRect(0, 0, w, h, glow1);
            c.drawRect(0, 0, w, h, glow2);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
