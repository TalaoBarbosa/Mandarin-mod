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

    /**
     * Tira o que não é voz (pouca energia ou sem periodicidade), conserta os erros de oitava do YIN e apaga trechos
     * curtos demais.
     *
     * Erro de oitava é o YIN pular exatamente uma oitava (12 semitons) de um quadro para o seguinte: a voz de verdade
     * não muda mais que ~1 semitom em 10 ms, então isso só pode ser erro de medida. Antes cada quadro era comparado com a
     * mediana da fala inteira, e o que ficasse a mais de ~8 semitons abaixo (ou ~10 acima) dela era dobrado (ou cortado
     * pela metade). Quem varia muito a voz (um 2º tom bem subido, um 3º tom bem baixo, uma sílaba gritada e a outra não)
     * tinha a subida cortada pela metade: no desenho a voz aparecia caindo, e o mod dizia "não subiu".
     */
    public static double[] clean(Track t) {
        final int n = t.size();
        final double[] f = t.f0.clone();
        double mx = 0;
        for (double r : t.rms) mx = Math.max(mx, r);
        for (int i = 0; i < n; i++) if (t.rms[i] < 0.06 * mx || t.ap[i] > 0.3) f[i] = 0;
        fixOctaveJumps(f);
        // longe demais da mediana para ser voz de uma mesma palavra (mais de 14 semitons): é oitava errada de um trecho
        // inteiro; volta uma oitava se isso põe no lugar, senão não é voz
        final double m = median(f);
        if (m <= 0) return f;
        for (int i = 0; i < n; i++) {
            if (f[i] <= 0) continue;
            double d = st(f[i]) - st(m);
            if (Math.abs(d) <= 14) continue;
            double back = d - Math.signum(d) * 12;
            f[i] = Math.abs(back) <= 9 ? f[i] * Math.pow(2, -Math.signum(d)) : 0;
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

    /** salto maior que isto entre dois quadros com voz seguidos (ou com até MAX_GAP quadros sem voz no meio) é erro de medida */
    static final double JUMP = 5.0;
    static final int MAX_GAP = 3;

    /**
     * Conserta pulos de oitava dentro de cada trecho contínuo de voz: parte o trecho onde há salto > JUMP semitons; o
     * pedaço mais comprido é o certo; andando para os lados a partir dele, um pedaço que chega com salto de 12 ± 3
     * semitons em relação ao vizinho (já consertado) volta uma oitava. Entre sílabas (silêncio maior que MAX_GAP) não
     * compara: ali a voz pode mesmo mudar bastante.
     */
    static void fixOctaveJumps(double[] f) {
        int n = f.length, i = 0;
        while (i < n) {
            if (f[i] <= 0) { i++; continue; }
            int last = i, j = i;
            while (j < n && (f[j] > 0 || j - last <= MAX_GAP)) { if (f[j] > 0) last = j; j++; }
            fixStretch(f, i, last + 1);
            i = last + 1;
        }
    }

    private static void fixStretch(double[] f, int s, int e) {
        java.util.List<int[]> pieces = new java.util.ArrayList<>();
        int start = -1, prev = -1;
        for (int k = s; k < e; k++) {
            if (f[k] <= 0) continue;
            if (prev >= 0 && Math.abs(st(f[k]) - st(f[prev])) > JUMP) { pieces.add(new int[]{start, prev + 1}); start = k; }
            if (start < 0) start = k;
            prev = k;
        }
        if (start >= 0) pieces.add(new int[]{start, prev + 1});
        if (pieces.size() < 2) return;
        int anchor = 0;
        for (int p = 1; p < pieces.size(); p++) if (voiced(f, pieces.get(p)) > voiced(f, pieces.get(anchor))) anchor = p;
        for (int p = anchor + 1; p < pieces.size(); p++) shiftIfOctave(f, pieces.get(p), edge(f, pieces.get(p - 1), false), edge(f, pieces.get(p), true));
        for (int p = anchor - 1; p >= 0; p--) shiftIfOctave(f, pieces.get(p), edge(f, pieces.get(p + 1), true), edge(f, pieces.get(p), false));
    }

    private static int voiced(double[] f, int[] p) { int c = 0; for (int k = p[0]; k < p[1]; k++) if (f[k] > 0) c++; return c; }

    /** a altura (semitons) na ponta do pedaço: mediana dos 3 primeiros (first) ou dos 3 últimos quadros com voz */
    private static double edge(double[] f, int[] p, boolean first) {
        double[] v = new double[3];
        int c = 0;
        if (first) { for (int k = p[0]; k < p[1] && c < 3; k++) if (f[k] > 0) v[c++] = st(f[k]); }
        else { for (int k = p[1] - 1; k >= p[0] && c < 3; k--) if (f[k] > 0) v[c++] = st(f[k]); }
        double[] a = Arrays.copyOf(v, c);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    /** o pedaço chega no vizinho com um salto de uma oitava (12 ± 3 semitons): volta uma oitava */
    private static void shiftIfOctave(double[] f, int[] p, double neighbor, double mine) {
        double d = mine - neighbor;
        double k = Math.abs(d - 12) < 3 ? 0.5 : Math.abs(d + 12) < 3 ? 2 : 1;
        if (k == 1) return;
        for (int i = p[0]; i < p[1]; i++) if (f[i] > 0) f[i] *= k;
    }

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
