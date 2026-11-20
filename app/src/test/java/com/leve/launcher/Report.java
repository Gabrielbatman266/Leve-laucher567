package com.leve.launcher;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Placar dos testes de estresse: cada verificação vira uma linha em build/stress/launcher.tsv. */
final class Report {
    static final File OUT = new File(System.getProperty("user.dir"), "build/stress/launcher.tsv");

    static synchronized void check(int level, String name, boolean ok, String detail) {
        OUT.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(OUT, true)) {
            w.write(level + "\t" + name + "\t" + (ok ? "PASS" : "FAIL") + "\t" + detail.replace('\t', ' ').replace('\n', ' ') + "\n");
        } catch (Exception ignored) { }
        System.out.println((ok ? "  ✔ " : "  ✘ ") + "N" + level + " " + name + " — " + detail);
    }

    /** Executa e marca como falha se der exceção (crash = falha, mas o resto continua). */
    static void run(int level, String name, Check c) {
        try {
            String d = c.run();
            boolean ok = d == null || !d.startsWith("FAIL:");
            check(level, name, ok, d == null ? "ok" : ok ? d : d.substring(5));
        } catch (Throwable t) {
            Throwable r = t;
            while (r.getCause() != null && r != r.getCause()) r = r.getCause();
            StackTraceElement at = null;
            for (StackTraceElement e : r.getStackTrace()) if (e.getClassName().startsWith("com.leve")) { at = e; break; }
            check(level, name, false, "CRASH " + r + (at != null ? " em " + at : ""));
        }
    }

    interface Check { String run() throws Exception; }

    static String fail(String why) { return "FAIL:" + why; }

    static String budget(String what, double value, double max, String unit) {
        String d = String.format(java.util.Locale.ROOT, "%s %.1f %s (limite %.0f)", what, value, unit, max);
        return value <= max ? d : fail(d);
    }

    // ---------- reflexão (os campos do launcher são privados) ----------

    @SuppressWarnings("unchecked")
    static <T> T get(Object o, String field) {
        try {
            Field f = find(o.getClass(), field);
            f.setAccessible(true);
            return (T) f.get(o);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void set(Object o, String field, Object v) {
        try {
            Field f = find(o.getClass(), field);
            f.setAccessible(true);
            f.set(o, v);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Field find(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object call(Object o, String method, Object... args) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterTypes().length != args.length) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(o, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause();
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    if (t instanceof Error) throw (Error) t;
                    throw new RuntimeException(t);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        }
        throw new RuntimeException("método não existe: " + method);
    }

    static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException ignored) { } }
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }
}
