package com.tales.mandarimcraft.net;

import com.tales.mandarimcraft.MandarimCraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Mensagens entre o servidor (que vê o inventário) e o cliente (que ouve o microfone). */
public final class Payloads {
    private Payloads() {}

    /** servidor -> cliente: diga esta palavra (item novo no inventário). seen = quantas vezes já errou ela neste mundo (decide a dica) */
    public record Challenge(String word, String item, int seconds, int seen) implements CustomPayload {
        public static final CustomPayload.Id<Challenge> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "challenge"));
        public static final PacketCodec<RegistryByteBuf, Challenge> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Challenge::word,
                PacketCodecs.STRING, Challenge::item,
                PacketCodecs.VAR_INT, Challenge::seconds,
                PacketCodecs.VAR_INT, Challenge::seen,
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

    /** servidor -> cliente: o desafio foi cancelado (pausa, modo criativo, item sumiu…) */
    public record Cancel(String word) implements CustomPayload {
        public static final CustomPayload.Id<Cancel> ID = new CustomPayload.Id<>(Identifier.of(MandarimCraft.MOD_ID, "cancel"));
        public static final PacketCodec<RegistryByteBuf, Cancel> CODEC = PacketCodec.tuple(PacketCodecs.STRING, Cancel::word, Cancel::new);
        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }
}
