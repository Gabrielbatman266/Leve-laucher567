package com.leve.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Botão "Atualizar": confere a última Release no GitHub, baixa o APK e abre o instalador.
 * O número da versão do app é o número da compilação do GitHub (igual ao da Release, ex.: v16).
 */
final class Updater {

    static final String REPO = "Gabrielbatman266/Leve-laucher567";
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String FILE_NAME = "LeveLauncher.apk";
    private static final String APK = PAGE + "/download/" + FILE_NAME;
    private static final String AGENT = "LeveLauncher";

    private final Activity act;
    private final int theme;
    private volatile boolean busy;

    Updater(Activity act, int dialogTheme) {
        this.act = act;
        this.theme = dialogTheme;
    }

    static int installedVersion(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    void check() {
        if (busy) return;
        busy = true;
        Toast.makeText(act, "Procurando atualização…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final int latest = fetchLatestNumber();
                    final int mine = installedVersion(act);
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (latest <= 0) fail();
                            else if (latest <= mine) {
                                Toast.makeText(act, "Você já tem a versão mais nova (v" + mine + ")",
                                        Toast.LENGTH_LONG).show();
                            } else askDownload(latest, mine);
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() { busy = false; fail(); }
                    });
                }
            }
        }).start();
    }

    private int fetchLatestNumber() throws Exception {
        HttpURLConnection c = open(API);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"[^\"]*?(\\d+)\"").matcher(buf.toString("UTF-8"));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } finally {
            c.disconnect();
        }
    }

    private void askDownload(int latest, int mine) {
        new AlertDialog.Builder(act, theme)
                .setTitle("Atualização disponível")
                .setMessage("Versão nova: v" + latest + "\nVocê tem: v" + mine
                        + "\n\nBaixar e instalar agora? Suas configurações continuam.")
                .setPositiveButton("Baixar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { download(); }
                })
                .setNegativeButton("Depois", null)
                .show();
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, "Permita \"Instalar apps desconhecidos\" e toque em Atualizar de novo",
                    Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {
                fail();
            }
            return false;
        }
        return true;
    }

    private void download() {
        if (!canInstall() || busy) return;
        busy = true;
        final AlertDialog progress = new AlertDialog.Builder(act, theme)
                .setTitle("Baixando atualização")
                .setMessage("0%")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final File apk = new File(act.getCacheDir(), FILE_NAME);
                    HttpURLConnection c = open(APK);
                    try {
                        long total = c.getContentLength();
                        InputStream in = c.getInputStream();
                        OutputStream out = new FileOutputStream(apk);
                        byte[] b = new byte[16384];
                        long done = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(b)) > 0) {
                            out.write(b, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) (done * 100L / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    act.runOnUiThread(new Runnable() {
                                        @Override public void run() { progress.setMessage(pct + "%"); }
                                    });
                                }
                            }
                        }
                        out.close();
                        in.close();
                    } finally {
                        c.disconnect();
                    }
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            progress.dismiss();
                            install(apk);
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            progress.dismiss();
                            fail();
                        }
                    });
                }
            }
        }).start();
    }

    private void install(File apk) {
        Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uri(act, apk.getName()) : Uri.fromFile(apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            fail();
        }
    }

    /** Se algo der errado, oferece abrir a página de downloads no navegador. */
    private void fail() {
        new AlertDialog.Builder(act, theme)
                .setTitle("Não deu para atualizar agora")
                .setMessage("Confira a internet e tente de novo, ou baixe pelo navegador:\n\n" + PAGE)
                .setPositiveButton("Abrir no navegador", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", AGENT);
        // Segue redirecionamentos entre hosts (github.com -> servidor de arquivos)
        for (int i = 0; i < 5; i++) {
            int code = c.getResponseCode();
            if (code < 300 || code >= 400) break;
            String next = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(next).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", AGENT);
        }
        if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
        return c;
    }
}
