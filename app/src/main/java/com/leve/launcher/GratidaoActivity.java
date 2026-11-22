package com.leve.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Caderno da Gratidão: anote objetos, pessoas, momentos e lugares pelos quais você é grato.
 * Tudo nativo (sem WebView) e salvo só no celular, em SharedPreferences "gratidao".
 * Toque longo num item para editar ou apagar.
 */
public class GratidaoActivity extends Activity {

    static final String PREFS = "gratidao";
    static final String KEY = "itens";

    static final String[] CATS = {"Objeto", "Pessoa", "Momento", "Lugar", "Saúde", "Outro"};
    static final String[] ICONS = {"🎁", "👤", "✨", "📍", "💚", "💛"};

    private static final String[] FRASES = {
            "Quem agradece pelo que tem, recebe mais.",
            "Repare no que já está aqui.",
            "Pequenas coisas também contam.",
            "Agradecer é dizer: já é meu.",
            "Hoje tem pelo menos uma coisa boa. Ache ela.",
    };

    private int accent, line, muted, faint;
    private int cat = 0;          // categoria escolhida para o próximo item
    private int filtro = -1;      // -1 = mostrar todas
    private EditText input;
    private LinearLayout catRow, filtroRow, lista;
    private TextView resumo;

    // ---------- Dados (também usados pelo cartão na tela inicial) ----------

    static JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void save(Context c, JSONArray a) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, a.toString()).apply();
    }

    static long startOfDay(long t) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(t);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static int countToday(JSONArray a) {
        long hoje = startOfDay(System.currentTimeMillis());
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("t") >= hoje) n++;
        }
        return n;
    }

    /** Dias seguidos (terminando hoje ou ontem) com pelo menos uma gratidão. */
    static int streak(JSONArray a) {
        java.util.HashSet<Long> dias = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) dias.add(startOfDay(o.optLong("t")));
        }
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(startOfDay(System.currentTimeMillis()));
        if (!dias.contains(k.getTimeInMillis())) k.add(Calendar.DAY_OF_YEAR, -1);
        int n = 0;
        while (dias.contains(k.getTimeInMillis())) {
            n++;
            k.add(Calendar.DAY_OF_YEAR, -1);
        }
        return n;
    }

    /** Uma gratidão antiga sorteada, para lembrar na tela inicial. */
    static String lembrete(JSONArray a) {
        if (a.length() == 0) return null;
        JSONObject o = a.optJSONObject(new Random().nextInt(a.length()));
        if (o == null) return null;
        int c = Math.max(0, Math.min(ICONS.length - 1, o.optInt("c")));
        return ICONS[c] + "  " + o.optString("x");
    }

    // ---------- Tela ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        accent = MainActivity.ACCENT != 0 ? MainActivity.ACCENT : 0xFFB4C4FF;
        line = MainActivity.LINE != 0 ? MainActivity.LINE : 0xFF555C7A;
        muted = MainActivity.MUTED != 0 ? MainActivity.MUTED : 0xFFC2C7D6;
        faint = MainActivity.FAINT != 0 ? MainActivity.FAINT : 0xFF8A90A8;

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        // Topo
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(24, Color.WHITE);
        title.setText("🙏  Caderno da Gratidão");
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(20, muted);
        close.setText("✕");
        close.setPadding(dp(14), dp(6), dp(4), dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(close);
        root.addView(top);

        TextView frase = text(14, faint);
        frase.setText(FRASES[new Random().nextInt(FRASES.length)]);
        frase.setPadding(0, dp(4), 0, dp(12));
        root.addView(frase);

        resumo = text(14, muted);
        resumo.setPadding(0, 0, 0, dp(14));
        root.addView(resumo);

        // Campo de escrever
        TextView q = text(16, Color.WHITE);
        q.setText("Sou grato por…");
        q.setPadding(0, 0, 0, dp(8));
        root.addView(q);

        input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(faint);
        input.setHint("ex.: meu celular, minha cama, minha mãe…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setBackground(pill(Color.BLACK, line, 16));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                adicionar();
                return true;
            }
        });
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));

        // Tipo do item
        catRow = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.addView(catRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(10);
        root.addView(hs, hp);

        TextView add = text(16, Color.BLACK);
        add.setText("+ Anotar gratidão");
        add.setGravity(Gravity.CENTER);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setBackground(pill(accent, accent, 22));
        add.setPadding(dp(16), dp(13), dp(16), dp(13));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { adicionar(); }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(12);
        root.addView(add, ap);

        // Filtro da lista
        TextView h = text(13, faint);
        h.setText("MEU CADERNO");
        h.setLetterSpacing(0.12f);
        h.setPadding(0, dp(26), 0, dp(6));
        root.addView(h);

        filtroRow = new LinearLayout(this);
        HorizontalScrollView hf = new HorizontalScrollView(this);
        hf.setHorizontalScrollBarEnabled(false);
        hf.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hf.addView(filtroRow);
        root.addView(hf, new LinearLayout.LayoutParams(-1, -2));

        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        root.addView(lista, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
        montarCats();
        refresh();
    }

    private void montarCats() {
        catRow.removeAllViews();
        for (int i = 0; i < CATS.length; i++) {
            final int k = i;
            TextView c = chip(ICONS[i] + " " + CATS[i], i == cat);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cat = k; montarCats(); }
            });
            catRow.addView(c);
        }
    }

    private void montarFiltro() {
        filtroRow.removeAllViews();
        for (int i = -1; i < CATS.length; i++) {
            final int k = i;
            TextView c = chip(i < 0 ? "Todos" : ICONS[i] + " " + CATS[i], i == filtro);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filtro = k; refresh(); }
            });
            filtroRow.addView(c);
        }
    }

    private TextView chip(String s, boolean on) {
        TextView c = text(14, on ? Color.BLACK : muted);
        c.setText(s);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setBackground(on ? pill(accent, accent, 18) : pill(Color.BLACK, line, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(8);
        c.setLayoutParams(p);
        return c;
    }

    private void adicionar() {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) {
            input.requestFocus();
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, 0);
            return;
        }
        try {
            JSONArray a = load(this);
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("c", cat);
            o.put("x", s);
            a.put(o);
            save(this, a);
        } catch (Exception ignored) { }
        input.setText("");
        refresh();
    }

    private void refresh() {
        JSONArray a = load(this);
        int hoje = countToday(a), seq = streak(a);
        String r = hoje == 0 ? "Hoje você ainda não anotou nada." :
                "Hoje: " + hoje + (hoje == 1 ? " gratidão" : " gratidões");
        if (seq > 1) r += "  ·  🔥 " + seq + " dias seguidos";
        r += "  ·  total " + a.length();
        resumo.setText(r);

        montarFiltro();
        lista.removeAllViews();

        // Mais novas primeiro, agrupadas por dia
        List<Integer> idx = new ArrayList<>();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (filtro >= 0 && o.optInt("c") != filtro) continue;
            idx.add(i);
        }
        if (idx.isEmpty()) {
            TextView e = text(14, faint);
            e.setText(a.length() == 0
                    ? "Seu caderno está vazio.\nComece com 3 coisas: um objeto, uma pessoa e um momento de hoje."
                    : "Nada nesse tipo ainda.");
            e.setLineSpacing(dp(4), 1f);
            e.setPadding(0, dp(18), 0, 0);
            lista.addView(e);
            return;
        }
        long hojeIni = startOfDay(System.currentTimeMillis());
        long ontemIni = hojeIni - 86400000L;
        SimpleDateFormat df = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR"));
        long diaAtual = Long.MIN_VALUE;
        for (int i : idx) {
            final JSONObject o = a.optJSONObject(i);
            long t = o.optLong("t");
            long d = startOfDay(t);
            if (d != diaAtual) {
                diaAtual = d;
                TextView dh = text(13, accent);
                String nome = d == hojeIni ? "Hoje" : d == ontemIni ? "Ontem" : df.format(t);
                dh.setText(nome.substring(0, 1).toUpperCase(Locale.ROOT) + nome.substring(1));
                dh.setPadding(dp(2), dp(18), 0, dp(6));
                lista.addView(dh);
            }
            int c = Math.max(0, Math.min(ICONS.length - 1, o.optInt("c")));
            TextView it = text(16, Color.WHITE);
            it.setText(ICONS[c] + "   " + o.optString("x"));
            it.setEllipsize(TextUtils.TruncateAt.END);
            it.setPadding(dp(16), dp(12), dp(16), dp(12));
            it.setBackground(pill(Color.BLACK, line, 16));
            final long id = t;
            it.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { opcoes(id); return true; }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(6);
            lista.addView(it, p);
        }
        TextView dica = text(12, faint);
        dica.setText("Segure um item para editar ou apagar.");
        dica.setPadding(0, dp(12), 0, 0);
        lista.addView(dica);
    }

    private int find(JSONArray a, long id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("t") == id) return i;
        }
        return -1;
    }

    private void opcoes(final long id) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Editar", "Apagar"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) editar(id); else apagar(id);
                    }
                }).show();
    }

    private void editar(final long id) {
        final JSONArray a = load(this);
        final int i = find(a, id);
        if (i < 0) return;
        final EditText e = new EditText(this);
        e.setText(a.optJSONObject(i).optString("x"));
        e.setSelection(e.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Editar")
                .setView(e)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        if (s.isEmpty()) return;
                        try { a.optJSONObject(i).put("x", s); } catch (Exception ignored) { }
                        save(GratidaoActivity.this, a);
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void apagar(long id) {
        JSONArray a = load(this);
        int i = find(a, id);
        if (i < 0) return;
        JSONArray n = new JSONArray();
        for (int k = 0; k < a.length(); k++) if (k != i) n.put(a.opt(k));
        save(this, n);
        refresh();
    }

    // ---------- Utilidades ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable pill(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.5f)), stroke);
        return g;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }
}
