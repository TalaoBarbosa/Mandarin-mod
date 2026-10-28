package com.tales.mandarimcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tales.mandarimcraft.MandarimCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Ajustes do jogador (config/mandarimcraft.json): tempo, rigor dos tons, dica e a referência da voz. */
public final class ClientConfig {
    public int seconds = 7;            // tempo para falar
    public String tones = "rigido";    // rigido | normal | leve
    public int retries = 1;            // quando só o tom sai errado: mais quantas tentativas na mesma vez
    public String hint = "nenhuma";    // nenhuma | hanzi | pinyin
    public double refHz = 0;           // altura média da voz (calibrar); 0 = ainda não calibrou
    public int samples = 0;            // quantas falas já entraram na média

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig INSTANCE;

    public static ClientConfig get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("mandarimcraft.json"); }

    private static ClientConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                ClientConfig c = GSON.fromJson(r, ClientConfig.class);
                if (c != null) return c;
            }
        } catch (Exception e) { MandarimCraft.LOG.warn("Config inválida, usando o padrão", e); }
        return new ClientConfig();
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) { GSON.toJson(this, w); }
        catch (Exception e) { MandarimCraft.LOG.warn("Não deu para salvar a config", e); }
    }

    public double tol() {
        return switch (tones) { case "leve" -> 1.3; case "normal" -> 1.15; default -> 1.0; };
    }

    /** junta a altura desta fala na média da voz (em escala log, para não puxar demais por uma fala só) */
    public void addVoice(double hz) {
        if (hz < 60 || hz > 450) return;
        if (refHz <= 0 || samples == 0) { refHz = hz; samples = 1; }
        else {
            double w = samples < 5 ? 1.0 / (samples + 1) : 0.15;
            refHz = Math.exp((1 - w) * Math.log(refHz) + w * Math.log(hz));
            samples++;
        }
        save();
    }
}
