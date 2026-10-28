package com.leve.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.List;
import java.util.Locale;

/**
 * Leve Launcher: tela inicial só com texto.
 * Sem ícones, sem widgets, sem animações e sem bibliotecas: gasta o mínimo de RAM.
 *
 * Toque num app = abrir. Toque longo = tela de informações do app (desinstalar / forçar parada).
 * Toque no relógio = abre o app de alarmes. Toque longo no relógio = configurações do Android.
 */
public class MainActivity extends Activity {

    private static final class App {
        final String label;
        final String search;
        final ComponentName component;

        App(String label, ComponentName component) {
            this.label = label;
            this.component = component;
            this.search = normalize(label);
        }
    }

    private final List<App> allApps = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private AppAdapter adapter;
    private EditText searchBox;
    private ListView list;
    private BroadcastReceiver packageReceiver;
    private volatile int loadGeneration = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver);
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(40), dp(24), 0);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryStart(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        clock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                tryStart(new Intent(Settings.ACTION_SETTINGS));
                return true;
            }
        });
        root.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextColor(0xFF9E9E9E);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(date);

        searchBox = new EditText(this);
        searchBox.setHint("Buscar app…");
        searchBox.setHintTextColor(0xFF666666);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchBox.setBackgroundColor(0xFF151515);
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(24);
        sp.bottomMargin = dp(8);
        root.addView(searchBox, sp);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        // "Enter" no teclado abre o primeiro resultado
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (!shown.isEmpty()) {
                    launch(shown.get(0));
                }
                return true;
            }
        });

        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(shown.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                App app = shown.get(position);
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", app.component.getPackageName(), null));
                tryStart(i);
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) hideKeyboard();
            }
            @Override
            public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

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
                    result.add(new App(label == null ? ri.activityInfo.packageName : label.toString().trim(),
                            new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
                }
                final Collator collator = Collator.getInstance(Locale.getDefault());
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(result, new Comparator<App>() {
                    @Override
                    public int compare(App a, App b) {
                        return collator.compare(a.label, b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != loadGeneration || isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(result);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private void applyFilter() {
        String q = normalize(searchBox.getText().toString());
        shown.clear();
        if (q.isEmpty()) {
            shown.addAll(allApps);
        } else {
            // Primeiro os que começam com o texto, depois os que contêm
            List<App> contains = new ArrayList<>();
            for (App a : allApps) {
                if (a.search.startsWith(q)) shown.add(a);
                else if (a.search.contains(q)) contains.add(a);
            }
            shown.addAll(contains);
        }
        adapter.notifyDataSetChanged();
    }

    private void launch(App app) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(app.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(i)) {
            resetHome();
        }
    }

    private boolean tryStart(Intent i) {
        try {
            if (i.getComponent() == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void resetHome() {
        if (searchBox.length() > 0) searchBox.setText("");
        searchBox.clearFocus();
        hideKeyboard();
        if (list != null) list.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) {
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
    }

    /** Botão Home apertado enquanto já está na tela inicial. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetHome();
    }

    /** Voltar só limpa a busca; a tela inicial não fecha. */
    @Override
    public void onBackPressed() {
        resetHome();
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                tv.setSingleLine(true);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setPadding(0, dp(12), 0, dp(12));
            }
            tv.setText(shown.get(position).label);
            return tv;
        }
    }
}
