package com.tales.mandarimcraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * O quadro do desafio no alto da tela, no jeito das dicas de item do Minecraft: fundo escuro translúcido com a borda
 * roxa delas (verde, amarela ou vermelha no resultado), o item grande à esquerda, 汉字 em tamanho dobrado com o pinyin
 * dourado ao lado e o tempo numa barra de chefe que muda de cor quando está acabando. Entra e sai deslizando.
 */
public final class Hud {
    private Hud() {}

    // cores de texto do Minecraft (§7 §a §e §c §6 §f §8)
    static final int GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, RED = 0xFFFF5555, GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, DARK = 0xFF777777;

    private static final int BG = 0xB0100010;   // o fundo das dicas de item (0xF0100010), mais transparente
    private static final int PAD = 6, ICON = 32, GAP = 8, BAR = 182, MIN_TEXT = 160;
    private static final String[] BAR_COLORS = {"blue", "yellow", "red", "pink"};
    private static final Identifier[] BAR_BG = new Identifier[BAR_COLORS.length], BAR_FG = new Identifier[BAR_COLORS.length];
    static {
        for (int i = 0; i < BAR_COLORS.length; i++) {
            BAR_BG[i] = Identifier.ofVanilla("boss_bar/" + BAR_COLORS[i] + "_background");
            BAR_FG[i] = Identifier.ofVanilla("boss_bar/" + BAR_COLORS[i] + "_progress");
        }
    }

    private static long openedAt, lastFrame;

    /** por cima de uma tela aberta (inventário, baú…): à frente dos itens dos slots, como uma dica de item */
    public static void renderOnTop(DrawContext ctx) {
        if (Desafio.state == Desafio.St.IDLE) return;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        render(ctx);
        ctx.getMatrices().pop();
    }

    public static void render(DrawContext ctx) {
        Desafio.St st = Desafio.state;
        if (st == Desafio.St.IDLE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        if (now - lastFrame > 250) openedAt = now;   // estava escondido: entra deslizando
        lastFrame = now;

        TextRenderer tr = mc.textRenderer;
        boolean listening = st == Desafio.St.LISTEN || st == Desafio.St.CALIBRATE;
        String title = st == Desafio.St.THINK ? Desafio.title + ".".repeat(1 + (int) (now / 350 % 3)) : Desafio.title;
        String zh = Desafio.zh, py = Desafio.py, l1 = Desafio.line1, l2 = Desafio.line2, l3 = Desafio.line3;
        int zhW = zh.isEmpty() ? 0 : tr.getWidth(zh) * 2, pyW = py.isEmpty() ? 0 : tr.getWidth(py);
        boolean pyBeside = zhW + 6 + pyW <= 200;   // pinyin ao lado do 汉字 se couber; senão embaixo (a frase da calibração)

        // tamanho: a coluna de texto cabe a linha mais comprida; o item fica numa coluna própria à esquerda
        int textW = Math.max(MIN_TEXT, Math.max(Math.max(tr.getWidth(title), tr.getWidth(l1)), Math.max(tr.getWidth(l2), tr.getWidth(l3))));
        textW = Math.max(textW, pyBeside ? zhW + (pyW > 0 ? 6 + pyW : 0) : Math.max(zhW, pyW));
        if (listening) textW = Math.max(textW, BAR);
        int textH = 11 + (l1.isEmpty() ? 0 : 11) + (zh.isEmpty() ? 0 : 19 + (!pyBeside && pyW > 0 ? 11 : 0))
                + (l2.isEmpty() ? 0 : 11) + (l3.isEmpty() ? 0 : 11) + (listening ? 22 : 0) - 2;
        boolean hasIcon = !Desafio.icon.isEmpty();
        int sw = ctx.getScaledWindowWidth();
        int w = Math.min(sw - 8, PAD + (hasIcon ? ICON + GAP : 2) + textW + PAD);
        int h = PAD + Math.max(textH, hasIcon ? ICON : 0) + PAD;
        int x = (sw - w) / 2;

        // desliza para dentro ao aparecer e para fora no fim do resultado (se não vem outro desafio logo atrás)
        float k = ease((now - openedAt) / 180f);
        if (st == Desafio.St.SHOW && Desafio.pending == null) k = Math.min(k, ease((Desafio.showUntil - now) / 180f));
        int y = 6 - Math.round((1 - k) * (h + 8));

        panel(ctx, x, y, w, h, Desafio.color);

        if (hasIcon) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + PAD, y + (h - ICON) / 2f, 0);
            ctx.getMatrices().scale(2f, 2f, 1f);
            ctx.drawItem(Desafio.icon, 0, 0);
            ctx.getMatrices().pop();
        }

        int tx = x + PAD + (hasIcon ? ICON + GAP : 2), ty = y + PAD + Math.max(0, (hasIcon ? ICON : 0) - textH) / 2;
        ctx.drawTextWithShadow(tr, title, tx, ty, Desafio.color);
        ty += 11;
        if (!l1.isEmpty()) { ctx.drawTextWithShadow(tr, l1, tx, ty, WHITE); ty += 11; }
        if (!zh.isEmpty()) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(tx, ty, 0);
            ctx.getMatrices().scale(2f, 2f, 1f);   // dobro exato: o 汉字 continua nítido (1,6x deixava os pixels tortos)
            ctx.drawTextWithShadow(tr, zh, 0, 0, WHITE);
            ctx.getMatrices().pop();
            if (pyW > 0 && pyBeside) ctx.drawTextWithShadow(tr, py, tx + zhW + 6, ty + 4, GOLD);
            ty += 19;
            if (pyW > 0 && !pyBeside) { ctx.drawTextWithShadow(tr, py, tx, ty, GOLD); ty += 11; }
        }
        if (!l2.isEmpty()) { ctx.drawTextWithShadow(tr, l2, tx, ty, GRAY); ty += 11; }
        if (!l3.isEmpty()) { ctx.drawTextWithShadow(tr, l3, tx, ty, DARK); ty += 11; }

        if (listening) {
            long left = Math.max(0, Desafio.deadline - now);
            long total = st == Desafio.St.CALIBRATE ? 9000 : Math.max(1, ClientConfig.get().seconds * 1000L);
            boolean hearing = Desafio.MIC.speechStarted;
            ctx.drawTextWithShadow(tr, hearing ? "♪ Ouvindo…" : "Fale agora", tx, ty, hearing ? GREEN : WHITE);
            String secs = (left + 999) / 1000 + " s";
            ctx.drawTextWithShadow(tr, secs, tx + BAR - tr.getWidth(secs), ty, GRAY);
            ty += 11;
            // tempo: barra de chefe azul → amarela → vermelha (rosa na calibração)
            float frac = Math.min(1f, (float) left / total);
            int c = st == Desafio.St.CALIBRATE ? 3 : frac > 0.4f ? 0 : frac > 0.2f ? 1 : 2;
            ctx.drawGuiTexture(BAR_BG[c], BAR, 5, 0, 0, tx, ty, BAR, 5);
            int fill = Math.round(BAR * frac);
            if (fill > 0) ctx.drawGuiTexture(BAR_FG[c], BAR, 5, 0, 0, tx, ty, fill, 5);
            ty += 7;
            // volume do microfone: linha fina embaixo (verde quando já está ouvindo a fala)
            ctx.fill(tx, ty, tx + BAR, ty + 2, 0x40FFFFFF);
            ctx.fill(tx, ty, tx + Math.round(BAR * Desafio.MIC.level), ty + 2, hearing ? GREEN : GRAY);
        }
    }

    /** fundo e borda como os das dicas de item; a borda pega a cor do momento (roxa = a das dicas, neutra) */
    private static void panel(DrawContext ctx, int x, int y, int w, int h, int color) {
        int top, bottom;
        if (color == GREEN) { top = 0xC055FF55; bottom = 0x70207A20; }
        else if (color == YELLOW) { top = 0xC0FFFF55; bottom = 0x707A7A20; }
        else if (color == RED) { top = 0xC0FF5555; bottom = 0x707A2020; }
        else { top = 0x805000FF; bottom = 0x8028007F; }
        ctx.fill(x + 1, y, x + w - 1, y + h, BG);
        ctx.fill(x, y + 1, x + 1, y + h - 1, BG);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);
        ctx.fillGradient(x + 1, y + 1, x + 2, y + h - 1, top, bottom);
        ctx.fillGradient(x + w - 2, y + 1, x + w - 1, y + h - 1, top, bottom);
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, top);
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, bottom);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }
}
