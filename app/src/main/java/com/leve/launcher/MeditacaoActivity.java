package com.leve.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Locale;

/**
 * Leve Meditar: tela de meditação (Wim Hof, meditação guiada e som para dormir).
 * A interface é o arquivo assets/meditacao.html; a voz usa o "Texto para fala" do celular.
 * Ao terminar, o HTML chama Leve.close(): a tela volta ao normal, o app fecha e o celular apaga sozinho.
 */
public class MeditacaoActivity extends Activity implements TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Leve");
        web.loadUrl("file:///android_asset/meditacao.html");
        setContentView(web);
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(new Locale("pt", "BR"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("pt"));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { done(id); }
            @Override public void onError(String id) { done(id); }
            @Override public void onStop(String id, boolean interrupted) { done(id); }
        });
        ttsReady = true;
        js("window.__voicesReady&&__voicesReady()");
    }

    private void done(String id) {
        js("window.__ttsDone&&__ttsDone('" + id.replace("'", "") + "')");
    }

    private void js(final String code) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() { if (web != null) web.evaluateJavascript(code, null); }
        });
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Métodos que o HTML chama como Leve.xxx() */
    private class Bridge {
        @JavascriptInterface public boolean hasTts() { return ttsReady; }

        @JavascriptInterface public void speak(String text, float rate, float pitch, float vol, String id) {
            if (!ttsReady) { done(id); return; }
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            Bundle p = new Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.max(0f, Math.min(1f, vol)));
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, id) != TextToSpeech.SUCCESS) done(id);
        }

        @JavascriptInterface public void stopSpeak() { if (ttsReady) tts.stop(); }

        /** Vozes em português instaladas: [{"id":"...","name":"..."}] */
        @JavascriptInterface public String voices() {
            StringBuilder sb = new StringBuilder("[");
            if (ttsReady) {
                try {
                    int n = 0;
                    for (Voice v : tts.getVoices()) {
                        Locale l = v.getLocale();
                        if (l == null || !"pt".equals(l.getLanguage())) continue;
                        if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
                        if (n++ > 0) sb.append(',');
                        String label = (n) + ". " + l.getDisplayCountry(new Locale("pt", "BR"))
                                + (v.isNetworkConnectionRequired() ? " (internet)" : " (offline)")
                                + " · " + v.getName();
                        sb.append("{\"id\":\"").append(esc(v.getName())).append("\",\"name\":\"").append(esc(label)).append("\"}");
                    }
                } catch (Exception ignored) { }
            }
            return sb.append(']').toString();
        }

        @JavascriptInterface public void setVoice(String name) {
            if (!ttsReady) return;
            try {
                if (name == null || name.length() == 0) { tts.setLanguage(new Locale("pt", "BR")); return; }
                for (Voice v : tts.getVoices()) if (v.getName().equals(name)) { tts.setVoice(v); return; }
            } catch (Exception ignored) { }
        }

        /** Mantém a tela ligada durante a sessão (sem bloquear no meio). */
        @JavascriptInterface public void keepOn(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }});
        }

        /** Brilho mínimo enquanto a tela está preta. */
        @JavascriptInterface public void dim(final boolean on) {
            runOnUiThread(new Runnable() { @Override public void run() {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            }});
        }

        /** Fim da sessão: solta a tela e fecha. O celular apaga pelo tempo de tela normal. */
        @JavascriptInterface public void close() {
            runOnUiThread(new Runnable() { @Override public void run() {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                finishAndRemoveTask();
            }});
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?__back():false", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (!"true".equals(v)) finish();
            }
        });
    }

    // Não pausa o WebView no onPause: o som continua se a tela apagar ou se você sair do app.

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (web != null) { web.removeJavascriptInterface("Leve"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
