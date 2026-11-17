package com.tales.mandarimcraft.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Confere se cada sílaba saiu no tom esperado, pela curva da voz.
 *
 * Não depende de quão agudo ou grave a pessoa falou (nem de calibração): no jogo a mesma pessoa fala uma palavra
 * gritando e a seguinte baixinho, e a altura média muda mais de uma oitava de uma tentativa para a outra. O que vale é
 *  - a FORMA da curva de cada sílaba (reta, sobe, desce e sobe, cai), comparada com moldes medidos em gravações de
 *    nativos, cada molde colocado na altura que melhor encaixa;
 *  - a ALTURA de cada sílaba em relação às outras da mesma palavra: o "registro" da fala sai das sílabas cuja forma
 *    bate com o tom pedido (as âncoras), e as regras de altura são aplicadas em volta dele.
 * Palavra de uma sílaba só tem forma: vale o molde que encaixa melhor.
 *
 * Medido nas 230 gravações de nativos do mod (falas certas que passam / trocas de tom que são pegas):
 *   uma sílaba 98% / 96%; sílaba + neutro 96% / 66%; duas ou mais sílabas com tom 84% / 80%; todas 90% / 80%.
 *   (Das 115 palavras, 49 são montadas com a gravação de cada caractere, e nelas a altura de uma sílaba em relação
 *   à outra é artificial: das 66 gravadas inteiras, 94% passam.)
 * As regras antigas, que comparavam com a altura da calibração, davam 96% / 78% com a calibração perfeita, mas
 * 79–84% / 72–75% com a voz 4 semitons fora dela, que é o caso de quem joga.
 */
public final class ToneCheck {
    private ToneCheck() {}

    public static final class Feats {
        public double head, tail, mn, mx, rng, imn, imx, rise, fall, dip, level;
        /** queda medida no contorno inteiro (sem cortar o começo vozeado): ajuda no 4º tom depois de m, n, l, w… */
        public double fallFull, imxFull;
        public int n;
        /** a curva alisada da sílaba inteira, em semitons em relação à referência da conta (para desenhar) */
        public double[] curve;
    }

    /** NONE = não conferido (neutro, livre) ou sem voz para medir; BAD = errado; ROUGH = aceito, mas não saiu limpo; GOOD = certo */
    public enum Grade { NONE, BAD, ROUGH, GOOD }

    /**
     * Uma sílaba.
     * curve = a voz, em semitons acima ou abaixo do registro da palavra (vazia se não deu para medir);
     * shape = quanto a curva foge do molde do tom pedido (desvio em semitons, com o molde na melhor altura);
     * rival / rivalShape = o outro tom cujo molde encaixa melhor, e quanto foge dele;
     * height = quanto a sílaba ficou acima (+) ou abaixo (−) de onde devia, em relação às outras sílabas.
     */
    public record SylResult(Pinyin.Syl syl, int expected, Grade grade, String why, double[] curve, double shape, int rival, double rivalShape, double height) {
        public boolean ok() { return grade != Grade.BAD; }
        /** conferida (tom 1 a 4) mas sem voz bastante para medir */
        public boolean unmeasured() { return expected >= 1 && expected <= 4 && grade == Grade.NONE; }
        public boolean checked() { return expected >= 1 && expected <= 4; }
    }

    /**
     * voiced = quantos quadros (de 10 ms) com voz há no trecho da palavra: ruído que o reconhecedor tomou por palavra não tem nenhum;
     * checked / measured = quantas sílabas têm tom para conferir, e em quantas deu para medir a voz.
     */
    public record Result(boolean ok, List<SylResult> syls, double medianHz, int voiced, int checked, int measured) {
        public String summary() {
            StringBuilder b = new StringBuilder();
            for (SylResult s : syls) {
                if (b.length() > 0) b.append("  ");
                b.append(Pinyin.mark(s.syl().base(), s.syl().tone()));
                b.append(switch (s.grade()) {
                    case BAD -> " ✗ " + s.why();
                    case ROUGH -> " ✓ raspando (" + s.why() + ")";
                    case GOOD -> " ✓";
                    case NONE -> s.unmeasured() ? " ? sem medida" : "";
                });
            }
            return b.toString();
        }

        /** só o que não saiu limpo, para a tela (o desenho já mostra o resto): "bǎn ✗ subiu (como 2º tom)" */
        public String problems() {
            StringBuilder b = new StringBuilder();
            for (SylResult s : syls) {
                if (s.grade() == Grade.GOOD || !s.checked()) continue;
                if (b.length() > 0) b.append("  ");
                b.append(Pinyin.mark(s.syl().base(), s.syl().tone()));
                b.append(switch (s.grade()) { case BAD -> " ✗ " + s.why(); case ROUGH -> " passou raspando: " + s.why(); default -> " ? não deu para medir"; });
            }
            return b.toString();
        }

        /** os números de cada sílaba conferida, para o log */
        public String detail() {
            StringBuilder b = new StringBuilder();
            for (SylResult s : syls) {
                if (!s.checked()) continue;
                if (b.length() > 0) b.append("; ");
                b.append(Pinyin.mark(s.syl().base(), s.syl().tone()));
                if (s.unmeasured()) { b.append(" sem medida"); continue; }
                b.append(String.format(java.util.Locale.ROOT, " forma %.1f (tom %d: %.1f) altura %+.1f, %d pontos, de %+.1f a %+.1f",
                        s.shape(), s.rival(), s.rivalShape(), s.height(), s.curve().length, s.curve().length > 0 ? s.curve()[0] : 0, s.curve().length > 0 ? s.curve()[s.curve().length - 1] : 0));
            }
            return b.toString();
        }
    }

    static double st(double hz, double ref) { return 12 * Math.log(hz / ref) / Math.log(2); }

    // ------------------------------------------------------------ moldes

    /** pontos de cada molde */
    static final int P = 9;
    /**
     * A curva média de cada tom nas gravações de nativos, em semitons em relação ao registro da palavra:
     * MOLD[tom][posição][ponto], posição 0 = palavra de uma sílaba, 1 = última sílaba, 2 = sílaba do meio.
     * O 2º tom começa baixo e só sobe na segunda metade; o 3º desce e sobe; o 4º do fim da palavra cai menos.
     */
    static final double[][][] MOLD = {
            {},
            {{4.3, 4.1, 4.0, 4.0, 4.0, 4.0, 3.8, 3.7, 3.9}, {3.9, 4.0, 4.0, 4.0, 4.0, 3.9, 3.9, 4.0, 4.1}, {2.8, 2.6, 2.6, 2.5, 2.5, 2.5, 2.4, 2.2, 2.0}},
            {{-4.0, -4.3, -4.4, -4.1, -3.4, -1.9, 0.4, 2.4, 3.6}, {-2.7, -3.7, -3.9, -3.7, -3.2, -2.0, -0.5, 1.3, 2.6}, {-2.8, -3.0, -2.9, -2.6, -2.0, -1.1, 0.3, 1.9, 3.1}},
            {{-2.5, -3.9, -4.7, -5.4, -5.9, -5.6, -4.2, -1.4, 0.0}, {-2.4, -3.8, -5.0, -5.4, -5.5, -5.0, -3.8, -1.9, -0.8}, {-1.9, -3.0, -3.9, -4.5, -5.0, -4.8, -4.0, -2.8, -2.2}},
            {{4.2, 3.9, 3.4, 2.4, 1.5, 0.2, -0.9, -1.4, -1.9}, {2.4, 2.3, 2.5, 2.4, 2.0, 1.3, 0.3, -0.6, -1.4}, {4.8, 4.3, 3.6, 2.7, 1.3, 0.1, -1.2, -2.1, -2.7}},
    };

    /** posição da sílaba k numa palavra de n: 0 = sozinha, 1 = a última, 2 = no meio */
    public static int pos(int k, int n) { return n == 1 ? 0 : k == n - 1 ? 1 : 2; }

    /**
     * O molde de um tom para desenhar ao lado da voz do jogador, em semitons em relação ao registro da palavra
     * (t de 0 a 1 ao longo da sílaba). 0 (livre) e 5 (neutro) não têm molde: devolve NaN.
     */
    public static double shape(int tone, int pos, double t) {
        if (tone < 1 || tone > 4) return Double.NaN;
        double x = Math.max(0, Math.min(1, t)) * (P - 1);
        int a = Math.min(P - 2, (int) x);
        double[] m = MOLD[tone][pos];
        return m[a] + (m[a + 1] - m[a]) * (x - a);
    }

    static double[] resample(double[] c, int p) {
        double[] o = new double[p];
        for (int i = 0; i < p; i++) {
            double x = i / (double) (p - 1) * (c.length - 1);
            int a = (int) x;
            o[i] = a + 1 < c.length ? c[a] + (c[a + 1] - c[a]) * (x - a) : c[c.length - 1];
        }
        return o;
    }

    /** encaixa o molde do tom na curva (P pontos): devolve {a altura que melhor encaixa, o desvio que sobra} */
    static double[] fit(double[] c, int tone, int pos) {
        double[] m = MOLD[tone][pos];
        double off = 0;
        for (int i = 0; i < P; i++) off += c[i] - m[i];
        off /= P;
        double s = 0;
        for (int i = 0; i < P; i++) { double x = c[i] - m[i] - off; s += x * x; }
        return new double[]{off, Math.sqrt(s / P)};
    }

    static double median(double[] v) {
        double[] a = v.clone();
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    private static String like(int tone) {
        return switch (tone) {
            case 1 -> "ficou reto (como 1º tom)";
            case 2 -> "subiu (como 2º tom)";
            case 3 -> "desceu e subiu (como 3º tom)";
            default -> "caiu (como 4º tom)";
        };
    }

    /** palavra de uma sílaba: o molde pedido pode fugir da curva até isto a mais que o melhor dos outros três */
    static final double SINGLE_MARGIN = 0.6;
    /** com outras sílabas: uma sílaba cuja forma está até isto do melhor molde serve de âncora do registro */
    static final double ANCHOR_MARGIN = 0.8;
    /** com outras sílabas: passou nas regras, mas outro molde encaixa melhor por mais que isto: não vale */
    static final double VETO = 2.0;
    /** as regras de altura (fits) foram ajustadas com a referência um pouco acima do registro */
    static final double UP = 1.0;
    /** passou, mas outro molde encaixa melhor por mais que isto: "raspando" */
    static final double ROUGH_MARGIN = 0.3;
    /**
     * com outras sílabas: a sílaba pode ficar até isto (semitons) acima ou abaixo de onde devia em relação às outras.
     * Entre 3,5 e 5 os mesmos nativos passam; com 3,5 mais trocas são pegas.
     */
    static final double HEIGHT_MAX = 3.5;
    /** passou, mas ficou mais que isto fora da altura: "raspando" */
    static final double ROUGH_HEIGHT = 2.5;

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
        F0.curve = c;
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
    public static String fits(int e, Feats F, boolean fin, double tol) { return fits(e, F, fin, false, tol); }

    /** nextNeutral: a próxima sílaba é neutra (石头, 绳子): aí o 2º tom continua subindo nela e pode começar baixo */
    public static String fits(int e, Feats F, boolean fin, boolean nextNeutral, double tol) {
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
                if (!nextNeutral && tail < -1.5 * tol && lv < -2.5 * tol) return "ficou baixo (como 3º tom)";
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
     * @param tol 1 = rígido; maior deixa mais leniente
     */
    public static Result check(float[] pcm, double fromSec, double toSec, String hanzi, String py, double tol) {
        return check(pcm, fromSec, toSec, null, hanzi, py, tol);
    }

    /** @param sylSecs início e fim de cada sílaba em segundos (do reconhecedor, quando ele separa caractere por caractere); null = acha sozinho */
    public static Result check(float[] pcm, double fromSec, double toSec, double[][] sylSecs, String hanzi, String py, double tol) {
        List<Pinyin.Syl> syls = Pinyin.parse(py);
        return check(pcm, fromSec, toSec, sylSecs, syls, Pinyin.expected(hanzi, syls), tol);
    }

    /** com os tons esperados já calculados (as quantidades têm regras próprias: veja Counting.expected) */
    public static Result check(float[] pcm, double fromSec, double toSec, double[][] sylSecs, List<Pinyin.Syl> syls, int[] exp, double tol) {
        Pitch.Track tr = Pitch.yin(pcm);
        return check(Pitch.clean(tr), tr.rms, fromSec, toSec, sylSecs, syls, exp, tol);
    }

    /** a mesma conta a partir da altura já medida (f = Hz por quadro de 10 ms, 0 onde não há voz) */
    public static Result check(double[] f, double[] rms, double fromSec, double toSec, double[][] sylSecs, List<Pinyin.Syl> syls, int[] exp, double tol) {
        int n = syls.size(), checked = 0;
        for (int e : exp) if (e >= 1 && e <= 4) checked++;
        double med = Pitch.median(f);
        List<SylResult> res = new ArrayList<>();
        int a = -1, b = -1, voiced = 0;
        if (n > 0 && med > 0) {
            int fa = fromSec >= 0 ? (int) Math.floor(fromSec * 100) : 0, fb = toSec > 0 ? (int) Math.ceil(toSec * 100) : f.length;
            fa = Math.max(0, Math.min(f.length, fa - 5)); fb = Math.max(fa, Math.min(f.length, fb + 5));
            for (int i = fa; i < fb; i++) if (f[i] > 0) { if (a < 0) a = i; b = i + 1; }
            // o reconhecedor costuma marcar o fim da palavra cedo, e é no fim que o 2º tom sobe: a palavra vai até onde
            // a voz continua sem parar (até 0,3 s além), e o começo também
            if (a >= 0) {
                while (b < f.length && b < fb + 30 && f[b] > 0) b++;
                while (a > 0 && a > fa - 30 && f[a - 1] > 0) a--;
                for (int i = a; i < b; i++) if (f[i] > 0) voiced++;
            }
        }
        if (a < 0) {   // nenhuma voz no trecho: nada foi conferido (quem chama decide o que fazer com isso)
            for (int i = 0; i < n; i++) res.add(new SylResult(syls.get(i), exp[i], Grade.NONE, "", new double[0], 0, 0, 0, 0));
            return new Result(true, res, med, 0, checked, 0);
        }
        int[][] cuts;
        if (sylSecs != null && sylSecs.length == n) {
            cuts = new int[n][2];
            for (int k = 0; k < n; k++) {
                cuts[k][0] = Math.max(0, Math.min(f.length - 1, (int) Math.floor(sylSecs[k][0] * 100)));
                cuts[k][1] = Math.max(cuts[k][0] + 1, Math.min(f.length, (int) Math.ceil(sylSecs[k][1] * 100)));
            }
            cuts[0][0] = Math.min(cuts[0][0], a);
            cuts[n - 1][1] = Math.max(cuts[n - 1][1], b);
        } else cuts = smartCuts(f, rms, n, a, b);

        // 1) a curva de cada sílaba (em semitons sobre a mediana da fala) e como cada molde encaixa nela
        double[][] seg = new double[n][], curve = new double[n][];
        double[] off = new double[n], mine = new double[n], rivalFit = new double[n];
        int[] rival = new int[n];
        boolean[] has = new boolean[n];
        int measured = 0;
        for (int k = 0; k < n; k++) {
            boolean fin = k == n - 1;
            int s = cuts[k][0], e = cuts[k][1], L = e - s;
            int s2 = s + (int) (L * 0.12), e2 = e - (fin ? 0 : (int) (L * 0.15));
            int cnt = 0;
            for (int i = s2; i < e2; i++) if (f[i] > 0) cnt++;
            seg[k] = new double[cnt]; cnt = 0;
            for (int i = s2; i < e2; i++) if (f[i] > 0) seg[k][cnt++] = f[i];
            Feats F = feats(seg[k], med, syls.get(k).base());
            curve[k] = F == null ? new double[0] : F.curve;
            if (F == null || exp[k] < 1 || exp[k] > 4) continue;
            double[] c = resample(F.curve, P);
            rivalFit[k] = Double.MAX_VALUE;
            for (int t = 1; t <= 4; t++) {
                double[] ft = fit(c, t, pos(k, n));
                if (t == exp[k]) { off[k] = ft[0]; mine[k] = ft[1]; }
                else if (ft[1] < rivalFit[k]) { rivalFit[k] = ft[1]; rival[k] = t; }
            }
            has[k] = true; measured++;
        }

        // 2) o registro da fala: a altura que põe os moldes das âncoras em cima das curvas delas
        int anchors = 0;
        double[] votes = new double[measured];
        for (int k = 0, i = 0; k < n; k++) if (has[k] && mine[k] - rivalFit[k] <= ANCHOR_MARGIN * tol) { votes[i++] = off[k]; anchors++; }
        if (anchors == 0) for (int k = 0, i = 0; k < n; k++) if (has[k]) { votes[i++] = off[k]; anchors++; }   // nenhuma forma bate: vale a de todas
        double reg = anchors == 0 ? 0 : median(Arrays.copyOf(votes, anchors));
        double ref = med * Math.pow(2, (reg + UP) / 12);

        // 3) o veredito de cada sílaba
        boolean all = true;
        for (int k = 0; k < n; k++) {
            double[] c = curve[k].clone();
            for (int i = 0; i < c.length; i++) c[i] -= reg;   // para desenhar: em relação ao registro da palavra
            if (!has[k]) { res.add(new SylResult(syls.get(k), exp[k], Grade.NONE, "", c, 0, 0, 0, 0)); continue; }
            double gap = mine[k] - rivalFit[k];
            String why;
            if (n == 1) why = gap > SINGLE_MARGIN * tol ? like(rival[k]) : null;
            else {
                why = fits(exp[k], feats(seg[k], ref, syls.get(k).base()), k == n - 1, k + 1 < n && syls.get(k + 1).tone() == 5, tol);
                if (why == null && gap > VETO * tol) why = like(rival[k]);
                double up = off[k] - reg;
                if (why == null && measured >= 2 && Math.abs(up) > HEIGHT_MAX * tol) why = up < 0 ? "ficou baixo perto das outras sílabas" : "ficou alto perto das outras sílabas";
            }
            double h = off[k] - reg;
            boolean roughShape = gap > ROUGH_MARGIN, roughHeight = n > 1 && measured >= 2 && Math.abs(h) > ROUGH_HEIGHT;
            Grade g = why != null ? Grade.BAD : roughShape || roughHeight ? Grade.ROUGH : Grade.GOOD;
            if (g == Grade.ROUGH) why = roughShape ? "quase " + like(rival[k]) : h < 0 ? "um pouco baixo perto das outras sílabas" : "um pouco alto perto das outras sílabas";
            res.add(new SylResult(syls.get(k), exp[k], g, why == null ? "" : why, c, mine[k], rival[k], rivalFit[k], off[k] - reg));
            if (g == Grade.BAD) all = false;
        }
        return new Result(all, res, med, voiced, checked, measured);
    }
}
