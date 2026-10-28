package com.tales.mandarimcraft.core;

import java.util.List;

/** Compara o que o reconhecedor ouviu com a palavra pedida: sílabas certas? qual forma (principal ou apelido)? */
public final class Answer {
    private Answer() {}

    public record Form(String zh, String py) {}

    public record Match(boolean syllablesOk, Form form, String heard) {}

    /**
     * @param heard texto ouvido, sem espaços
     * @param target formas aceitas da palavra pedida
     * @param all todas as formas de todas as palavras (para achar o que ele disse, mesmo que seja outra palavra)
     */
    public static Match match(String heard, List<Form> target, List<Form> all) {
        String h = heard == null ? "" : heard.replace(" ", "").replace("[unk]", "");
        if (h.isEmpty()) return new Match(false, null, "");
        // 1) disse exatamente uma forma da palavra pedida
        for (Form f : target) if (h.equals(f.zh())) return new Match(true, f, h);
        // 2) disse outra palavra com as mesmas sílabas (剑/箭, 铜/桶…): sílabas certas, o tom decide
        for (Form o : all) if (h.equals(o.zh())) {
            String sy = Pinyin.toneless(o.py());
            for (Form f : target) if (Pinyin.toneless(f.py()).equals(sy)) return new Match(true, f, h);
            return new Match(false, null, h);
        }
        // 3) a palavra pedida aparece no meio do que ouviu (repetiu, falou algo antes)
        for (Form f : target) if (h.contains(f.zh())) return new Match(true, f, h);
        return new Match(false, null, h);
    }
}
