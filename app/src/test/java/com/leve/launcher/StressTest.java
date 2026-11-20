package com.leve.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Looper;
import android.os.Process;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Testes de estresse do Leve Launcher, do fácil (N1) ao impossível (N10).
 * Cada verificação é anotada no placar; o teste não para no primeiro erro.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h851dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class StressTest {

    static final Intent LAUNCH = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    static final String[] REAL = {"WhatsApp", "Instagram", "Threads", "YouTube", "Chrome", "Brave", "Claude",
            "ChatGPT", "TikTok", "Nubank", "Calculadora", "Câmera", "Galeria", "Mensagens", "Telefone",
            "Spotify", "YMusic", "Configurações", "Google", "Mapas"};

    private final List<ActivityController<MainActivity>> open = new ArrayList<>();

    @Before public void clean() {
        ShadowToast.reset();
    }

    @After public void close() {
        for (ActivityController<MainActivity> c : open) {
            try { c.pause().stop().destroy(); } catch (Throwable ignored) { }
        }
        open.clear();
    }

    // ------------------------------------------------------------------ ajudantes

    static Context app() { return RuntimeEnvironment.getApplication(); }

    static ShadowPackageManager spm() { return shadowOf(app().getPackageManager()); }

    static ResolveInfo ri(String pkg, String label) {
        ResolveInfo r = new ResolveInfo();
        ActivityInfo ai = new ActivityInfo();
        ai.packageName = pkg;
        ai.name = pkg + ".Main";
        ai.applicationInfo = new ApplicationInfo();
        ai.applicationInfo.packageName = pkg;
        r.activityInfo = ai;
        r.nonLocalizedLabel = label;
        return r;
    }

    /** Coloca n apps "instalados": os 20 primeiros com nomes reais, o resto inventado. */
    static List<ResolveInfo> apps(int n) {
        List<ResolveInfo> l = new ArrayList<>();
        Random r = new Random(n);
        for (int i = 0; i < n; i++) {
            String label;
            if (i < REAL.length) label = REAL[i];
            else {
                StringBuilder sb = new StringBuilder();
                int len = 4 + r.nextInt(14);
                for (int k = 0; k < len; k++) sb.append((char) ((k == 0 ? 'A' : 'a') + r.nextInt(26)));
                label = sb + " " + i;
            }
            l.add(ri("com.teste.app" + i, label));
        }
        return l;
    }

    static void install(List<ResolveInfo> list) {
        spm().addResolveInfoForIntent(LAUNCH, list);
    }

    MainActivity start() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        open.add(c);
        return c.get();
    }

    ActivityController<MainActivity> controller(MainActivity a) {
        for (ActivityController<MainActivity> c : open) if (c.get() == a) return c;
        return null;
    }

    static void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    /** Espera a lista de apps (lida em segundo plano) chegar na tela. Devolve ms. */
    static long waitApps(MainActivity a, int expected) throws Exception {
        long t0 = System.nanoTime();
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            idle();
            List<?> all = Report.get(a, "allApps");
            if (all.size() == expected) return (System.nanoTime() - t0) / 1_000_000;
            Thread.sleep(3);
        }
        throw new AssertionError("apps não carregaram: " + ((List<?>) Report.get(a, "allApps")).size() + "/" + expected);
    }

    static double ms(long nanos) { return nanos / 1e6; }

    static Intent next() { return shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity(); }

    static void clearStarted() { while (next() != null) { } }

    static Intent lastStarted(MainActivity a) {
        Intent i, last = null;
        while ((i = shadowOf(a).getNextStartedActivity()) != null) last = i;
        if (last == null) last = next();
        return last;
    }

    static void type(EditText e, String s) { e.setText(s); idle(); }

    // ================================================================== N1 FÁCIL
    @Test public void n01_facil() throws Exception {
        install(apps(20));
        long t0 = System.nanoTime();
        final MainActivity a = start();
        final long openMs = (System.nanoTime() - t0) / 1_000_000;
        waitApps(a, 20);
        Report.run(1, "abre a tela inicial", () -> Report.budget("abrir", openMs, 4000, "ms"));
        Report.run(1, "todas as 6 abas abrem", () -> {
            for (int t = 0; t < 6; t++) { Report.call(a, "selectTab", t); idle(); }
            Report.call(a, "selectTab", 0); idle();
            return "ok";
        });
        Report.run(1, "widget do Google aparece", () -> {
            View g = a.googleBar;
            if (g == null || g.getVisibility() != View.VISIBLE) return Report.fail("barra não visível");
            if (a.googleInput == null) return Report.fail("sem campo de texto");
            return "visível com campo + 🎤 + Lens";
        });
        Report.run(1, "pesquisa no Google pela barra", () -> {
            clearStarted();
            type(a.googleInput, "receita de bolo de cenoura");
            a.submitGoogle();
            Intent i = lastStarted(a);
            if (i == null) return Report.fail("nada abriu");
            if (!Intent.ACTION_WEB_SEARCH.equals(i.getAction())) return Report.fail("abriu " + i);
            if (!"receita de bolo de cenoura".equals(i.getStringExtra(SearchManager.QUERY))) return Report.fail("texto errado");
            if (a.googleInput.length() != 0) return Report.fail("campo não limpou");
            return "abriu o app Google com a pesquisa";
        });
        Report.run(1, "busca de app acha o WhatsApp", () -> {
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            type(sb, "whats");
            List<?> rows = Report.get(a, "rows");
            Object first = rows.isEmpty() ? null : rows.get(0);
            String label = first == null ? "" : Report.get(first, "label");
            if (!"WhatsApp".equals(label)) return Report.fail("primeiro resultado: " + label);
            if (rows.get(rows.size() - 1) != MainActivity.GOOGLE_ROW) return Report.fail("sem linha do Google no fim");
            type(sb, "");
            return "ok + 'Pesquisar no Google' no fim";
        });
    }

    // ================================================================== N2
    @Test public void n02_widget_google_completo() throws Exception {
        install(apps(200));
        final MainActivity a = start();
        waitApps(a, 200);
        Report.run(2, "busca sem resultado vira pesquisa Google", () -> {
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            type(sb, "previsão do tempo amanhã");
            List<?> rows = Report.get(a, "rows");
            if (rows.size() != 1 || rows.get(0) != MainActivity.GOOGLE_ROW) return Report.fail("linhas: " + rows.size());
            clearStarted();
            ListView list = Report.get(a, "list");
            View v = list.getAdapter().getView(0, null, list);
            String txt = ((TextView) v).getText().toString();
            list.performItemClick(v, 0, 0);
            Intent i = lastStarted(a);
            if (i == null || !"previsão do tempo amanhã".equals(i.getStringExtra(SearchManager.QUERY)))
                return Report.fail("não pesquisou: " + i);
            return "linha: " + txt;
        });
        Report.run(2, "Enter na busca de apps sem resultado → Google", () -> {
            EditText sb = Report.get(a, "searchBox");
            type(sb, "xyzw quanto custa");
            clearStarted();
            sb.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            idle();
            Intent i = lastStarted(a);
            return i != null && Intent.ACTION_WEB_SEARCH.equals(i.getAction()) ? "ok" : Report.fail("abriu " + i);
        });
        Report.run(2, "site digitado abre direto", () -> {
            clearStarted();
            type(a.googleInput, "g1.globo.com");
            a.submitGoogle();
            Intent i = lastStarted(a);
            if (i == null || !Intent.ACTION_VIEW.equals(i.getAction())) return Report.fail("abriu " + i);
            return "https://g1.globo.com".equals(String.valueOf(i.getData())) ? "ok" : Report.fail("" + i.getData());
        });
        Report.run(2, "sem app Google → abre no navegador", () -> {
            shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=gatos+fofos"));
            spm().addResolveInfoForIntent(web, ri("com.navegador", "Navegador"));
            clearStarted();
            type(a.googleInput, "gatos fofos");
            a.submitGoogle();
            Intent i = lastStarted(a);
            shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false);
            if (i == null) return Report.fail("nada abriu");
            return Intent.ACTION_VIEW.equals(i.getAction()) && String.valueOf(i.getData()).contains("google.com/search")
                    ? "plano B ok" : Report.fail("abriu " + i);
        });
        Report.run(2, "botão de voz", () -> {
            ViewGroup bar = a.googleBar;
            clearStarted();
            bar.getChildAt(2).performClick();
            Intent i = lastStarted(a);
            return i != null && android.speech.RecognizerIntent.ACTION_WEB_SEARCH.equals(i.getAction())
                    ? "ok" : Report.fail("abriu " + i);
        });
        Report.run(2, "botão do Lens", () -> {
            ViewGroup bar = a.googleBar;
            clearStarted();
            bar.getChildAt(3).performClick();
            Intent i = lastStarted(a);
            ComponentName c = i == null ? null : i.getComponent();
            return c != null && c.getClassName().contains("Lens") ? "ok" : Report.fail("abriu " + i);
        });
        Report.run(2, "esconder e mostrar a barra (sobrevive a reinício)", () -> {
            a.setGoogleBar(false);
            if (a.googleBar.getVisibility() != View.GONE) return Report.fail("não escondeu");
            MainActivity b = start();
            waitApps(b, 200);
            boolean hidden = b.googleBar.getVisibility() == View.GONE;
            b.setGoogleBar(true);
            return hidden && b.googleBar.getVisibility() == View.VISIBLE ? "ok" : Report.fail("não lembrou");
        });
        Report.run(2, "escolher navegador fixo pras pesquisas", () -> {
            SharedPreferences p = app().getSharedPreferences("leve", Context.MODE_PRIVATE);
            p.edit().putString("google_open", "com.teste.app5").apply();
            clearStarted();
            a.googleSearch("teste");
            Intent i = lastStarted(a);
            p.edit().remove("google_open").apply();
            return i != null && "com.teste.app5".equals(i.getPackage()) ? "ok" : Report.fail("abriu " + i);
        });
    }

    // ================================================================== N3 MÉDIO
    @Test public void n03_medio_1000_apps() throws Exception {
        install(apps(1000));
        final MainActivity a = start();
        waitApps(a, 1000);
        Report.call(a, "selectTab", 3); idle();
        for (int mode = 0; mode < 4; mode++) {
            final int m = mode;
            Report.run(3, "organizar " + new String[]{"A–Z", "Categorias", "Tamanho", "Jogos"}[m], () -> {
                Report.set(a, "sortMode", m);
                Report.call(a, "refreshRows");
                long t = System.nanoTime();
                for (int k = 0; k < 10; k++) Report.call(a, "refreshRows");
                return Report.budget("refresh", ms(System.nanoTime() - t) / 10, 30, "ms");
            });
        }
        Report.set(a, "sortMode", 0);
        Report.run(3, "trocar de aba 300x", () -> {
            long t = System.nanoTime();
            for (int k = 0; k < 300; k++) Report.call(a, "selectTab", k % 6);
            idle();
            return Report.budget("média por troca", ms(System.nanoTime() - t) / 300, 12, "ms");
        });
        Report.run(3, "digitar 25 letras na busca", () -> {
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            sb.setText("");
            String s = "abcdefghijklmnopqrstuvwxy";
            long worst = 0, total = 0;
            for (char ch : s.toCharArray()) {
                long t = System.nanoTime();
                sb.append(String.valueOf(ch));
                long d = System.nanoTime() - t;
                total += d; worst = Math.max(worst, d);
            }
            sb.setText("");
            return Report.budget("por tecla (pior " + String.format("%.1f", ms(worst)) + ")", ms(total) / s.length(), 16, "ms");
        });
    }

    // ================================================================== N4 tempestade de instalações
    @Test public void n04_tempestade_de_atualizacoes() throws Exception {
        List<ResolveInfo> base = apps(1000);
        install(base);
        final MainActivity a = start();
        waitApps(a, 1000);
        Report.run(4, "300 apps atualizando de uma vez", () -> {
            int before = MainActivity.LOADS_RUN.get();
            LauncherApps.Callback cb = Report.get(a, "appsCallback");
            List<ResolveInfo> more = new ArrayList<>(base);
            more.add(ri("com.teste.novo", "App Novo Instalado"));
            install(more);
            for (int i = 0; i < 300; i++) cb.onPackageChanged("com.teste.app" + i, Process.myUserHandle());
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitApps(a, 1001);
            int loads = MainActivity.LOADS_RUN.get() - before;
            return loads <= 2 ? "virou " + loads + " recarga(s), lista atualizada"
                    : Report.fail(loads + " recargas pesadas (devia juntar)");
        });
        Report.run(4, "60 pedidos de recarga seguidos", () -> {
            int before = MainActivity.LOADS_RUN.get();
            for (int i = 0; i < 60; i++) a.loadApps();
            waitApps(a, 1001);
            Thread.sleep(300);
            idle();
            int loads = MainActivity.LOADS_RUN.get() - before;
            return loads <= 6 ? loads + " leituras de verdade" : Report.fail(loads + " leituras");
        });
        Report.run(4, "threads não acumulam", () -> {
            int t0 = Thread.activeCount();
            for (int i = 0; i < 100; i++) a.loadApps();
            waitApps(a, 1001);
            int grow = Thread.activeCount() - t0;
            return grow <= 2 ? "+" + grow + " threads" : Report.fail("+" + grow + " threads");
        });
    }

    // ================================================================== N5 dados malucos
    @Test public void n05_dados_estranhos_e_config_corrompida() throws Exception {
        List<ResolveInfo> l = apps(300);
        String longName = new String(new char[600]).replace('\0', 'W');
        String[] weird = {"", "   ", "😀🔥💯", longName, "واتساب", "微信", "123", "WhatsApp", "WhatsApp", "linha\nquebrada",
                "#$%&", "Ñandú", "​", "ÁÉÍÓÚ", "a"};
        for (int i = 0; i < weird.length; i++) l.add(ri("com.estranho" + i, weird[i]));
        l.add(ri("com.semnome", null));
        install(l);
        SharedPreferences p = app().getSharedPreferences("leve", Context.MODE_PRIVATE);
        p.edit().putInt("tema", 999).putInt("estilo", -7).putInt("ordem", 55).putBoolean("ess_init", true)
                .putString("ess", "web:|\nweb:x\n||||\nlixo\nweb:https://a.com|Nome|com.x|extra|mais\n\n\n")
                .putString("dock0", "nada").putString("music_app", "x").putInt("limite", -50)
                .putString("u:abc", "texto no lugar de número").putLong("drain_t", -1)
                .putString("google_open", "com.nao.existe").apply();
        final int total = l.size();
        final MainActivity[] holder = new MainActivity[1];
        Report.run(5, "abre com nomes estranhos e configurações quebradas", () -> {
            holder[0] = start();
            waitApps(holder[0], total);
            return total + " apps";
        });
        final MainActivity a = holder[0];
        if (a == null) return;
        Report.run(5, "todas as abas e organizações com dados estranhos", () -> {
            for (int t = 0; t < 6; t++) { Report.call(a, "selectTab", t); idle(); }
            Report.call(a, "selectTab", 3);
            for (int m = 0; m < 4; m++) { Report.set(a, "sortMode", m); Report.call(a, "refreshRows"); }
            ListView list = Report.get(a, "list");
            for (int i = 0; i < list.getAdapter().getCount(); i++) list.getAdapter().getView(i, null, list);
            return "ok";
        });
        Report.run(5, "busca com acentos, emoji e símbolos", () -> {
            EditText sb = Report.get(a, "searchBox");
            for (String q : new String[]{"ñ", "😀", "%", "\\", "[", "(", "*", ".*", "ÁÉ", "aeiou", " ", "​"}) {
                type(sb, q);
            }
            type(sb, "");
            return "ok";
        });
        Report.run(5, "Google com texto gigante e caracteres especiais", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5000; i++) sb.append("ç&?=#% ");
            clearStarted();
            a.googleSearch(sb.toString());
            a.googleSearch("&q=hack#frag");
            a.googleSearch("   ");
            return "ok";
        });
        Report.run(5, "Home com tudo quebrado", () -> {
            a.onBackPressed();
            Report.call(a, "resetHome");
            return "ok";
        });
    }

    // ================================================================== N7 5 mil apps
    @Test public void n07_dificil_5000_apps() throws Exception {
        long m0 = Report.usedMb();
        install(apps(5000));
        final MainActivity a = start();
        final long load = waitApps(a, 5000);
        final long m1 = Report.usedMb();
        Report.run(7, "carregar 5.000 apps (fora da tela principal)", () -> Report.budget("leitura", load, 4000, "ms"));
        Report.run(7, "memória com 5.000 apps", () -> Report.budget("RAM a mais", m1 - m0, 40, "MB"));
        Report.run(7, "busca por tecla com 5.000 apps", () -> {
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            long total = 0;
            String s = "qwerty";
            for (char ch : s.toCharArray()) { long t = System.nanoTime(); sb.append("" + ch); total += System.nanoTime() - t; }
            sb.setText("");
            return Report.budget("por tecla", ms(total) / s.length(), 16, "ms");
        });
        Report.run(7, "aba Todos por categoria", () -> {
            Report.set(a, "sortMode", 1);
            long t = System.nanoTime();
            Report.call(a, "refreshRows");
            Report.set(a, "sortMode", 0);
            return Report.budget("refresh", ms(System.nanoTime() - t), 30, "ms");
        });
    }

    // ================================================================== N8 vazamento de memória
    @Test public void n08_muito_dificil_recriar_50x() throws Exception {
        install(apps(300));
        final List<WeakReference<MainActivity>> refs = new ArrayList<>();
        final int threads0 = Thread.activeCount();
        Report.run(8, "trocar tema/estilo 50x (recriar a tela)", () -> {
            SharedPreferences p = app().getSharedPreferences("leve", Context.MODE_PRIVATE);
            for (int i = 0; i < 50; i++) {
                p.edit().putInt("tema", i % 10).putInt("estilo", i % 4).commit();
                ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
                waitApps(c.get(), 300);
                Report.call(c.get(), "selectTab", i % 6);
                idle();
                refs.add(new WeakReference<>(c.get()));
                c.pause().stop().destroy();
                idle();
            }
            return "50 recriações";
        });
        Report.run(8, "telas antigas liberadas da memória", () -> {
            ShadowToast.reset();
            ShadowDialog.reset();
            ShadowAlertDialog.reset();
            Thread.sleep(200);
            idle();
            Report.usedMb();
            int alive = 0;
            for (WeakReference<MainActivity> r : refs) if (r.get() != null) alive++;
            return alive <= 2 ? alive + " presas (ok)" : Report.fail(alive + " telas vazando");
        });
        Report.run(8, "threads não acumulam após recriar", () -> {
            Thread.sleep(300);
            int grow = Thread.activeCount() - threads0;
            return grow <= 3 ? "+" + grow : Report.fail("+" + grow + " threads vivas");
        });
    }

    // ================================================================== N9 EXTREMO
    @Test public void n09_extremo_20mil_apps() throws Exception {
        long m0 = Report.usedMb();
        install(apps(20000));
        SharedPreferences p = app().getSharedPreferences("leve", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        StringBuilder ess = new StringBuilder();
        for (int i = 0; i < 500; i++) ess.append("web:https://site").append(i).append(".com|Site ").append(i).append('\n');
        e.putString("ess", ess.toString()).putBoolean("ess_init", true);
        for (int i = 0; i < 10000; i++) e.putInt("u:com.teste.app" + i + "/com.teste.app" + i + ".Main#0", i);
        e.commit();
        final MainActivity a = start();
        final long load = waitApps(a, 20000);
        final long m1 = Report.usedMb();
        Report.run(9, "carregar 20.000 apps", () -> Report.budget("leitura", load, 10000, "ms"));
        Report.run(9, "memória 20 mil apps + 10 mil usos", () -> Report.budget("RAM a mais", m1 - m0, 80, "MB"));
        Report.run(9, "botão Home (voltar ao início)", () -> {
            long t = System.nanoTime();
            for (int i = 0; i < 10; i++) { Report.call(a, "selectTab", 3); Report.call(a, "resetHome"); }
            return Report.budget("Home", ms(System.nanoTime() - t) / 10, 50, "ms");
        });
        Report.run(9, "aba Frequentes com 10 mil usos", () -> {
            long t = System.nanoTime();
            Report.call(a, "selectTab", 2);
            return Report.budget("abrir", ms(System.nanoTime() - t), 50, "ms");
        });
        Report.run(9, "busca por tecla com 20 mil apps", () -> {
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            long total = 0;
            String s = "brave";
            for (char ch : s.toCharArray()) { long t = System.nanoTime(); sb.append("" + ch); total += System.nanoTime() - t; }
            sb.setText("");
            return Report.budget("por tecla", ms(total) / s.length(), 33, "ms");
        });
        Report.run(9, "500 atalhos nos Essenciais", () -> {
            long t = System.nanoTime();
            Report.call(a, "rebuildEssentials");
            return Report.budget("montar", ms(System.nanoTime() - t), 30, "ms");
        });
    }

    // ================================================================== N10 IMPOSSÍVEL
    @Test public void n10_impossivel() throws Exception {
        install(apps(2000));
        final MainActivity a = start();
        waitApps(a, 2000);
        Report.run(10, "núcleo de CPU liga no meio da medição", () -> {
            Report.set(a, "tab", 5);
            Report.set(a, "lite", false);
            Report.set(a, "cpuMax", new long[]{1});
            for (int i = 0; i < 5; i++) Report.call(a, "readStats");
            return "não fechou";
        });
        Report.run(10, "macaco: 15.000 ações aleatórias", () -> {
            Random r = new Random(42);
            EditText sb = Report.get(a, "searchBox");
            ListView list = Report.get(a, "list");
            ActivityController<MainActivity> c = controller(a);
            int crashes = 0;
            String first = null;
            for (int i = 0; i < 15000; i++) {
                try {
                    switch (r.nextInt(14)) {
                        case 0: Report.call(a, "selectTab", r.nextInt(8) - 1); break;
                        case 1: sb.append(String.valueOf((char) ('a' + r.nextInt(26)))); break;
                        case 2: sb.setText(""); break;
                        case 3: a.onBackPressed(); break;
                        case 4: {
                            int n = list.getAdapter().getCount();
                            if (n > 0) { int pos = r.nextInt(n); list.performItemClick(list.getAdapter().getView(pos, null, list), pos, pos); }
                            break;
                        }
                        case 5: a.googleInput.setText("q" + r.nextInt(999)); a.submitGoogle(); break;
                        case 6: Report.set(a, "sortMode", r.nextInt(4)); Report.call(a, "refreshRows"); break;
                        case 7: a.onTrimMemory(new int[]{5, 10, 15, 20, 40, 60, 80}[r.nextInt(7)]); break;
                        case 8: if (r.nextInt(20) == 0) { c.pause(); c.resume(); } break;
                        case 9: a.scheduleReload(); break;
                        case 10: {
                            long t = android.os.SystemClock.uptimeMillis();
                            View root = a.getWindow().getDecorView();
                            float x = r.nextFloat() * 1000, y = r.nextFloat() * 2000;
                            MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                            MotionEvent u = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x + r.nextInt(400) - 200, y, 0);
                            a.dispatchTouchEvent(d); a.dispatchTouchEvent(u);
                            d.recycle(); u.recycle();
                            break;
                        }
                        case 11: a.setGoogleBar(r.nextBoolean()); break;
                        case 12: Report.call(a, "resetHome"); break;
                        default: idle();
                    }
                    if (i % 50 == 0) { ShadowDialog.reset(); ShadowToast.reset(); clearStarted(); idle(); }
                } catch (Throwable t) {
                    crashes++;
                    if (first == null) {
                        Throwable x = t; while (x.getCause() != null) x = x.getCause();
                        StackTraceElement at = null;
                        for (StackTraceElement el : x.getStackTrace()) if (el.getClassName().startsWith("com.leve")) { at = el; break; }
                        first = x + " em " + at;
                    }
                }
            }
            a.setGoogleBar(true);
            return crashes == 0 ? "0 travamentos" : Report.fail(crashes + " crashes; 1º: " + first);
        });
        Report.run(10, "100 mil apps: busca por tecla", () -> {
            // 100 mil apps direto na lista (o PackageManager de teste não aguenta tantos)
            List<Object> all = Report.get(a, "allApps");
            List<Object> copy = new ArrayList<>(all);
            java.lang.reflect.Constructor<?> k = all.get(0).getClass().getDeclaredConstructors()[0];
            k.setAccessible(true);
            Random r = new Random(7);
            all.clear();
            for (int i = 0; i < 100_000; i++) {
                Object src = copy.get(i % copy.size());
                String label = Report.<String>get(src, "label") + " " + Integer.toString(r.nextInt(1 << 20), 36);
                all.add(k.newInstance(label, new ComponentName("p" + i, "p" + i + ".M"), Process.myUserHandle(), 0L, false));
            }
            Report.call(a, "selectTab", 3); idle();
            EditText sb = Report.get(a, "searchBox");
            long total = 0;
            String s = "zz";
            for (char ch : s.toCharArray()) { long t = System.nanoTime(); sb.append("" + ch); total += System.nanoTime() - t; }
            sb.setText("");
            return Report.budget("por tecla", ms(total) / s.length(), 50, "ms");
        });
    }
}
