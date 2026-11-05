package com.tales.mandarimcraft.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pinyin com números ("mu4tou5", "ma3'an1", "zai4 lai2"): sílabas, tons esperados (com as mudanças de tom) e escrita com acentos. */
public final class Pinyin {
    private Pinyin() {}

    public record Syl(String base, int tone) {}

    private static final Pattern SYL = Pattern.compile("([a-zA-ZüÜv:]+?)([1-5])");

    public static List<Syl> parse(String py) {
        List<Syl> out = new ArrayList<>();
        Matcher m = SYL.matcher(py.replace("'", " ").replace("u:", "ü").replace("v", "ü"));
        while (m.find()) out.add(new Syl(m.group(1).toLowerCase(), Integer.parseInt(m.group(2))));
        return out;
    }

    /** sílabas sem tom, para comparar palavras de mesmo som (剑 = 箭) */
    public static String toneless(String py) {
        StringBuilder b = new StringBuilder();
        for (Syl s : parse(py)) b.append(s.base()).append(' ');
        return b.toString().trim();
    }

    /**
     * Tom que deve sair em cada sílaba: 3+3 vira 2+3 (aqui o primeiro fica livre, 0 = não confere),
     * 一 e 不 mudam conforme o tom seguinte, 5 (neutro) não é conferido.
     */
    public static int[] expected(String hanzi, List<Syl> s) {
        int n = s.size();
        int[] t = new int[n];
        for (int i = 0; i < n; i++) t[i] = s.get(i).tone();
        String h = hanzi.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i < n - 1; i++) {
            char c = i < h.length() ? h.charAt(i) : ' ';
            int next = s.get(i + 1).tone();
            if (t[i] == 3 && next == 3) t[i] = 0;
            else if (c == '不' && t[i] == 4 && next == 4) t[i] = 2;
            else if (c == '一' && t[i] == 1) t[i] = next == 4 ? 2 : 4;
        }
        return t;
    }

    /** a sílaba começa com som vozeado (m, n, l, r, w, y ou vogal): a voz entra antes do tom, então corta mais do começo */
    public static boolean sonorantStart(String base) {
        return !base.isEmpty() && "mnlrwyaeo".indexOf(base.charAt(0)) >= 0;
    }

    private static final String MARKS_A = "āáǎà", MARKS_E = "ēéěè", MARKS_I = "īíǐì", MARKS_O = "ōóǒò", MARKS_U = "ūúǔù", MARKS_V = "ǖǘǚǜ";

    public static String mark(String base, int tone) {
        String b = base.replace("v", "ü").replace("u:", "ü");
        if (tone < 1 || tone > 4) return b;
        String low = b.toLowerCase();
        int idx = low.indexOf('a');
        if (idx < 0) idx = low.indexOf('e');
        if (idx < 0 && low.contains("ou")) idx = low.indexOf('o');
        if (idx < 0) for (int k = low.length() - 1; k >= 0; k--) if ("aeiouü".indexOf(low.charAt(k)) >= 0) { idx = k; break; }
        if (idx < 0) return b;
        String set = switch (low.charAt(idx)) {
            case 'a' -> MARKS_A; case 'e' -> MARKS_E; case 'i' -> MARKS_I; case 'o' -> MARKS_O; case 'u' -> MARKS_U; default -> MARKS_V;
        };
        char mk = set.charAt(tone - 1);
        if (Character.isUpperCase(b.charAt(idx))) mk = Character.toUpperCase(mk);
        return b.substring(0, idx) + mk + b.substring(idx + 1);
    }

    /** "mu4tou5" -> "mùtou"; mantém espaços e apóstrofo do original */
    public static String pretty(String py) { return write(py, true); }

    /** "lv4bao3shi2" -> "lübaoshi": como pretty(), sem as marcas de tom (o ü continua ü) */
    public static String noTones(String py) { return write(py, false); }

    private static String write(String py, boolean tones) {
        String s = py.replace("u:", "ü").replace("v", "ü");   // os índices do matcher são deste texto, não do original
        StringBuilder out = new StringBuilder();
        Matcher m = SYL.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(mark(m.group(1), tones ? Integer.parseInt(m.group(2)) : 0));
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    public static final String[] TONE_NAME = {"livre", "1º tom", "2º tom", "3º tom", "4º tom", "neutro"};
}
