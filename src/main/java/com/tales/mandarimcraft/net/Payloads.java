package com.tales.mandarimcraft.net;

import com.tales.mandarimcraft.MandarimCraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Mensagens entre o servidor (que vê o inventário) e o cliente (que ouve o microfone). */
public final class Payloads {
    private Payloads() {}

    /** tipos de desafio (Challenge.kind) */
    public static final int NEW = 0, COUNT = 1, REVIEW = 2;

    /**
     * servidor -> cliente: diga esta palavra.
     * kind: NEW = item novo no inventário; COUNT = "quantos você tem" (número + classificador); REVIEW = palavra que
     * ele já aprendeu, de novo, com menos dica.
     * level = quanta dica tirar (dica automática): 0 = tudo, com o pinyin e os tons; 1 = menos; 2 = nada. Sobe quando acerta,
     * desce quando erra: errar nunca deixa mais difícil. Palavra nova é sempre 0 (ainda não acertou nenhuma vez).
     * count = quantos itens tem a pilha do desafio (na quantidade é a resposta; na revisão decide o que se perde ao errar).
     */
    public record Challenge(String word, String item, int seconds, int level, int count, int kind) implements CustomPayload {
        public static final CustomPayload.Id<Challenge> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "challenge"));
        public static final PacketCodec<RegistryByteBuf, Challenge> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Challenge::word,
                PacketCodecs.STRING, Challenge::item,
                PacketCodecs.VAR_INT, Challenge::seconds,
                PacketCodecs.VAR_INT, Challenge::level,
                PacketCodecs.VAR_INT, Challenge::count,
                PacketCodecs.VAR_INT, Challenge::kind,
                Challenge::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** cliente -> servidor: acertou ou errou (heard = o que o reconhecedor ouviu, para o histórico) */
    public record Answer(String word, boolean ok, String heard) implements CustomPayload {
        public static final CustomPayload.Id<Answer> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "answer"));
        public static final PacketCodec<RegistryByteBuf, Answer> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Answer::word,
                PacketCodecs.BOOL, Answer::ok,
                PacketCodecs.STRING, Answer::heard,
                Answer::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * cliente -> servidor, a cada 2 s enquanto o desafio desta palavra está na tela ou esperando um momento calmo:
     * "ainda estou aqui". Sem isso o servidor não sabe se o cliente está só esperando ou se travou.
     */
    public record Alive(String word) implements CustomPayload {
        public static final CustomPayload.Id<Alive> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "alive"));
        public static final PacketCodec<RegistryByteBuf, Alive> CODEC = PacketCodec.tuple(PacketCodecs.STRING, Alive::word, Alive::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** servidor -> cliente: o desafio foi cancelado (pausa, modo criativo, item sumiu…) */
    public record Cancel(String word) implements CustomPayload {
        public static final CustomPayload.Id<Cancel> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "cancel"));
        public static final PacketCodec<RegistryByteBuf, Cancel> CODEC = PacketCodec.tuple(PacketCodecs.STRING, Cancel::word, Cancel::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * servidor -> cliente: o que o jogador já sabe neste mundo, para o dicionário.
     * learned: palavra -> quantas revisões seguidas já acertou (0 = aprendeu agora); fails: palavra -> quantas vezes errou.
     */
    public record Progress(boolean active, Map<String, Integer> learned, Map<String, Integer> fails) implements CustomPayload {
        public static final CustomPayload.Id<Progress> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "progress"));
        private static final PacketCodec<io.netty.buffer.ByteBuf, Map<String, Integer>> MAP = PacketCodecs.map(HashMap::new, PacketCodecs.STRING, PacketCodecs.VAR_INT);
        public static final PacketCodec<RegistryByteBuf, Progress> CODEC = PacketCodec.tuple(
                PacketCodecs.BOOL, Progress::active,
                MAP, Progress::learned,
                MAP, Progress::fails,
                Progress::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }
}
