package com.leve.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

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
 * Leve Launcher v3 — tela inicial minimalista com tema espacial.
 *
 * Foco: velocidade e bateria. Fundo preto puro (economiza bateria em tela AMOLED),
 * sem ícones, sem widgets, sem animações, sem papel de parede e sem bibliotecas extras.
 *
 * - Aba "Frequentes": os apps que você mais abre.
 * - Aba "Todos": todos os apps em ordem alfabética, separados por letra.
 * - Busca: digite e aperte Enter para abrir o primeiro resultado.
 * - Acesso rápido: Limpar (limpador da Xiaomi), Config e Bateria.
 * - Toque longo num app: informações / desinstalar / remover dos frequentes.
 * - Toque no relógio: alarmes.
 */
public class MainActivity extends Activity {

    // ---------- Cores ----------
    private static final int BG = 0xFF000000;
    private static final int TEXT = 0xFFEDEFF7;
    private static final int MUTED = 0xFF7C8199;
    private static final int FAINT = 0xFF3A3E52;
    private static final int ACCENT = 0xFF9DB0FF;
    private static final int SURFACE = 0xFF0B0C14;

    private static final int MAX_FREQUENT = 12;
    private static final int REQ_HOME = 7;

    private static final int TAB_FREQUENT = 0;
    private static final int TAB_ALL = 1;

    private static final class App {
        final String label;
        final String search;
        final String key;
        final ComponentName component;

        App(String label, ComponentName component) {
            this.label = label;
            this.component = component;
            this.search = normalize(label);
            this.key = component.flattenToShortString();
        }
    }

    private final List<App> allApps = new ArrayList<>();
    /** Linhas mostradas na lista: String = cabeçalho de letra, App = app. */
    private final List<Object> rows = new ArrayList<>();
    private final Map<String, Integer> usage = new HashMap<>();

    private SharedPreferences prefs;
    private RowAdapter adapter;
    private EditText searchBox;
    private ListView list;
    private TextView tabFrequent, tabAll, emptyHint, defaultBanner;
    private int tab = TAB_FREQUENT;
    private BroadcastReceiver packageReceiver;
    private volatile int loadGeneration = 0;

    // ================================================================
    // Ciclo de vida
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("leve", MODE_PRIVATE);
        loadUsage();
        setContentView(buildUi());

        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                loadApps();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        loadApps();

        // Na primeira vez, já pede para virar a tela inicial padrão
        if (!prefs.getBoolean("pediu_padrao", false) && !isDefaultHome()) {
            prefs.edit().putBoolean("pediu_padrao", true).apply();
            requestDefaultHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        defaultBanner.setVisibility(isDefaultHome() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) unregisterReceiver(packageReceiver);
    }

    /** Botão Home apertado enquanto já está na tela inicial. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetHome();
    }

    /** Voltar só limpa a busca; a tela inicial nunca fecha. */
    @Override
    public void onBackPressed() {
        resetHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME && !isDefaultHome()) {
            // Alguns Xiaomi ignoram o pedido: abre a tela de escolha manual
            openHomeSettings();
        }
    }

    // ================================================================
    // Interface
    // ================================================================

    private int dp(float v) {
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

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new StarsDrawable(getResources().getDisplayMetrics().density));
        root.setPadding(dp(22), dp(28), dp(22), 0);

        // --- Aviso para virar a tela inicial padrão ---
        defaultBanner = new TextView(this);
        defaultBanner.setText("✦  Toque para tornar o Leve sua tela inicial");
        defaultBanner.setTextColor(ACCENT);
        defaultBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        defaultBanner.setBackground(pill(0xFF0D1024, 0xFF2B3566));
        defaultBanner.setPadding(dp(16), dp(10), dp(16), dp(10));
        defaultBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDefaultHome(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.bottomMargin = dp(16);
        root.addView(defaultBanner, bp);

        // --- Relógio e data ---
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(TEXT);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        root.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextColor(MUTED);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setLetterSpacing(0.04f);
        root.addView(date);

        // --- Acesso rápido ---
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qp.topMargin = dp(22);
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
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(26);
        root.addView(tabs, tp);

        tabFrequent = tabView("Frequentes", TAB_FREQUENT);
        tabAll = tabView("Todos", TAB_ALL);
        tabs.addView(tabFrequent);
        tabs.addView(tabAll);

        // --- Busca ---
        searchBox = new EditText(this);
        searchBox.setHint("Buscar app…");
        searchBox.setHintTextColor(FAINT);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchBox.setBackground(pill(SURFACE, 0xFF1C1F2E));
        searchBox.setPadding(dp(18), dp(11), dp(18), dp(11));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        sp.bottomMargin = dp(6);
        root.addView(searchBox, sp);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshRows(); }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                for (Object o : rows) {
                    if (o instanceof App) { launch((App) o); break; }
                }
                return true;
            }
        });

        // --- Texto quando não há frequentes ---
        emptyHint = new TextView(this);
        emptyHint.setText("Os apps que você mais usar vão aparecer aqui.\nToque em \"Todos\" para ver a lista completa.");
        emptyHint.setTextColor(MUTED);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyHint.setLineSpacing(dp(4), 1f);
        emptyHint.setPadding(0, dp(18), 0, 0);
        emptyHint.setVisibility(View.GONE);
        root.addView(emptyHint);

        // --- Lista de apps ---
        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(android.R.color.transparent);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setFastScrollEnabled(true);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof App) launch((App) o);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof App) showAppMenu((App) o);
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) hideKeyboard();
            }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateTabs();
        return root;
    }

    private TextView quickButton(String text, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(pill(SURFACE, 0xFF22263A));
        b.setPadding(dp(6), dp(11), dp(6), dp(11));
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams quickParams(boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!last) p.rightMargin = dp(8);
        return p;
    }

    private TextView tabView(String text, final int which) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(6), dp(22), dp(6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tab = which;
                updateTabs();
                refreshRows();
                list.setSelection(0);
            }
        });
        return t;
    }

    private void updateTabs() {
        styleTab(tabFrequent, tab == TAB_FREQUENT);
        styleTab(tabAll, tab == TAB_ALL);
    }

    private void styleTab(TextView t, boolean selected) {
        t.setTextColor(selected ? TEXT : FAINT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    // ================================================================
    // Apps
    // ================================================================

    /** Lê a lista de apps numa thread separada para não travar a tela. */
    private void loadApps() {
        final int gen = ++loadGeneration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
                final List<App> result = new ArrayList<>(infos.size());
                String self = getPackageName();
                for (ResolveInfo ri : infos) {
                    if (ri.activityInfo == null) continue;
                    if (self.equals(ri.activityInfo.packageName)) continue;
                    CharSequence label = ri.loadLabel(pm);
                    String name = label == null ? ri.activityInfo.packageName : label.toString().trim();
                    result.add(new App(name,
                            new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
                }
                final Collator collator = Collator.getInstance(Locale.getDefault());
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(result, new Comparator<App>() {
                    @Override public int compare(App a, App b) {
                        return collator.compare(a.label, b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGeneration || isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(result);
                        if (tab == TAB_FREQUENT && frequentApps().isEmpty()) {
                            tab = TAB_ALL;
                            updateTabs();
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
            @Override public int compare(App a, App b) {
                return usage.get(b.key) - usage.get(a.key);
            }
        });
        return f.size() > MAX_FREQUENT ? new ArrayList<>(f.subList(0, MAX_FREQUENT)) : f;
    }

    private void refreshRows() {
        rows.clear();
        String q = normalize(searchBox.getText().toString());
        boolean showEmpty = false;

        if (!q.isEmpty()) {
            // Busca em todos os apps: primeiro os que começam com o texto
            List<App> contains = new ArrayList<>();
            for (App a : allApps) {
                if (a.search.startsWith(q)) rows.add(a);
                else if (a.search.contains(q)) contains.add(a);
            }
            rows.addAll(contains);
        } else if (tab == TAB_FREQUENT) {
            rows.addAll(frequentApps());
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
        emptyHint.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static String firstLetter(String s) {
        if (s.isEmpty()) return "#";
        char c = Character.toUpperCase(s.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    private void launch(App app) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(app.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(i)) {
            Integer c = usage.get(app.key);
            usage.put(app.key, c == null ? 1 : c + 1);
            saveUsage(app.key);
            resetHome();
        }
    }

    private void showAppMenu(final App app) {
        final boolean inFrequent = usage.containsKey(app.key);
        String[] options = inFrequent
                ? new String[]{"Informações do app / desinstalar", "Remover dos frequentes"}
                : new String[]{"Informações do app / desinstalar"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", app.component.getPackageName(), null)));
                        } else {
                            usage.remove(app.key);
                            prefs.edit().remove("u:" + app.key).apply();
                            refreshRows();
                        }
                    }
                })
                .show();
    }

    // ---------- Contagem de uso (para a aba Frequentes) ----------

    private void loadUsage() {
        usage.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith("u:") && e.getValue() instanceof Integer) {
                usage.put(e.getKey().substring(2), (Integer) e.getValue());
            }
        }
    }

    private void saveUsage(String key) {
        prefs.edit().putInt("u:" + key, usage.get(key)).apply();
    }

    // ================================================================
    // Acesso rápido
    // ================================================================

    /** Abre o limpador da própria Xiaomi (MIUI / HyperOS), tentando várias versões. */
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

        for (Intent i : tries) {
            if (startQuiet(i)) return;
        }
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
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
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
        int wanted = frequentApps().isEmpty() ? TAB_ALL : TAB_FREQUENT;
        if (tab != wanted) {
            tab = wanted;
            updateTabs();
        }
        refreshRows();
        if (list != null) list.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) {
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // ================================================================
    // Lista
    // ================================================================

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }
        @Override public boolean isEnabled(int position) {
            return rows.get(position) instanceof App;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            TextView tv = (TextView) convertView;
            if (o instanceof String) {
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextColor(ACCENT);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    tv.setLetterSpacing(0.2f);
                    tv.setPadding(0, dp(16), 0, dp(4));
                }
                tv.setText((String) o);
            } else {
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextColor(TEXT);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                    tv.setSingleLine(true);
                    tv.setGravity(Gravity.CENTER_VERTICAL);
                    tv.setPadding(0, dp(11), 0, dp(11));
                }
                tv.setText(((App) o).label);
            }
            return tv;
        }
    }

    // ================================================================
    // Fundo espacial: estrelas fixas desenhadas uma vez, sem animação
    // ================================================================

    private static final class StarsDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private float[] xs, ys, rs;
        private int[] colors;

        StarsDrawable(float density) {
            this.density = density;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            Random r = new Random(1987);
            int n = 70;
            xs = new float[n];
            ys = new float[n];
            rs = new float[n];
            colors = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = b.left + r.nextFloat() * b.width();
                ys[i] = b.top + r.nextFloat() * b.height();
                float big = r.nextFloat();
                rs[i] = density * (big > 0.93f ? 1.3f : big > 0.6f ? 0.8f : 0.5f);
                int alpha = 40 + r.nextInt(110);
                boolean blue = r.nextFloat() > 0.7f;
                colors[i] = Color.argb(alpha, blue ? 170 : 230, blue ? 190 : 230, 255);
            }
        }

        @Override
        public void draw(Canvas c) {
            c.drawColor(BG);
            if (xs == null) return;
            for (int i = 0; i < xs.length; i++) {
                paint.setColor(colors[i]);
                c.drawCircle(xs[i], ys[i], rs[i], paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
