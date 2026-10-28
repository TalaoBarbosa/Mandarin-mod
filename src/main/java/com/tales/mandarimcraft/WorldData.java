package com.tales.mandarimcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** O que cada jogador já aprendeu neste mundo (arquivo mandarimcraft.json dentro da pasta do mundo). */
public final class WorldData {
    public static final class PlayerData {
        public boolean paused = true;          // começa pausado: só desafia depois de /mandarim comecar
        public boolean started = false;
        public Map<String, Long> learned = new LinkedHashMap<>();   // palavra -> quando aprendeu
        public Map<String, Integer> fails = new HashMap<>();         // palavra -> quantas vezes errou
        public Set<String> grace = new HashSet<>();                  // itens que já tinha ao começar: errar não tira
        public int itemsLost = 0;

        /** campo apagado ou "null" no arquivo (editado à mão) não pode derrubar o servidor depois */
        PlayerData fix() {
            if (learned == null) learned = new LinkedHashMap<>();
            if (fails == null) fails = new HashMap<>();
            if (grace == null) grace = new HashSet<>();
            return this;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private Map<String, PlayerData> players = new HashMap<>();

    public WorldData(Path file) { this.file = file; }

    public static WorldData load(Path file) {
        WorldData d = new WorldData(file);
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Map<String, PlayerData> m = GSON.fromJson(r, new TypeToken<Map<String, PlayerData>>() {}.getType());
                    if (m != null) {
                        m.values().removeIf(Objects::isNull);
                        m.values().forEach(PlayerData::fix);
                        d.players = m;
                    }
                }
            }
        } catch (Exception e) {
            // guarda uma cópia antes de começar do zero: o próximo save() sobrescreveria o progresso de todo mundo
            Path bak = file.resolveSibling(file.getFileName() + ".com-erro");
            MandarimCraft.LOG.error("Erro lendo {} (cópia guardada em {})", file, bak, e);
            try { Files.copy(file, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
        }
        return d;
    }

    public synchronized void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { GSON.toJson(players, w); }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            MandarimCraft.LOG.error("Erro salvando {}", file, e);
        }
    }

    public synchronized PlayerData get(UUID id) { return players.computeIfAbsent(id.toString(), k -> new PlayerData()); }
    public synchronized boolean has(UUID id) { return players.containsKey(id.toString()); }
}
