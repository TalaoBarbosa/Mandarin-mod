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
import java.util.List;

/** Ajustes do jogador (config/mandarimcraft.json): tempo, rigor dos tons, dica, microfone. */
public final class ClientConfig {
    public int seconds = 7;            // tempo para falar
    public String tones = "rigido";    // rigido | normal | leve
    public int retries = 1;            // quando erra: mais quantas tentativas na mesma vez
    public String hint = "auto";       // auto (pinyin até acertar; no "quantos você tem?" some conforme acerta) | nenhuma | hanzi | pinyin
    public boolean names = true;       // 汉字 + pinyin na dica dos itens (passar o mouse no inventário)
    public String mic = "";            // microfone escolhido nos ajustes (nome como o Java mostra); "" = o padrão do sistema
    public boolean saveAudio = false;  // guarda o áudio de cada tentativa em mandarimcraft-gravacoes (para ouvir de novo e ajustar o mod)
    public int version = 0;            // formato do arquivo (ver sane())

    private static final int VERSION = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig INSTANCE;

    public static ClientConfig get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("mandarimcraft.json"); }

    private static ClientConfig load() {
        ClientConfig c = null;
        try {
            Path f = file();
            if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                c = GSON.fromJson(r, ClientConfig.class);
            }
        } catch (Exception e) { MandarimCraft.LOG.warn("Config inválida, usando o padrão", e); }
        if (c == null) c = new ClientConfig();
        int was = c.version;
        c.sane();
        if (was != c.version) c.save();
        return c;
    }

    /** o arquivo pode ter sido editado à mão: volta para o padrão o que estiver fora do que os comandos aceitam */
    private void sane() {
        // até a versão 1 "nenhuma" era o padrão de todo mundo, não uma escolha: passa para a dica automática
        if (version < 2 && "nenhuma".equals(hint)) hint = "auto";
        version = VERSION;
        if (seconds < 3 || seconds > 20) seconds = 7;
        if (retries < 0 || retries > 2) retries = 1;
        if (tones == null || !List.of("rigido", "normal", "leve").contains(tones)) tones = "rigido";
        if (hint == null || !List.of("auto", "nenhuma", "hanzi", "pinyin").contains(hint)) hint = "auto";
        if (mic == null) mic = "";
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) { GSON.toJson(this, w); }
        catch (Exception e) { MandarimCraft.LOG.warn("Não deu para salvar a config", e); }
    }

    public double tol() {
        return switch (tones) { case "leve" -> 1.3; case "normal" -> 1.15; default -> 1.0; };
    }
}
