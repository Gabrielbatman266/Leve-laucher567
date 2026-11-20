package com.leve.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

/** N6 — layout em telas pequenas, celular fraco e letra grande do sistema (medição de texto real). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StressLayoutTest {

    private final List<ActivityController<MainActivity>> open = new ArrayList<>();

    @After public void close() {
        for (ActivityController<MainActivity> c : open) try { c.pause().stop().destroy(); } catch (Throwable ignored) { }
    }

    private void scenario(String name, float fontScale, int estilo) throws Exception {
        if (fontScale != 1f) RuntimeEnvironment.setFontScale(fontScale);
        StressTest.install(StressTest.apps(60));
        SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("leve", Context.MODE_PRIVATE);
        p.edit().putInt("estilo", estilo).commit();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        open.add(c);
        final MainActivity a = c.get();
        StressTest.waitApps(a, 60);
        final DisplayMetrics dm = a.getResources().getDisplayMetrics();
        final View decor = a.getWindow().getDecorView();
        final Runnable layout = () -> {
            decor.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, dm.widthPixels, dm.heightPixels);
            shadowOf(Looper.getMainLooper()).idle();
        };
        final float d = dm.density;
        final String tag = name + String.format(" (%.0fx%.0fdp, letra %.0f%%, %s)",
                dm.widthPixels / d, dm.heightPixels / d, fontScale * 100, MainActivity.STYLE_NAMES[estilo]);

        Report.run(6, tag + ": sobra espaço pra lista de apps", () -> {
            Report.call(a, "selectTab", 3);
            layout.run();
            View list = Report.get(a, "list");
            float h = list.getHeight() / d;
            return h >= 150 ? String.format("lista com %.0fdp", h) : Report.fail(String.format("lista só com %.0fdp", h));
        });
        Report.run(6, tag + ": barra do Google confortável", () -> {
            Report.call(a, "selectTab", 0);
            layout.run();
            View g = a.googleBar;
            float h = g.getHeight() / d, w = g.getWidth() / d;
            if (g.getVisibility() != View.VISIBLE) return Report.fail("escondida");
            if (h < 44 || h > 72) return Report.fail(String.format("altura %.0fdp", h));
            TextView hint = a.googleInput;
            Layout l = hint.getLayout();
            float hintW = hint.getPaint().measureText(hint.getHint().toString());
            if (hintW > hint.getWidth()) return Report.fail(String.format("texto 'Pesquisar no Google' cortado (%.0f > %d px)", hintW, hint.getWidth()));
            return String.format("%.0fx%.0fdp", w, h);
        });
        Report.run(6, tag + ": botões rápidos sem texto cortado", () -> {
            layout.run();
            View q = a.quickRow;
            if (q.getVisibility() != View.VISIBLE) return "escondidos neste estilo";
            StringBuilder cut = new StringBuilder();
            for (int i = 0; i < ((ViewGroup) q).getChildCount(); i++) {
                TextView t = (TextView) ((ViewGroup) q).getChildAt(i);
                float need = t.getPaint().measureText(t.getText().toString()) + t.getTotalPaddingLeft() + t.getTotalPaddingRight();
                if (need > t.getWidth() + 1) cut.append(t.getText()).append(' ');
                if (t.getWidth() < 40 * d || t.getHeight() < 40 * d) cut.append("(botão pequeno demais p/ o dedo) ");
            }
            return cut.length() == 0 ? "ok" : Report.fail("cortados: " + cut);
        });
        Report.run(6, tag + ": barra de baixo cabe na tela e os nomes aparecem", () -> {
            layout.run();
            LinearLayout[] dock = Report.get(a, "dockViews");
            int[] loc = new int[2];
            dock[0].getLocationInWindow(loc);
            float bottom = (loc[1] + dock[0].getHeight()) / d;
            if (bottom > dm.heightPixels / d + 1) return Report.fail(String.format("barra sai da tela (%.0fdp)", bottom));
            StringBuilder cut = new StringBuilder();
            for (LinearLayout b : dock) {
                TextView nm = (TextView) b.getChildAt(1);
                Layout l = nm.getLayout();
                if (l != null && l.getEllipsisCount(0) > 0) cut.append(nm.getText()).append(' ');
            }
            return cut.length() == 0 ? "ok" : Report.fail("nomes cortados: " + cut);
        });
        Report.run(6, tag + ": abas e relógio cabem", () -> {
            layout.run();
            View tabs = Report.get(a, "tabScroll");
            float top = tabs.getTop() / d;
            int[] loc = new int[2];
            tabs.getLocationInWindow(loc);
            float y = loc[1] / d;
            return y < dm.heightPixels / d * 0.62f ? String.format("abas em %.0fdp", y)
                    : Report.fail(String.format("abas muito para baixo (%.0fdp de %.0f)", y, dm.heightPixels / d));
        });
    }

    @Config(qualifiers = "w393dp-h851dp-xxhdpi")
    @Test public void normal_xiaomi() throws Exception { scenario("Xiaomi", 1f, 0); }

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test public void samsung_fraco() throws Exception { scenario("Samsung fraco", 1f, 0); }

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test public void samsung_fraco_letra_grande() throws Exception { scenario("Samsung fraco", 1.3f, 2); }

    @Config(qualifiers = "w320dp-h480dp-mdpi")
    @Test public void tela_minuscula() throws Exception { scenario("Tela minúscula", 1f, 0); }

    @Config(qualifiers = "w320dp-h480dp-mdpi")
    @Test public void tela_minuscula_letra_enorme() throws Exception { scenario("Tela minúscula", 2f, 2); }

    @Config(qualifiers = "w393dp-h851dp-xxhdpi")
    @Test public void xiaomi_letra_enorme_foco() throws Exception { scenario("Xiaomi", 2f, 3); }
}
