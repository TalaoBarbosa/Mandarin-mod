package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Tela de ajustes (/hanyu ajustes, ou pelo dicionário): escolher o microfone vendo o volume ao vivo, treinar, guardar
 * as gravações e mudar o que antes só dava por comando. Enquanto ela está aberta nenhum desafio começa.
 */
public class AjustesScreen extends Screen {
    private static final int W = 310, ROW = 24;

    private final Screen parent;
    /** microfones que o Java enxerga; "" = o padrão do sistema. Carregada em segundo plano (no Windows demora). */
    private List<String> devices;
    private boolean loading = true;
    private float shown;       // o volume desenhado: sobe na hora e desce devagar, para dar para ler
    private int micY;

    public AjustesScreen(Screen parent) {
        super(Text.literal("Mandarim Craft: ajustes"));
        this.parent = parent;
        ClientConfig k = ClientConfig.get();
        devices = new ArrayList<>(List.of(""));
        if (!k.mic.isEmpty()) devices.add(k.mic);
    }

    @Override
    protected void init() {
        ClientConfig k = ClientConfig.get();
        if (loading) {
            loading = false;
            Desafio.WORK.submit(() -> {
                List<String> found = new ArrayList<>(List.of(""));
                try { found.addAll(Mic.devices()); } catch (Throwable t) { MandarimCraft.LOG.warn("Não deu para listar os microfones", t); }
                if (!k.mic.isEmpty() && !found.contains(k.mic)) found.add(k.mic);   // o escolhido está desligado agora: continua na lista
                if (client != null) client.execute(() -> { devices = found; if (client.currentScreen == this) clearAndInit(); });
            });
        }
        int x = (width - W) / 2, half = (W - 6) / 2, y = Math.max(34, height / 2 - 108);

        micY = y;
        addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(v.isEmpty() ? "padrão do sistema" : shorten(v, 34)))
                .values(devices).initially(devices.contains(k.mic) ? k.mic : "")
                .build(x, y, W, 20, Text.literal("Microfone"), (b, v) -> { k.mic = v; k.save(); if (Desafio.state == Desafio.St.IDLE) Desafio.MIC.start(); }));
        y += ROW + 16;   // o volume ao vivo é desenhado neste espaço

        addDrawableChild(ButtonWidget.builder(Text.literal("Treino (não perde nada)"), b -> { client.setScreen(null); Desafio.startPractice(); }).dimensions(x, y, half, 20).build());
        addDrawableChild(CyclingButtonWidget.onOffBuilder(Text.literal("sim"), Text.literal("não")).initially(k.saveAudio)
                .tooltip(v -> Tooltip.of(Text.literal("Guarda o áudio de cada tentativa (as " + Gravacoes.KEEP + " últimas) na pasta do jogo, em mandarimcraft-gravacoes: dá para ouvir o que o mod ouviu e mandar junto com o log para ajustar os tons à sua voz.")))
                .build(x + half + 6, y, half, 20, Text.literal("Guardar gravações"), (b, v) -> k.saveAudio = v));
        y += ROW + 6;

        addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(switch (v) { case "rigido" -> "rígido"; default -> v; }))
                .values(List.of("rigido", "normal", "leve")).initially(k.tones)
                .build(x, y, half, 20, Text.literal("Tons"), (b, v) -> k.tones = v));
        addDrawableChild(new SliderWidget(x + half + 6, y, half, 20, Text.empty(), (k.seconds - 3) / 17.0) {
            { updateMessage(); }
            private int seconds() { return 3 + (int) Math.round(value * 17); }
            @Override protected void updateMessage() { setMessage(Text.literal("Tempo para falar: " + seconds() + " s")); }
            @Override protected void applyValue() { k.seconds = seconds(); }
        });
        y += ROW;

        addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(switch (v) { case "auto" -> "automática"; case "pinyin" -> "sempre pinyin"; case "hanzi" -> "só 汉字"; default -> "nenhuma"; }))
                .values(List.of("auto", "pinyin", "hanzi", "nenhuma")).initially(k.hint)
                .build(x, y, half, 20, Text.literal("Dica"), (b, v) -> k.hint = v));
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(String.valueOf(v)))
                .values(List.of(0, 1, 2)).initially(k.retries)
                .build(x + half + 6, y, half, 20, Text.literal("Chances extras"), (b, v) -> k.retries = v));
        y += ROW;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(Text.literal("sim"), Text.literal("não")).initially(k.names)
                .build(x, y, W, 20, Text.literal("Nome em chinês na dica dos itens"), (b, v) -> k.names = v));
        y += ROW + 6;

        addDrawableChild(ButtonWidget.builder(Text.literal("Dicionário"), b -> client.setScreen(new DicionarioScreen(parent))).dimensions(x, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pronto"), b -> close()).dimensions(x + half + 6, y, half, 20).build());
    }

    private static String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    @Override
    public void tick() {
        // o microfone fica aberto só para a barra de volume, e só se não tem desafio usando ele
        if (Desafio.state == Desafio.St.IDLE && !Desafio.MIC.active()) Desafio.MIC.start();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ClientConfig k = ClientConfig.get();
        int x = (width - W) / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, Math.max(8, micY - 24), 0xFFFFFF);
        String ver = FabricLoader.getInstance().getModContainer(MandarimCraft.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("reconhecimento: " + Speech.status() + " · versão " + ver),
                width / 2, Math.max(19, micY - 12), Hud.DARK);

        // volume ao vivo do microfone escolhido
        Mic mic = Desafio.MIC;
        float level = Desafio.state == Desafio.St.IDLE ? mic.level : 0;
        shown = level > shown ? level : Math.max(level, shown - 0.02f);
        int by = micY + 24, bw = W - 2;
        String msg = Desafio.state != Desafio.St.IDLE ? "um desafio está usando o microfone"
                : mic.error != null ? mic.error
                : mic.opening ? "abrindo o microfone…"
                : shown > 0.12f ? "ouvindo você" : "fale algo: a barra tem que mexer";
        ctx.fill(x + 1, by, x + 1 + bw, by + 4, 0x60FFFFFF);
        ctx.fill(x + 1, by, x + 1 + Math.round(bw * Math.min(1f, shown)), by + 4, mic.error != null ? Hud.RED : shown > 0.12f ? Hud.GREEN : Hud.GRAY);
        ctx.drawTextWithShadow(textRenderer, msg, x + 1, by + 6, mic.error != null ? Hud.RED : Hud.GRAY);
    }

    @Override
    public void removed() {
        ClientConfig.get().save();
        if (Desafio.state == Desafio.St.IDLE) Desafio.MIC.stopQuiet();
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
