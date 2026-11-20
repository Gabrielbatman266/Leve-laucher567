package com.leve.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;

/** Gera prints da tela inicial (build/stress/*.png) para conferir o visual. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {

    private void shot(String name, int tab) throws Exception {
        StressTest.install(StressTest.apps(40));
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("leve", 0).edit()
                .putBoolean("anim_ui", false).putBoolean("anim", false).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        StressTest.waitApps(a, 40);
        a.getSharedPreferences("leve", 0).edit().putBoolean("banner_off", true).commit();
        Report.<View>get(a, "defaultBanner").setVisibility(View.GONE);
        Report.call(a, "selectTab", tab);
        shadowOf(Looper.getMainLooper()).idle();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, dm.widthPixels, dm.heightPixels);
        Bitmap b = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(b));
        File out = new File(System.getProperty("user.dir"), "build/stress/" + name + ".png");
        out.getParentFile().mkdirs();
        try (FileOutputStream f = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 100, f); }
    }

    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    @Test public void xiaomi() throws Exception { shot("launcher-xiaomi", 3); }

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test public void samsung() throws Exception { shot("launcher-samsung-fraco", 0); }
}
