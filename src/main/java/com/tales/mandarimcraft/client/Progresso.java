package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.net.Payloads;

import java.util.Map;

/** O que o jogador já sabe neste mundo, como o servidor mandou por último (o dicionário mostra). Só na thread do jogo. */
public final class Progresso {
    private Progresso() {}

    /** o servidor tem o mod e já mandou o progresso */
    static boolean known;
    /** o mod está ligado neste mundo (/mandarim comecar) */
    static boolean active;
    /** palavra aprendida -> quantas revisões seguidas acertou */
    static Map<String, Integer> learned = Map.of();
    /** palavra -> quantas vezes errou */
    static Map<String, Integer> fails = Map.of();

    static void set(Payloads.Progress p) { known = true; active = p.active(); learned = p.learned(); fails = p.fails(); }

    static void clear() { known = false; active = false; learned = Map.of(); fails = Map.of(); }

    static boolean isLearned(String key) { return learned.containsKey(key); }

    static boolean isMastered(String key) { return learned.getOrDefault(key, 0) >= MandarimCraft.MASTERED_STAGE; }
}
