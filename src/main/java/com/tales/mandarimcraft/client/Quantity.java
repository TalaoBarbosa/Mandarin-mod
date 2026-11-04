package com.tales.mandarimcraft.client;

import com.google.gson.JsonArray;
import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.core.Counting;
import com.tales.mandarimcraft.core.Pinyin;
import com.tales.mandarimcraft.core.ToneCheck;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A gramática do reconhecedor para um "quantos você tem?": todo número de 1 a 64 com cada classificador (os certos e
 * o 个 genérico), com ou sem o nome. Os erros típicos também estão nela (二块 em vez de 两块, 个 no lugar de 根), para
 * o mod ouvir o que a pessoa disse e dar a dica certa em vez de "não entendi".
 */
final class Quantity {
    /** o que uma frase da gramática quer dizer */
    record Said(int n, Vocab.Alt mw, boolean mwOk, boolean er, Vocab.Alt noun, String joined) {}

    final Vocab.Word w;
    final String grammar;
    private final List<Said> longestFirst;

    private Quantity(Vocab.Word w, String grammar, List<Said> longestFirst) { this.w = w; this.grammar = grammar; this.longestFirst = longestFirst; }

    static Quantity of(Vocab.Word w) {
        List<Vocab.Alt> mws = new ArrayList<>(w.mw);
        if (mws.stream().noneMatch(m -> m.zh().equals("个"))) mws.add(new Vocab.Alt("个", "ge4", "个"));
        List<Vocab.Alt> nouns = new ArrayList<>(); nouns.add(null); nouns.addAll(w.forms());
        Set<String> phrases = new LinkedHashSet<>();
        List<Said> said = new ArrayList<>();
        for (int k = 1; k <= 64; k++) {
            for (boolean er : k == 2 ? new boolean[]{false, true} : new boolean[]{false}) {
                String num = er ? "二" : Counting.zhCount(k);
                String numTokens = String.join(" ", num.split(""));   // um caractere por palavra: todos estão no modelo
                for (Vocab.Alt mw : mws) for (Vocab.Alt noun : nouns) {
                    String p = numTokens + " " + mw.zh() + (noun == null ? "" : " " + noun.rec());
                    if (phrases.add(p)) said.add(new Said(k, mw, w.mw.contains(mw), er, noun, p.replace(" ", "")));
                }
            }
        }
        JsonArray a = new JsonArray();
        phrases.forEach(a::add);
        said.sort(Comparator.comparingInt((Said s) -> s.joined().length()).reversed());
        return new Quantity(w, a.toString(), said);
    }

    /** a frase mais longa que aparece no que ele ouviu ("六十四根" ganha de "四根"); null se nenhuma */
    Said find(String heard) {
        for (Said s : longestFirst) if (heard.contains(s.joined())) return s;
        return null;
    }

    /**
     * "Quantos você tem?": reconhece com a gramática só desta pergunta e confere o número, o classificador
     * (dica se usou 个 no lugar do certo, ou 二 no lugar de 两) e os tons da frase que ele disse, com as regras das quantidades.
     */
    Desafio.Result analyze(float[] audio, boolean spoke, int n, double ref, double tol) {
        try {
            Speech.Heard h = Speech.recognize(audio, grammar);
            String heard = h == null ? "" : h.joined();
            if (!spoke && heard.isEmpty()) return new Desafio.Result(false, false, false, "", null, null, null);
            Said s = find(heard);
            if (s == null || s.n() != n) return new Desafio.Result(true, false, false, heard, null, w, null);
            Vocab.Alt mw0 = w.mw.get(0);
            if (s.er()) return new Desafio.Result(true, false, false, heard, null, w, null, "Antes do classificador o 2 é 两 (liǎng), não 二.");
            if (!s.mwOk()) return new Desafio.Result(true, false, false, heard, null, w, null,
                    "Para " + w.zh + " o classificador é " + mw0.zh() + " (" + Pinyin.pretty(mw0.py()) + "), não " + s.mw().zh() + ".");
            String zh = Counting.zhCount(n) + s.mw().zh() + (s.noun() == null ? "" : s.noun().zh());
            String py = Counting.pyCount(n) + " " + s.mw().py() + (s.noun() == null ? "" : " " + s.noun().py());
            List<Pinyin.Syl> syls = Pinyin.parse(py);
            int[] exp = Counting.expected(zh, syls, Counting.syllables(n));
            double from = -1, to = -1; double[][] secs = null;
            int[] sp = h == null ? null : Speech.span(h.pieces(), s.joined());
            if (sp != null) {
                List<Speech.Piece> ps = h.pieces().subList(sp[0], sp[1]);
                from = ps.get(0).start(); to = ps.get(ps.size() - 1).end();
                secs = Speech.perSyllable(ps, syls.size());
            }
            ToneCheck.Result tr = ToneCheck.check(audio, from, to, secs, syls, exp, ref, tol);
            return new Desafio.Result(true, tr.ok(), true, heard, null, w, tr);
        } catch (Throwable t) {
            MandarimCraft.LOG.error("Erro analisando a quantidade", t);
            return new Desafio.Result(true, false, false, "", null, null, null);
        }
    }
}
