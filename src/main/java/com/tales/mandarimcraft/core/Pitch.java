package com.tales.mandarimcraft.core;

import java.util.Arrays;

/** Altura da voz (F0) quadro a quadro, pelo método YIN. Áudio mono 16 kHz em float (-1..1). */
public final class Pitch {
    public static final int SR = 16000, FRAME = 640, HOP = 160; // quadro de 40 ms, passo de 10 ms
    public static final double FMIN = 60, FMAX = 500;

    private Pitch() {}

    public static final class Track {
        public final double[] f0, rms, ap;
        Track(int n) { f0 = new double[n]; rms = new double[n]; ap = new double[n]; Arrays.fill(ap, 1.0); }
        public int size() { return f0.length; }
    }

    public static Track yin(float[] x) {
        final int tmin = (int) (SR / FMAX), tmax = (int) (SR / FMIN), w = FRAME - tmax;
        final int n = Math.max(0, (x.length - FRAME) / HOP + 1);
        final Track t = new Track(n);
        final double[] d = new double[tmax + 2], cm = new double[tmax + 2];
        for (int i = 0; i < n; i++) {
            final int o = i * HOP;
            double e = 0;
            for (int k = 0; k < w + tmax; k++) e += (double) x[o + k] * x[o + k];
            t.rms[i] = Math.sqrt(e / (w + tmax));
            for (int tau = 1; tau <= tmax; tau++) {
                double s = 0;
                for (int k = 0; k < w; k++) { double dd = x[o + k] - x[o + k + tau]; s += dd * dd; }
                d[tau] = s;
            }
            cm[0] = 1; double cs = 0;
            for (int tau = 1; tau <= tmax; tau++) { cs += d[tau]; cm[tau] = d[tau] * tau / Math.max(cs, 1e-12); }
            int tau = -1;
            for (int k = tmin; k < tmax; k++) {
                if (cm[k] < 0.15) { while (k + 1 < tmax && cm[k + 1] < cm[k]) k++; tau = k; break; }
            }
            if (tau < 0) {
                int best = tmin;
                for (int k = tmin; k < tmax; k++) if (cm[k] < cm[best]) best = k;
                if (cm[best] < 0.35) tau = best;
            }
            if (tau > 0) {
                double sh = 0;
                if (tau >= 1 && tau < tmax) {
                    double a0 = cm[tau - 1], b0 = cm[tau], c0 = cm[tau + 1], den = a0 - 2 * b0 + c0;
                    if (Math.abs(den) > 1e-12) sh = 0.5 * (a0 - c0) / den;
                }
                t.f0[i] = SR / (tau + sh); t.ap[i] = cm[tau];
            }
        }
        return t;
    }

    /** Tira o que não é voz (pouca energia ou sem periodicidade), corrige oitava e apaga trechos curtos demais. */
    public static double[] clean(Track t) {
        final int n = t.size();
        final double[] f = t.f0.clone();
        double mx = 0;
        for (double r : t.rms) mx = Math.max(mx, r);
        for (int i = 0; i < n; i++) if (t.rms[i] < 0.06 * mx || t.ap[i] > 0.3) f[i] = 0;
        final double m = median(f);
        if (m <= 0) return f;
        for (int i = 0; i < n; i++) {
            if (f[i] <= 0) continue;
            double r = f[i] / m;
            if (r < 0.62) f[i] = f[i] * 2 / m < 1.6 ? f[i] * 2 : 0;
            else if (r > 1.75) f[i] = f[i] / 2 / m > 0.62 ? f[i] / 2 : 0;
        }
        for (int i = 1; i < n - 1; i++) {
            if (f[i] > 0 && f[i - 1] > 0 && f[i + 1] > 0) {
                double a = st(f[i - 1]), b = st(f[i]), c = st(f[i + 1]);
                if (Math.abs(b - a) > 2.5 && Math.abs(b - c) > 2.5) f[i] = 0;
            }
        }
        int i = 0;
        while (i < n) {
            if (f[i] > 0) {
                int j = i;
                while (j < n && f[j] > 0) j++;
                if (j - i < 4) Arrays.fill(f, i, j, 0);
                i = j;
            } else i++;
        }
        return f;
    }

    static double st(double hz) { return 12 * Math.log(hz / 100.0) / Math.log(2); }

    /** mediana dos valores > 0 (0 se não houver) */
    public static double median(double[] v) {
        int c = 0;
        for (double x : v) if (x > 0) c++;
        if (c == 0) return 0;
        double[] a = new double[c]; c = 0;
        for (double x : v) if (x > 0) a[c++] = x;
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }
}
