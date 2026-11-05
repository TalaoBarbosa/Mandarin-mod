package com.tales.mandarimcraft.core;

import java.util.List;

/**
 * Quantidades em chinês para os desafios de "quantos você tem": número + classificador (六十四根). Antes de um
 * classificador o 2 vira 两; o 一 dos números compostos (十一, 二十一) não muda de tom. Só de 1 a 99 (uma pilha tem até 64).
 */
public final class Counting {
    private Counting() {}

    private static final String[] ZH = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] PY = {"", "yi1", "er4", "san1", "si4", "wu3", "liu4", "qi1", "ba1", "jiu3"};

    /** 1..99 em 汉字: 三, 十一, 二十, 六十四 */
    public static String zh(int n) {
        check(n);
        if (n < 10) return ZH[n];
        int tens = n / 10, ones = n % 10;
        return (tens == 1 ? "" : ZH[tens]) + "十" + ZH[ones];
    }

    /** 1..99 em pinyin numerado, sílabas grudadas: "liu4shi2si4" */
    public static String py(int n) {
        check(n);
        if (n < 10) return PY[n];
        int tens = n / 10, ones = n % 10;
        return (tens == 1 ? "" : PY[tens]) + "shi2" + PY[ones];
    }

    /** o número como se diz antes de um classificador: 2 vira 两 (两块, não 二块) */
    public static String zhCount(int n) { return n == 2 ? "两" : zh(n); }

    public static String pyCount(int n) { return n == 2 ? "liang3" : py(n); }

    /** quantas sílabas tem o número (两 = 1, 六十四 = 3) */
    public static int syllables(int n) { return Pinyin.parse(pyCount(n)).size(); }

    /**
     * Tons esperados de "número + classificador (+ substantivo)": as regras de Pinyin.expected (3+3, 一 e 不), só que o
     * 一 dentro do número (十一, 二十一: n ≥ 2 aqui, então todo 一 é de número composto) fica no 1º tom, 七/八 antes do
     * 4º tom ficam livres (tem quem fale qí/bá), e o 十 entre dois dígitos (六十四) também: na fala corrida ele sai
     * fraco, quase neutro (liùshisì). No começo (十二) e no fim (六十) ele continua conferido.
     */
    public static int[] expected(String zhPhrase, List<Pinyin.Syl> syls, int numberSyllables) {
        int[] e = Pinyin.expected(zhPhrase, syls);
        String h = zhPhrase.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i < numberSyllables && i < e.length && i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '一') e[i] = 1;
            else if ((c == '七' || c == '八') && i + 1 < syls.size() && syls.get(i + 1).tone() == 4) e[i] = 0;
            else if (c == '十' && i > 0 && i + 1 < numberSyllables) e[i] = 0;
        }
        return e;
    }

    private static void check(int n) {
        if (n < 1 || n > 99) throw new IllegalArgumentException("fora de 1..99: " + n);
    }
}
