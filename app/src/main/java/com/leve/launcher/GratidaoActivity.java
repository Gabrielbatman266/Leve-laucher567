package com.leve.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Caderno da Gratidão com três abas:
 *  - Gratidão: anote objetos, pessoas, momentos... e, se quiser, o porquê (técnica das "3 coisas boas").
 *  - Metas: suas metas para marcar como feitas; "Já é meu" agradece pela meta como se já tivesse acontecido.
 *  - Técnica: o que a pesquisa diz que funciona + exercícios (subtração mental, carta de gratidão, relembrar).
 * Tudo nativo (sem WebView) e salvo só no celular, em SharedPreferences "gratidao".
 */
public class GratidaoActivity extends Activity {

    static final String PREFS = "gratidao";
    static final String KEY = "itens";
    static final String KEY_METAS = "metas";

    static final String[] CATS = {"Objeto", "Pessoa", "Momento", "Lugar", "Saúde", "Outro", "Meta"};
    static final String[] ICONS = {"🎁", "👤", "✨", "📍", "💚", "💛", "🏆"};
    // Índices fixos: "Outro" continua 5 (versão anterior), "Meta" entrou no fim.
    static final int CAT_PESSOA = 1, CAT_OUTRO = 5, CAT_META = 6;

    /** Perguntas que variam o foco a cada dia (variedade e detalhes deixam a prática viva). */
    private static final String[] PERGUNTAS = {
            "Quem fez algo bom por você hoje, mesmo pequeno?",
            "Que objeto facilita sua vida e você nem repara mais?",
            "O que deu certo hoje e por quê?",
            "Que surpresa boa aconteceu essa semana?",
            "Do que seu corpo te deixou fazer hoje?",
            "Que lugar te faz bem? O que tem nele?",
            "Que problema você evitou ou resolveu?",
            "Quem te ensinou algo que você usa até hoje?",
            "Que coisa simples você teria saudade se perdesse?",
            "Qual momento de hoje você quer lembrar daqui a um ano?",
    };

    private int accent, line, muted, faint;
    private int aba = 0;          // 0 Gratidão, 1 Metas, 2 Técnica
    private int cat = 0;          // categoria escolhida para o próximo item
    private int filtro = -1;      // -1 = mostrar todas
    private LinearLayout tabs, body;
    private ScrollView scroll;

    // ================================================================
    // Dados (também usados pelo cartão na tela inicial)
    // ================================================================

    static JSONArray load(Context c) { return loadKey(c, KEY); }

    static JSONArray loadMetas(Context c) { return loadKey(c, KEY_METAS); }

    private static JSONArray loadKey(Context c, String k) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(k, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void save(Context c, JSONArray a) { saveKey(c, KEY, a); }

    static void saveMetas(Context c, JSONArray a) { saveKey(c, KEY_METAS, a); }

    private static void saveKey(Context c, String k, JSONArray a) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(k, a.toString()).apply();
    }

    static void addGratidao(Context c, int cat, String texto, String porque) {
        try {
            JSONArray a = load(c);
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("c", cat);
            o.put("x", texto);
            if (porque != null && !porque.trim().isEmpty()) o.put("w", porque.trim());
            a.put(o);
            save(c, a);
        } catch (Exception ignored) { }
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
        HashSet<Long> dias = new HashSet<>();
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

    /** {feitas, total} das metas. */
    static int[] metasResumo(Context c) {
        JSONArray m = loadMetas(c);
        int feitas = 0;
        for (int i = 0; i < m.length(); i++) {
            JSONObject o = m.optJSONObject(i);
            if (o != null && o.optLong("d") > 0) feitas++;
        }
        return new int[]{feitas, m.length()};
    }

    static String icon(JSONObject o) {
        int c = Math.max(0, Math.min(ICONS.length - 1, o.optInt("c")));
        return ICONS[c];
    }

    /** Uma gratidão antiga sorteada, para lembrar na tela inicial. */
    static String lembrete(JSONArray a) {
        if (a.length() == 0) return null;
        JSONObject o = a.optJSONObject(new Random().nextInt(a.length()));
        if (o == null) return null;
        return icon(o) + "  " + o.optString("x");
    }

    private static String perguntaDoDia() {
        Calendar k = Calendar.getInstance();
        return PERGUNTAS[(k.get(Calendar.DAY_OF_YEAR) + k.get(Calendar.YEAR)) % PERGUNTAS.length];
    }

    // ================================================================
    // Tela
    // ================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        accent = MainActivity.ACCENT != 0 ? MainActivity.ACCENT : 0xFFB4C4FF;
        line = MainActivity.LINE != 0 ? MainActivity.LINE : 0xFF555C7A;
        muted = MainActivity.MUTED != 0 ? MainActivity.MUTED : 0xFFC2C7D6;
        faint = MainActivity.FAINT != 0 ? MainActivity.FAINT : 0xFF8A90A8;
        if (getIntent() != null) aba = getIntent().getIntExtra("aba", 0);

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
        }

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.BLACK);
        outer.setPadding(dp(20), dp(20), dp(20), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(22, Color.WHITE);
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
        outer.addView(top);

        tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(12);
        tp.bottomMargin = dp(6);
        outer.addView(tabs, tp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(6), 0, dp(32));
        scroll.addView(body);
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(outer);
        mostrar(aba);
    }

    private void mostrar(int a) {
        aba = a;
        tabs.removeAllViews();
        String[] nomes = {"🙏 Gratidão", "🎯 Metas", "📖 Técnica"};
        for (int i = 0; i < nomes.length; i++) {
            final int k = i;
            TextView t = chip(nomes[i], i == aba);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < nomes.length - 1) p.rightMargin = dp(6);
            t.setLayoutParams(p);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { mostrar(k); }
            });
            tabs.addView(t);
        }
        body.removeAllViews();
        if (aba == 0) buildGratidao();
        else if (aba == 1) buildMetas();
        else buildTecnica();
        scroll.scrollTo(0, 0);
    }

    // ================================================================
    // Aba Gratidão
    // ================================================================

    private void buildGratidao() {
        JSONArray a = load(this);
        int hoje = countToday(a), seq = streak(a);

        // Progresso das 3 coisas boas
        TextView prog = text(14, muted);
        String r = hoje >= 3 ? "✅ Hoje: " + hoje + " coisas boas. Feito!" :
                "Hoje: " + hoje + " de 3 coisas boas";
        if (seq > 1) r += "  ·  🔥 " + seq + " dias";
        r += "  ·  total " + a.length();
        prog.setText(r);
        body.addView(prog);

        // Pergunta do dia (muda o foco todo dia)
        final TextView perg = text(14, accent);
        perg.setText("💭  " + perguntaDoDia());
        perg.setPadding(0, dp(10), 0, dp(10));
        body.addView(perg);

        final EditText input = field("Sou grato por… (ex.: minha cama, minha mãe)", false);
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));

        final EditText why = field("Por quê? Quem fez acontecer? (opcional)", false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(8);
        body.addView(why, wp);

        final LinearLayout catRow = new LinearLayout(this);
        HorizontalScrollView hs = hscroll(catRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(10);
        body.addView(hs, hp);
        montarCats(catRow);

        TextView add = botao("+ Anotar gratidão");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = input.getText().toString().trim();
                if (s.isEmpty()) { mostrarTeclado(input); return; }
                addGratidao(GratidaoActivity.this, cat, s, why.getText().toString());
                esconderTeclado(input);
                mostrar(0);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                why.requestFocus();
                return true;
            }
        });
        body.addView(add, margemTop(12));

        TextView h = secao("MEU CADERNO");
        body.addView(h);

        final LinearLayout filtroRow = new LinearLayout(this);
        body.addView(hscroll(filtroRow), new LinearLayout.LayoutParams(-1, -2));
        for (int i = -1; i < CATS.length; i++) {
            final int k = i;
            TextView c = chip(i < 0 ? "Todos" : ICONS[i] + " " + CATS[i], i == filtro);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filtro = k; mostrar(0); }
            });
            filtroRow.addView(c);
        }

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
                    ? "Seu caderno está vazio.\nComece com 3 coisas boas de hoje e escreva o porquê de cada uma."
                    : "Nada nesse tipo ainda.");
            e.setLineSpacing(dp(4), 1f);
            e.setPadding(0, dp(18), 0, 0);
            body.addView(e);
            return;
        }
        long hojeIni = startOfDay(System.currentTimeMillis());
        long ontemIni = hojeIni - 86400000L;
        long diaAtual = Long.MIN_VALUE;
        for (int i : idx) {
            JSONObject o = a.optJSONObject(i);
            long t = o.optLong("t");
            long d = startOfDay(t);
            if (d != diaAtual) {
                diaAtual = d;
                TextView dh = text(13, accent);
                dh.setText(d == hojeIni ? "Hoje" : d == ontemIni ? "Ontem" : dataLonga(t));
                dh.setPadding(dp(2), dp(18), 0, dp(6));
                body.addView(dh);
            }
            body.addView(itemGratidao(o), margemBottom(6));
        }
        TextView dica = text(12, faint);
        dica.setText("Segure um item para editar ou apagar.");
        dica.setPadding(0, dp(12), 0, 0);
        body.addView(dica);
    }

    private View itemGratidao(JSONObject o) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(pill(Color.BLACK, line, 16));
        TextView it = text(16, Color.WHITE);
        it.setText(icon(o) + "   " + o.optString("x"));
        box.addView(it);
        String w = o.optString("w", "");
        if (!w.isEmpty()) {
            TextView wt = text(13, muted);
            wt.setText("porque " + w);
            wt.setPadding(dp(30), dp(4), 0, 0);
            box.addView(wt);
        }
        final long id = o.optLong("t");
        box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { opcoesGratidao(id); return true; }
        });
        return box;
    }

    private void montarCats(final LinearLayout catRow) {
        catRow.removeAllViews();
        for (int i = 0; i < CATS.length; i++) {
            final int k = i;
            TextView c = chip(ICONS[i] + " " + CATS[i], i == cat);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cat = k; montarCats(catRow); }
            });
            catRow.addView(c);
        }
    }

    private int find(JSONArray a, long id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("t") == id) return i;
        }
        return -1;
    }

    private void opcoesGratidao(final long id) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Editar", "Apagar"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final JSONArray a = load(GratidaoActivity.this);
                        final int i = find(a, id);
                        if (i < 0) return;
                        if (w == 1) {
                            save(GratidaoActivity.this, remover(a, i));
                            mostrar(0);
                            return;
                        }
                        final JSONObject o = a.optJSONObject(i);
                        LinearLayout f = new LinearLayout(GratidaoActivity.this);
                        f.setOrientation(LinearLayout.VERTICAL);
                        f.setPadding(dp(20), dp(8), dp(20), 0);
                        final EditText x = new EditText(GratidaoActivity.this);
                        x.setText(o.optString("x"));
                        final EditText wy = new EditText(GratidaoActivity.this);
                        wy.setHint("Por quê? (opcional)");
                        wy.setText(o.optString("w", ""));
                        f.addView(x);
                        f.addView(wy);
                        new AlertDialog.Builder(GratidaoActivity.this)
                                .setTitle("Editar")
                                .setView(f)
                                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        String s = x.getText().toString().trim();
                                        if (s.isEmpty()) return;
                                        try {
                                            o.put("x", s);
                                            String ws = wy.getText().toString().trim();
                                            if (ws.isEmpty()) o.remove("w"); else o.put("w", ws);
                                        } catch (Exception ignored) { }
                                        save(GratidaoActivity.this, a);
                                        mostrar(0);
                                    }
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                }).show();
    }

    // ================================================================
    // Aba Metas
    // ================================================================

    private void buildMetas() {
        final JSONArray m = loadMetas(this);
        int[] res = metasResumo(this);

        TextView r = text(14, muted);
        r.setText(res[1] == 0 ? "Escreva suas metas e marque quando conseguir."
                : "Conquistadas: " + res[0] + " de " + res[1]);
        body.addView(r);

        TextView dica = text(13, faint);
        dica.setText("Escreva como se já fosse seu. Toque em 🙏 \"Já é meu\" para agradecer pela meta antes de ela chegar.");
        dica.setLineSpacing(dp(3), 1f);
        dica.setPadding(0, dp(8), 0, dp(10));
        body.addView(dica);

        final EditText input = field("Nova meta (ex.: ter meu carro, juntar R$ 5 mil)", false);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView add = botao("+ Adicionar meta");
        View.OnClickListener addMeta = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = input.getText().toString().trim();
                if (s.isEmpty()) { mostrarTeclado(input); return; }
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", System.currentTimeMillis());
                    o.put("x", s);
                    o.put("d", 0);
                    m.put(o);
                    saveMetas(GratidaoActivity.this, m);
                } catch (Exception ignored) { }
                esconderTeclado(input);
                mostrar(1);
            }
        };
        add.setOnClickListener(addMeta);
        final View.OnClickListener am = addMeta;
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) { am.onClick(v); return true; }
        });
        body.addView(add, margemTop(10));

        // Abertas primeiro, depois as conquistadas
        boolean temAberta = false, temFeita = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < m.length(); i++) {
                JSONObject o = m.optJSONObject(i);
                if (o == null) continue;
                boolean feita = o.optLong("d") > 0;
                if ((pass == 0) == feita) continue;
                if (pass == 0 && !temAberta) { body.addView(secao("EM ANDAMENTO")); temAberta = true; }
                if (pass == 1 && !temFeita) { body.addView(secao("🏆 CONQUISTADAS")); temFeita = true; }
                body.addView(itemMeta(o), margemBottom(6));
            }
        }
        if (m.length() > 0) {
            TextView d2 = text(12, faint);
            d2.setText("Toque no quadrado para marcar. Segure a meta para editar ou apagar.");
            d2.setPadding(0, dp(12), 0, 0);
            body.addView(d2);
        }
    }

    private View itemMeta(final JSONObject o) {
        final boolean feita = o.optLong("d") > 0;
        final long id = o.optLong("t");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(pill(Color.BLACK, feita ? accent : line, 16));

        TextView box = text(22, feita ? accent : muted);
        box.setText(feita ? "☑" : "☐");
        box.setPadding(dp(8), dp(4), dp(10), dp(4));
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { marcarMeta(id, !feita); }
        });
        row.addView(box);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(16, feita ? muted : Color.WHITE);
        t.setText(o.optString("x"));
        if (feita) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        col.addView(t);
        TextView sub = text(12, faint);
        sub.setText(feita ? "conquistada em " + dataCurta(o.optLong("d")) : "desde " + dataCurta(id)
                + (o.optInt("g") > 0 ? "  ·  🙏 " + o.optInt("g") + "x" : ""));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!feita) {
            TextView ja = text(13, Color.BLACK);
            ja.setText("🙏 Já é meu");
            ja.setPadding(dp(10), dp(8), dp(10), dp(8));
            ja.setBackground(pill(accent, accent, 16));
            ja.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { jaEMeu(id); }
            });
            row.addView(ja);
        }
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { opcoesMeta(id); return true; }
        });
        return row;
    }

    private void marcarMeta(long id, boolean feita) {
        JSONArray m = loadMetas(this);
        int i = find(m, id);
        if (i < 0) return;
        JSONObject o = m.optJSONObject(i);
        try { o.put("d", feita ? System.currentTimeMillis() : 0); } catch (Exception ignored) { }
        saveMetas(this, m);
        if (feita) {
            // Conquista também entra no caderno de gratidão
            addGratidao(this, CAT_META, "Conquistei: " + o.optString("x"), null);
            mensagem("🏆 Conquistada!", "Anotei no seu caderno de gratidão.\nAgradeça a quem ajudou e ao seu esforço.");
        }
        mostrar(1);
    }

    /** Gratidão antecipada: agradece pela meta como se já tivesse acontecido. */
    private void jaEMeu(final long id) {
        final JSONArray m = loadMetas(this);
        final int i = find(m, id);
        if (i < 0) return;
        final JSONObject o = m.optJSONObject(i);
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText("Obrigado, já é meu: " + o.optString("x"));
        e.setSelection(e.getText().length());
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(4), dp(20), 0);
        TextView q = text(14, muted);
        q.setText("Feche os olhos 10 segundos. Como é ter isso agora? Que sensação dá? Escreva no presente:");
        q.setLineSpacing(dp(3), 1f);
        f.addView(q);
        f.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("🙏 Já é meu")
                .setView(f)
                .setPositiveButton("Agradecer", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        if (s.isEmpty()) return;
                        addGratidao(GratidaoActivity.this, CAT_META, s, null);
                        try { o.put("g", o.optInt("g") + 1); } catch (Exception ignored) { }
                        saveMetas(GratidaoActivity.this, m);
                        mostrar(1);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void opcoesMeta(final long id) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Editar", "Apagar"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final JSONArray m = loadMetas(GratidaoActivity.this);
                        final int i = find(m, id);
                        if (i < 0) return;
                        if (w == 1) {
                            saveMetas(GratidaoActivity.this, remover(m, i));
                            mostrar(1);
                            return;
                        }
                        final EditText e = new EditText(GratidaoActivity.this);
                        e.setText(m.optJSONObject(i).optString("x"));
                        e.setSelection(e.getText().length());
                        new AlertDialog.Builder(GratidaoActivity.this)
                                .setTitle("Editar meta")
                                .setView(e)
                                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        String s = e.getText().toString().trim();
                                        if (s.isEmpty()) return;
                                        try { m.optJSONObject(i).put("x", s); } catch (Exception ignored) { }
                                        saveMetas(GratidaoActivity.this, m);
                                        mostrar(1);
                                    }
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                }).show();
    }

    // ================================================================
    // Aba Técnica
    // ================================================================

    private void buildTecnica() {
        TextView intro = text(14, muted);
        intro.setText("O caderno da gratidão é uma das práticas mais estudadas da psicologia positiva. "
                + "Quem anota pelo que é grato costuma ter mais bom humor, otimismo e dormir melhor. "
                + "O segredo não é escrever muito: é sentir enquanto escreve.");
        intro.setLineSpacing(dp(4), 1f);
        body.addView(intro);

        body.addView(secao("COMO FAZER"));
        dicaTec("1", "3 coisas boas + o porquê",
                "No fim do dia, anote 3 coisas boas que aconteceram e por que aconteceram (o que você ou alguém fez). "
                        + "O porquê é o que mais muda a cabeça: você começa a ver suas próprias causas.");
        dicaTec("2", "Seja específico",
                "\"Minha mãe fez o café que eu gosto antes de eu acordar\" vale mais que \"minha família\". "
                        + "Detalhe traz a emoção de volta.");
        dicaTec("3", "Pessoas valem mais que coisas",
                "Agradecer por pessoas tem mais efeito que por objetos. Misture: um objeto, uma pessoa, um momento.");
        dicaTec("4", "Subtração mental",
                "Em vez de só somar, imagine sua vida SEM aquilo. A falta imaginada mostra o valor real.");
        dicaTec("5", "Surpresas e presentes",
                "Anote o que foi inesperado e veja as coisas boas como presentes, não como obrigação. "
                        + "Isso quebra o \"costume\" que faz a gente parar de notar o que tem.");
        dicaTec("6", "Varie",
                "Pode repetir pessoas e coisas, mas escreva um detalhe novo cada vez. A pergunta do dia ajuda nisso.");
        dicaTec("7", "Qualidade > quantidade",
                "Fazer 1 a 3 vezes por semana com calma pode render mais que todo dia no automático. "
                        + "Se virar obrigação chata, diminua. Tem que parecer certo pra você.");
        dicaTec("8", "Releia",
                "Uma vez por mês, releia o caderno. É um estoque de coisas boas para dias ruins.");

        body.addView(secao("GRATIDÃO E METAS"));
        dicaTec("🎯", "Agradeça antes de chegar",
                "Na aba Metas, \"Já é meu\" pede para você sentir a meta como já realizada e agradecer no presente. "
                        + "Quando conquistar, marque o quadrado: a conquista entra sozinha no caderno.");

        body.addView(secao("EXERCÍCIOS"));
        TextView sub = botao("🫥  Subtração mental agora");
        sub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { subtracao(); }
        });
        body.addView(sub, margemTop(6));
        TextView carta = botao("✉️  Escrever carta de gratidão");
        carta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { carta(); }
        });
        body.addView(carta, margemTop(8));
        TextView rel = botao("📜  Relembrar gratidões antigas");
        rel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { relembrar(); }
        });
        body.addView(rel, margemTop(8));

        TextView fonte = text(11, faint);
        fonte.setText("Base: estudos de Emmons & McCullough, Seligman (3 coisas boas), Lyubomirsky (frequência) "
                + "e Greater Good Science Center (Berkeley).");
        fonte.setLineSpacing(dp(2), 1f);
        fonte.setPadding(0, dp(20), 0, 0);
        body.addView(fonte);
    }

    private void dicaTec(String n, String titulo, String texto) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(pill(Color.BLACK, line, 16));
        TextView t = text(15, Color.WHITE);
        t.setText(n + "   " + titulo);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);
        TextView d = text(13, muted);
        d.setText(texto);
        d.setLineSpacing(dp(3), 1f);
        d.setPadding(0, dp(4), 0, 0);
        box.addView(d);
        body.addView(box, margemBottom(6));
    }

    private void subtracao() {
        JSONArray a = load(this);
        String alvo;
        if (a.length() == 0) alvo = "alguém ou algo que você usa todo dia";
        else alvo = a.optJSONObject(new Random().nextInt(a.length())).optString("x");
        final EditText e = new EditText(this);
        e.setHint("O que você percebeu?");
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(4), dp(20), 0);
        TextView q = text(15, muted);
        q.setText("Imagine por 30 segundos que você nunca teve:\n\n\"" + alvo + "\"\n\n"
                + "Como seria seu dia? O que faltaria? Agora volte e perceba: você tem.");
        q.setLineSpacing(dp(3), 1f);
        f.addView(q);
        f.addView(e);
        final String alvoF = alvo;
        new AlertDialog.Builder(this)
                .setTitle("🫥 Subtração mental")
                .setView(f)
                .setPositiveButton("Anotar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        addGratidao(GratidaoActivity.this, CAT_OUTRO, alvoF, s.isEmpty() ? "imaginei a vida sem isso" : s);
                        mostrar(0);
                    }
                })
                .setNeutralButton("Outro", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { subtracao(); }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void carta() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(4), dp(20), 0);
        TextView q = text(14, muted);
        q.setText("Pense em alguém que fez diferença na sua vida e você nunca agradeceu direito. "
                + "Conte o que a pessoa fez e como isso mudou você. Se der, mande ou leia para ela.");
        q.setLineSpacing(dp(3), 1f);
        f.addView(q);
        final EditText para = new EditText(this);
        para.setHint("Para quem?");
        para.setSingleLine(true);
        f.addView(para);
        final EditText txt = new EditText(this);
        txt.setHint("Sua carta…");
        txt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        txt.setMinLines(4);
        txt.setGravity(Gravity.TOP);
        f.addView(txt);
        new AlertDialog.Builder(this)
                .setTitle("✉️ Carta de gratidão")
                .setView(f)
                .setPositiveButton("Salvar e enviar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String p = para.getText().toString().trim();
                        String t = txt.getText().toString().trim();
                        if (t.isEmpty()) return;
                        addGratidao(GratidaoActivity.this, CAT_PESSOA,
                                "Carta para " + (p.isEmpty() ? "alguém especial" : p), t);
                        try {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_TEXT, (p.isEmpty() ? "" : p + ",\n\n") + t);
                            startActivity(Intent.createChooser(s, "Enviar carta"));
                        } catch (Exception ignored) { }
                        mostrar(0);
                    }
                })
                .setNeutralButton("Só salvar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String p = para.getText().toString().trim();
                        String t = txt.getText().toString().trim();
                        if (t.isEmpty()) return;
                        addGratidao(GratidaoActivity.this, CAT_PESSOA,
                                "Carta para " + (p.isEmpty() ? "alguém especial" : p), t);
                        mostrar(0);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void relembrar() {
        JSONArray a = load(this);
        if (a.length() == 0) {
            mensagem("📜 Relembrar", "Seu caderno ainda está vazio. Anote algumas gratidões primeiro.");
            return;
        }
        // Prefere as de mais de uma semana atrás; senão, sorteia entre todas
        long limite = System.currentTimeMillis() - 7L * 86400000L;
        List<JSONObject> velhas = new ArrayList<>(), todas = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            todas.add(o);
            if (o.optLong("t") < limite) velhas.add(o);
        }
        List<JSONObject> base = velhas.size() >= 3 ? velhas : todas;
        java.util.Collections.shuffle(base);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, base.size()); i++) {
            JSONObject o = base.get(i);
            sb.append(icon(o)).append("  ").append(o.optString("x"))
                    .append("\n      ").append(dataCurta(o.optLong("t"))).append("\n\n");
        }
        sb.append("Pare um pouco em cada uma e sinta de novo.");
        mensagem("📜 Relembrar", sb.toString());
    }

    // ================================================================
    // Utilidades
    // ================================================================

    private static JSONArray remover(JSONArray a, int i) {
        JSONArray n = new JSONArray();
        for (int k = 0; k < a.length(); k++) if (k != i) n.put(a.opt(k));
        return n;
    }

    private void mensagem(String t, String m) {
        new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show();
    }

    private String dataLonga(long t) {
        String s = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("pt", "BR")).format(t);
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private String dataCurta(long t) {
        return new SimpleDateFormat("d MMM yyyy", new Locale("pt", "BR")).format(t);
    }

    private EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(faint);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(pill(Color.BLACK, line, 16));
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (!multi) e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        return e;
    }

    private TextView botao(String s) {
        TextView b = text(16, Color.BLACK);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(pill(accent, accent, 22));
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        return b;
    }

    private TextView secao(String s) {
        TextView h = text(13, faint);
        h.setText(s);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, dp(24), 0, dp(8));
        return h;
    }

    private HorizontalScrollView hscroll(View v) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.addView(v);
        return hs;
    }

    private TextView chip(String s, boolean on) {
        TextView c = text(14, on ? Color.BLACK : muted);
        c.setText(s);
        c.setSingleLine(true);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setBackground(on ? pill(accent, accent, 18) : pill(Color.BLACK, line, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(8);
        c.setLayoutParams(p);
        return c;
    }

    private LinearLayout.LayoutParams margemTop(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(m);
        return p;
    }

    private LinearLayout.LayoutParams margemBottom(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(m);
        return p;
    }

    private void mostrarTeclado(EditText e) {
        e.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(e, 0);
    }

    private void esconderTeclado(EditText e) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }

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
