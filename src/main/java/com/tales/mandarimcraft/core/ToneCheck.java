package com.tales.mandarimcraft.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Confere se cada sílaba saiu no tom esperado, pela curva da voz.
 * Regra: leniente com o tom certo e rígida só com o que está claramente errado (2º que vira 3º, 4º que sobe…).
 * Testado com as gravações humanas do app: ~96% das sílabas certas passam e ~94% das trocas de tom são pegas.
 */
public final class ToneCheck {
    private ToneCheck() {}

    public static final class Feats {
        public double head, tail, mn, mx, rng, imn, imx, rise, fall, dip, level;
        /** queda medida no contorno inteiro (sem cortar o começo vozeado): ajuda no 4º tom depois de m, n, l, w… */
        public double fallFull, imxFull;
        public int n;
    }

    public record SylResult(Pinyin.Syl syl, int expected, boolean ok, String why) {}

    public record Result(boolean ok, List<SylResult> syls, double medianHz) {
        public String summary() {
            StringBuilder b = new StringBuilder();
            for (SylResult s : syls) {
                if (b.length() > 0) b.append("  ");
                b.append(Pinyin.mark(s.syl().base(), s.syl().tone())).append(s.ok() ? " ✓" : " ✗ " + s.why());
            }
            return b.toString();
        }
    }

    static double st(double hz, double ref) { return 12 * Math.log(hz / ref) / Math.log(2); }

    /** corrige pedaços uma oitava acima ou abaixo do pedaço principal (erro típico de detecção de altura) */
    static double[] fixOctaves(double[] c) {
        if (c.length < 2) return c;
        List<int[]> runs = new ArrayList<>();
        int s = 0;
        for (int i = 1; i <= c.length; i++) if (i == c.length || Math.abs(c[i] - c[i - 1]) > 4.0) { runs.add(new int[]{s, i}); s = i; }
        if (runs.size() < 2) return c;
        int[] main = runs.get(0);
        for (int[] r : runs) if (r[1] - r[0] > main[1] - main[0]) main = r;
        double mm = medianOf(c, main[0], main[1]);
        double[] out = c.clone();
        for (int[] r : runs) {
            if (r == main) continue;
            double d = medianOf(c, r[0], r[1]) - mm;
            double sh = Math.abs(d - 12) < 3 ? -12 : Math.abs(d + 12) < 3 ? 12 : 0;
            for (int i = r[0]; i < r[1]; i++) out[i] += sh;
        }
        return out;
    }

    static double medianOf(double[] a, int s, int e) { double[] b = Arrays.copyOfRange(a, s, e); Arrays.sort(b); return b[b.length / 2]; }

    /** divide onde há salto > 4 semitons entre quadros vizinhos e fica com o maior pedaço */
    static double[] longestRun(double[] c) {
        if (c.length == 0) return c;
        int bestS = 0, bestL = 1, s = 0;
        for (int i = 1; i <= c.length; i++) {
            if (i == c.length || Math.abs(c[i] - c[i - 1]) > 4.0) {
                if (i - s > bestL) { bestL = i - s; bestS = s; }
                s = i;
            }
        }
        return Arrays.copyOfRange(c, bestS, bestS + bestL);
    }

    static double[] smooth(double[] c) {
        if (c.length < 5) return c;
        double[] out = new double[c.length], w = new double[5];
        for (int i = 0; i < c.length; i++) {
            for (int k = -2; k <= 2; k++) w[k + 2] = c[Math.min(c.length - 1, Math.max(0, i + k))];
            double[] s = w.clone(); Arrays.sort(s); out[i] = s[2];
        }
        return out;
    }

    public static Feats feats(double[] segHz, double ref, String base) {
        if (segHz.length < 5) return null;
        Feats F0 = new Feats();
        double[] c = new double[segHz.length];
        for (int i = 0; i < c.length; i++) c[i] = st(segHz[i], ref);
        c = longestRun(fixOctaves(c));
        if (c.length < 5) return null;
        c = smooth(c);
        {
            int nn = c.length, qq = Math.max(2, nn / 5), im = 0;
            for (int i = 0; i < Math.max(2, (int) (nn * 0.6)); i++) if (c[i] > c[im]) im = i;
            F0.fallFull = c[im] - mean(c, nn - qq, nn); F0.imxFull = (double) im / (nn - 1);
        }
        double a = Pinyin.sonorantStart(base) ? 0.25 : 0.1;
        double[] core = Arrays.copyOfRange(c, (int) (c.length * a), c.length);
        if (core.length < 4) core = c;
        int n = core.length, q = Math.max(2, n / 5);
        Feats F = F0;
        F.n = n;
        F.head = mean(core, 0, q); F.tail = mean(core, n - q, n);
        int imn = 0, imx = 0;
        for (int i = 1; i < n; i++) { if (core[i] < core[imn]) imn = i; if (core[i] > core[imx]) imx = i; }
        F.mn = core[imn]; F.mx = core[imx]; F.rng = F.mx - F.mn;
        F.imn = (double) imn / (n - 1); F.imx = (double) imx / (n - 1);
        double minEarly = Double.MAX_VALUE, maxEarly = -Double.MAX_VALUE;
        for (int i = 0; i < Math.max(2, (int) (n * 0.7)); i++) minEarly = Math.min(minEarly, core[i]);
        for (int i = 0; i < Math.max(2, (int) (n * 0.6)); i++) maxEarly = Math.max(maxEarly, core[i]);
        F.rise = F.tail - minEarly; F.fall = maxEarly - F.tail;
        F.dip = Math.min(F.head, F.tail) - F.mn;
        F.level = mean(core, 0, n);
        return F;
    }

    static double mean(double[] a, int s, int e) { double t = 0; for (int i = s; i < e; i++) t += a[i]; return t / Math.max(1, e - s); }

    /** a sílaba é compatível com o tom e? final = última sílaba da palavra; tol > 1 deixa mais leniente */
    public static String fits(int e, Feats F, boolean fin, double tol) {
        if (e == 0 || e == 5 || F == null) return null;
        double m = tol * (fin ? 1.0 : 1.35);
        double lv = F.level, rise = F.rise, fall = F.fall, dip = F.dip, imn = F.imn, imx = F.imx, mn = F.mn, rng = F.rng, head = F.head, tail = F.tail;
        switch (e) {
            case 1 -> {
                if (rise > 3.0 * m && imx > 0.6) return "subiu (como 2º tom)";
                if (fall > 4.0 * m && imn > 0.6) return "caiu (como 4º tom)";
                if (dip > 3.0 * m) return "afundou (como 3º tom)";
                if (lv < -3.5 * tol) return "ficou baixo";
                return null;
            }
            case 2 -> {
                if (mn < -5.5 * tol && imn > 0.25 && imn < 0.8 && dip > 1.5 * m) return "afundou (como 3º tom)";
                if (tail < -1.5 * tol && lv < -2.5 * tol) return "ficou baixo (como 3º tom)";
                if (rise >= 1.5 / m && imx > 0.5) return null;
                if (fall > 2.5 * m && imx < 0.4) return "caiu (como 4º tom)";
                return fin ? "não subiu" : null;
            }
            case 3 -> {
                if (mn <= -5.0 / tol && imn > 0.15) return null;  // um ponto bem baixo (e não só no começo) define o 3º tom
                if (rise >= 2.5 * m && imx > 0.6 && tail >= -0.5 && dip < 1.5) return "subiu (como 2º tom)";
                if (lv <= -2.5 / tol || mn <= -4.0 / tol || (dip >= 1.5 / m && imn >= 0.15 && imn <= 0.85)) return null;
                if (fall > 2.5 * m && imx < 0.4 && head > 1.0) return "caiu do alto (como 4º tom)";
                if (rng < 2.5 && lv > 0.5) return "ficou alto e reto (como 1º tom)";
                return fin ? "não ficou baixo" : null;
            }
            case 4 -> {
                if (fall >= 1.5 / m && imx < 0.6) return null;
                if (F.fallFull >= 2.0 / m && F.imxFull < 0.6) return null;
                if (rise > 1.5 * m && imx > 0.6) return "subiu (como 2º tom)";
                if (head - tail >= 1.0 / m || fall >= 1.0 / m) return null;
                return "não caiu";
            }
            default -> { return null; }
        }
    }

    /** limites das n sílabas (em quadros): divide igual e puxa cada corte para o ponto mais fraco perto dali */
    public static int[][] smartCuts(double[] f, double[] rms, int n, int a, int b) {
        int L = b - a;
        int[] cuts = new int[n + 1];
        cuts[0] = a; cuts[n] = b;
        for (int k = 1; k < n; k++) {
            int c0 = (int) (a + (double) L * k / n), w = Math.max(2, (int) ((double) L / n * 0.3));
            int lo = Math.max(cuts[k - 1] + 3, c0 - w), hi = Math.min(b - 3, c0 + w);
            if (hi <= lo) { cuts[k] = c0; continue; }
            int best = lo; double bv = Double.MAX_VALUE;
            for (int i = lo; i < hi; i++) { double v = rms[i] * (f[i] <= 0 ? 0.2 : 1.0); if (v < bv) { bv = v; best = i; } }
            cuts[k] = best;
        }
        int[][] out = new int[n][2];
        for (int k = 0; k < n; k++) { out[k][0] = cuts[k]; out[k][1] = Math.max(cuts[k] + 1, cuts[k + 1]); }
        return out;
    }

    /**
     * Confere os tons de uma palavra falada.
     * @param pcm áudio 16 kHz; @param fromSec/toSec trecho da palavra (do reconhecedor; use -1 para usar todo o trecho com voz)
     * @param refHz referência da voz do jogador (mediana de falas anteriores; 0 = usa a desta fala)
     */
    public static Result check(float[] pcm, double fromSec, double toSec, String hanzi, String py, double refHz, double tol) {
        return check(pcm, fromSec, toSec, null, hanzi, py, refHz, tol);
    }

    /** @param sylSecs início e fim de cada sílaba em segundos (do reconhecedor, quando ele separa caractere por caractere); null = acha sozinho */
    public static Result check(float[] pcm, double fromSec, double toSec, double[][] sylSecs, String hanzi, String py, double refHz, double tol) {
        Pitch.Track tr = Pitch.yin(pcm);
        double[] f = Pitch.clean(tr);
        List<Pinyin.Syl> syls = Pinyin.parse(py);
        int[] exp = Pinyin.expected(hanzi, syls);
        int n = syls.size();
        double med = Pitch.median(f);
        List<SylResult> res = new ArrayList<>();
        if (n == 0 || med <= 0) {
            for (int i = 0; i < n; i++) res.add(new SylResult(syls.get(i), exp[i], true, "sem voz para medir"));
            return new Result(true, res, med);
        }
        double ref = refHz > 0 ? refHz : med;
        int a = -1, b = -1;
        int fa = fromSec >= 0 ? (int) Math.floor(fromSec * 100) : 0, fb = toSec > 0 ? (int) Math.ceil(toSec * 100) : f.length;
        fa = Math.max(0, Math.min(f.length, fa - 5)); fb = Math.max(fa, Math.min(f.length, fb + 5));
        for (int i = fa; i < fb; i++) if (f[i] > 0) { if (a < 0) a = i; b = i + 1; }
        if (a < 0) {
            for (int i = 0; i < n; i++) res.add(new SylResult(syls.get(i), exp[i], true, "sem voz para medir"));
            return new Result(true, res, med);
        }
        int[][] cuts;
        if (sylSecs != null && sylSecs.length == n) {
            cuts = new int[n][2];
            for (int k = 0; k < n; k++) {
                cuts[k][0] = Math.max(0, Math.min(f.length - 1, (int) Math.floor(sylSecs[k][0] * 100)));
                cuts[k][1] = Math.max(cuts[k][0] + 1, Math.min(f.length, (int) Math.ceil(sylSecs[k][1] * 100)));
            }
        } else cuts = smartCuts(f, tr.rms, n, a, b);
        boolean all = true;
        for (int k = 0; k < n; k++) {
            boolean fin = k == n - 1;
            int s = cuts[k][0], e = cuts[k][1], L = e - s;
            int s2 = s + (int) (L * 0.12), e2 = e - (fin ? 0 : (int) (L * 0.15));
            int cnt = 0;
            for (int i = s2; i < e2; i++) if (f[i] > 0) cnt++;
            double[] seg = new double[cnt]; cnt = 0;
            for (int i = s2; i < e2; i++) if (f[i] > 0) seg[cnt++] = f[i];
            Feats F = feats(seg, ref, syls.get(k).base());
            String why = fits(exp[k], F, fin, tol);
            res.add(new SylResult(syls.get(k), exp[k], why == null, why == null ? "" : why));
            if (why != null) all = false;
        }
        return new Result(all, res, med);
    }
}
