package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Guarda o áudio das tentativas (só se o jogador ligar nos ajustes), para dar para ouvir de novo e para ajustar o mod
 * com a voz de quem joga. Fica na pasta do jogo, em mandarimcraft-gravacoes; só as últimas KEEP ficam.
 */
final class Gravacoes {
    private Gravacoes() {}

    static final int KEEP = 80;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static Path dir() { return FabricLoader.getInstance().getGameDir().resolve("mandarimcraft-gravacoes"); }

    /** grava o áudio (16 kHz, mono) como .wav e devolve o nome do arquivo; null se não deu. Rode fora da thread do jogo. */
    static String save(float[] audio, String label) {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            String name = STAMP.format(LocalDateTime.now()) + "_" + label.replaceAll("[^A-Za-z0-9_]", "") + ".wav";
            Path file = dir.resolve(name);
            for (int i = 2; Files.exists(file); i++) file = dir.resolve(name.replace(".wav", "-" + i + ".wav"));
            int bytes = audio.length * 2;
            ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes()).putInt(36 + bytes).put("WAVE".getBytes());
            b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
            b.put("data".getBytes()).putInt(bytes);
            for (float v : audio) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f))));
            try (OutputStream out = Files.newOutputStream(file)) { out.write(b.array()); }
            prune(dir);
            return file.getFileName().toString();
        } catch (Exception e) {
            MandarimCraft.LOG.warn("Não deu para guardar a gravação: {}", e.toString());
            return null;
        }
    }

    /** apaga as mais antigas (o nome começa com a data, então a ordem dos nomes é a do tempo) */
    private static void prune(Path dir) throws Exception {
        List<Path> wavs;
        try (Stream<Path> s = Files.list(dir)) { wavs = s.filter(p -> p.getFileName().toString().endsWith(".wav")).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList(); }
        for (int i = 0; i < wavs.size() - KEEP; i++) Files.deleteIfExists(wavs.get(i));
    }
}
