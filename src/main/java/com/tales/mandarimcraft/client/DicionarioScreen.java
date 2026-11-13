package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.core.Pinyin;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O dicionário (tecla H): todas as palavras do mod com o item, 汉字, pinyin e português, quais o jogador já aprendeu
 * neste mundo e quais já dominou. Clicar numa palavra toca a pronúncia.
 */
public class DicionarioScreen extends Screen {
    private static final int CELL_W = 152, CELL_H = 30, GAP = 4;
    private static final List<String> FILTERS = List.of("todas", "aprendidas", "faltam");

    private final Screen parent;
    private final Map<String, ItemStack> icons = new HashMap<>();
    private TextFieldWidget search;
    private String filter = "todas", query = "";
    private List<Vocab.Word> shown = List.of();
    private double scroll;
    private int cols, gridX, gridTop, gridBottom;

    public DicionarioScreen(Screen parent) {
        super(Text.literal("词典 · Dicionário"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cols = Math.max(1, Math.min(4, (width - 24 + GAP) / (CELL_W + GAP)));
        int gridW = cols * (CELL_W + GAP) - GAP;
        gridX = (width - gridW) / 2;
        gridTop = 58;
        gridBottom = height - 34;

        int fw = Math.min(110, gridW / 3);
        search = new TextFieldWidget(textRenderer, gridX, 32, gridW - fw - 6, 18, Text.literal("Procurar"));
        search.setPlaceholder(Text.literal("procurar: madeira, mutou, 木头…").formatted(Formatting.DARK_GRAY));
        search.setText(query);
        search.setChangedListener(s -> { query = s; scroll = 0; refresh(); });
        addDrawableChild(search);
        addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal).values(FILTERS).initially(filter).omitKeyText()
                .build(gridX + gridW - fw, 31, fw, 20, Text.literal("Mostrar"), (b, v) -> { filter = v; scroll = 0; refresh(); }));

        int bw = Math.min(150, (gridW - 6) / 2);
        addDrawableChild(ButtonWidget.builder(Text.literal("Ajustes e microfone"), b -> client.setScreen(new AjustesScreen(parent))).dimensions(width / 2 - bw - 3, height - 26, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pronto"), b -> close()).dimensions(width / 2 + 3, height - 26, bw, 20).build());
        setInitialFocus(search);
        refresh();
    }

    /** as palavras que passam no filtro e na busca (português, pinyin com ou sem acento, 汉字) */
    private void refresh() {
        String q = MandarimClient.plain(query).replace(" ", "");
        List<Vocab.Word> out = new ArrayList<>();
        for (Vocab.Word w : Vocab.all()) {
            boolean learned = Progresso.isLearned(w.key);
            if (filter.equals("aprendidas") && !learned) continue;
            if (filter.equals("faltam") && learned) continue;
            if (!q.isEmpty() && !MandarimClient.plain(w.pt).replace(" ", "").contains(q) && !w.zh.contains(query.trim())
                    && !MandarimClient.plain(w.pretty()).replace(" ", "").contains(q) && !w.key.contains(q)) continue;
            out.add(w);
        }
        shown = out;
    }

    private int maxScroll() {
        int rows = (shown.size() + cols - 1) / cols;
        return Math.max(0, rows * (CELL_H + GAP) - GAP - (gridBottom - gridTop));
    }

    /** a palavra embaixo do mouse, ou null */
    private Vocab.Word at(double mx, double my) {
        if (my < gridTop || my >= gridBottom || mx < gridX) return null;
        int col = (int) ((mx - gridX) / (CELL_W + GAP)), row = (int) ((my - gridTop + scroll) / (CELL_H + GAP));
        if (col >= cols || (mx - gridX) - col * (CELL_W + GAP) >= CELL_W || (my - gridTop + scroll) - row * (CELL_H + GAP) >= CELL_H) return null;
        int i = row * cols + col;
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    /** só mostra o chinês de uma palavra que falta se o jogador deixa o nome aparecer nos itens (senão seria colar) */
    private static boolean reveal(Vocab.Word w) { return Progresso.isLearned(w.key) || ClientConfig.get().names; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        int total = Vocab.all().size(), learned = 0, mastered = 0;
        for (Vocab.Word w : Vocab.all()) { if (Progresso.isLearned(w.key)) learned++; if (Progresso.isMastered(w.key)) mastered++; }
        String head = !Progresso.known ? "Entre num mundo com o mod para ver o seu progresso."
                : learned + " de " + total + " aprendidas · " + mastered + " dominadas ★" + (Progresso.active ? "" : " · mod pausado neste mundo");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(head), width / 2, 19, Hud.GRAY);

        Vocab.Word hover = at(mouseX, mouseY);
        ctx.enableScissor(0, gridTop, width, gridBottom);
        for (int i = 0; i < shown.size(); i++) {
            int x = gridX + (i % cols) * (CELL_W + GAP), y = gridTop + (i / cols) * (CELL_H + GAP) - (int) scroll;
            if (y + CELL_H < gridTop || y > gridBottom) continue;
            cell(ctx, x, y, shown.get(i), shown.get(i) == hover);
        }
        ctx.disableScissor();
        if (shown.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Nenhuma palavra aqui."), width / 2, gridTop + 20, Hud.DARK);

        int max = maxScroll();
        if (max > 0) {   // barrinha de rolagem
            int trackX = gridX + cols * (CELL_W + GAP) - GAP + 3, trackH = gridBottom - gridTop;
            int thumbH = Math.max(12, trackH * trackH / (trackH + max)), thumbY = gridTop + (int) ((trackH - thumbH) * scroll / max);
            ctx.fill(trackX, gridTop, trackX + 3, gridBottom, 0x40FFFFFF);
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xC0FFFFFF);
        }
        if (hover != null) ctx.drawTooltip(textRenderer, tooltip(hover), mouseX, mouseY);
    }

    private void cell(DrawContext ctx, int x, int y, Vocab.Word w, boolean hover) {
        boolean learned = Progresso.isLearned(w.key), mastered = Progresso.isMastered(w.key), reveal = reveal(w);
        int border = hover ? 0xFFFFFFFF : mastered ? 0xC0FFAA00 : learned ? 0x9055FF55 : 0x50FFFFFF;
        ctx.fill(x, y, x + CELL_W, y + CELL_H, hover ? 0xC0202030 : 0x90100010);
        ctx.fill(x, y, x + CELL_W, y + 1, border);
        ctx.fill(x, y + CELL_H - 1, x + CELL_W, y + CELL_H, border);
        ctx.fill(x, y + 1, x + 1, y + CELL_H - 1, border);
        ctx.fill(x + CELL_W - 1, y + 1, x + CELL_W, y + CELL_H - 1, border);
        ItemStack icon = icons.computeIfAbsent(w.key, k -> w.icon());
        if (!icon.isEmpty()) ctx.drawItem(icon, x + 5, y + 7);
        int tx = x + 26;
        if (reveal) {
            Text zh = Hud.kai(w.zh, Hud.KAI);
            ctx.drawTextWithShadow(textRenderer, zh, tx, y + 4, learned ? Hud.WHITE : Hud.GRAY);
            String py = w.pretty();
            int px = tx + textRenderer.getWidth(zh) + 5, room = x + CELL_W - 14 - px;
            if (textRenderer.getWidth(py) <= room) ctx.drawTextWithShadow(textRenderer, py, px, y + 5, learned ? Hud.GOLD : Hud.DARK);
        } else {
            ctx.drawTextWithShadow(textRenderer, "？？", tx, y + 5, Hud.DARK);
        }
        ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(w.pt, CELL_W - 30), tx, y + 17, learned ? Hud.GRAY : Hud.DARK);
        String mark = mastered ? "★" : learned ? "✓" : "";
        if (!mark.isEmpty()) ctx.drawTextWithShadow(textRenderer, mark, x + CELL_W - 4 - textRenderer.getWidth(mark), y + 4, mastered ? Hud.GOLD : Hud.GREEN);
    }

    private List<Text> tooltip(Vocab.Word w) {
        List<Text> t = new ArrayList<>();
        boolean learned = Progresso.isLearned(w.key);
        if (reveal(w)) {
            t.add(Text.empty().append(Hud.kai(w.zh, Hud.KAI).formatted(Formatting.GOLD)).append(Text.literal("  " + w.pretty()).formatted(Formatting.WHITE)));
            t.add(Text.literal(w.pt).formatted(Formatting.GRAY));
            Vocab.Alt mw = w.mw.get(0);
            // o 一 muda de tom conforme o que vem depois: yí antes do 4º tom, yì antes dos outros
            String yi = Pinyin.parse(mw.py()).get(0).tone() == 4 ? "yi2 " : "yi4 ";
            t.add(Text.literal("contar: ").formatted(Formatting.DARK_GRAY).append(Hud.kai("一" + mw.zh() + w.zh, Hud.KAI).formatted(Formatting.GRAY))
                    .append(Text.literal("  " + Pinyin.pretty(yi + mw.py())).formatted(Formatting.DARK_GRAY)));
        } else {
            t.add(Text.literal(w.pt).formatted(Formatting.WHITE));
            t.add(Text.literal("Ainda não aprendida (nomes escondidos nos ajustes).").formatted(Formatting.DARK_GRAY));
        }
        if (Progresso.known) {
            int stage = Progresso.learned.getOrDefault(w.key, 0), fails = Progresso.fails.getOrDefault(w.key, 0);
            t.add(Text.literal(!learned ? "ainda não aprendida" : Progresso.isMastered(w.key) ? "★ dominada" : "aprendida · " + stage + (stage == 1 ? " revisão certa" : " revisões certas"))
                    .formatted(!learned ? Formatting.DARK_GRAY : Progresso.isMastered(w.key) ? Formatting.GOLD : Formatting.GREEN));
            if (fails > 0) t.add(Text.literal("errou " + fails + (fails == 1 ? " vez" : " vezes")).formatted(Formatting.RED));
        }
        if (reveal(w)) t.add(Text.literal("clique para ouvir").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
        return t;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Vocab.Word w = at(mouseX, mouseY);
        if (w != null && button == 0 && reveal(w)) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            Desafio.playWord(w.key);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * (CELL_H + GAP)));
        return true;
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
