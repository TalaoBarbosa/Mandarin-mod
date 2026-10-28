package com.tales.mandarimcraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** O quadro do desafio no alto da tela: item, o que dizer, tempo, nível do microfone e o resultado. */
public final class Hud {
    private Hud() {}

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
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth();
        boolean listening = st == Desafio.St.LISTEN || st == Desafio.St.CALIBRATE;
        boolean big = !Desafio.line2.isEmpty() && (st == Desafio.St.CALIBRATE || Desafio.line2.matches(".*[\\u4e00-\\u9fff].*"));
        // largura que cabe o texto mais comprido (mensagens de erro do microfone passavam da borda do quadro)
        int textW = Math.max(tr.getWidth(Desafio.title), Math.max(tr.getWidth(Desafio.line1), tr.getWidth(Desafio.line3)));
        textW = Math.max(textW, big ? (int) Math.ceil(tr.getWidth(Desafio.line2) * 1.6f) : tr.getWidth(Desafio.line2));
        int w = Math.min(sw - 16, Math.max(280, textW + (Desafio.icon.isEmpty() ? 16 : 36))), x = (sw - w) / 2, y = 6;
        int h = 34 + (Desafio.line1.isEmpty() ? 0 : 12) + (Desafio.line2.isEmpty() ? 0 : (big ? 20 : 12)) + (Desafio.line3.isEmpty() ? 0 : 12) + (listening ? 12 : 0);
        int border = st == Desafio.St.SHOW ? (Desafio.good ? 0xFF3FAF5F : 0xFFD24A4A) : 0xFF6B7FA6;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        ctx.fill(x, y, x + w, y + h, 0xE0101622);
        int tx = x + 8, ty = y + 7;
        if (!Desafio.icon.isEmpty()) { ctx.drawItem(Desafio.icon, x + 6, y + 6); tx = x + 28; }
        ctx.drawTextWithShadow(tr, Text.literal(Desafio.title), tx, ty, Desafio.color);
        ty += 13;
        if (!Desafio.line1.isEmpty()) {
            int c = (st == Desafio.St.LISTEN && !Desafio.practice) ? 0xFFFFE27A : 0xFFDDDDDD;
            ctx.drawTextWithShadow(tr, Text.literal(Desafio.line1), tx, ty + 1, c);
            ty += 12;
        }
        if (!Desafio.line2.isEmpty()) {
            if (big) {
                ctx.getMatrices().push();
                ctx.getMatrices().scale(1.6f, 1.6f, 1f);
                ctx.drawTextWithShadow(tr, Text.literal(Desafio.line2), (int) (tx / 1.6f), (int) ((ty + 2) / 1.6f), 0xFFFFFFFF);
                ctx.getMatrices().pop();
                ty += 20;
            } else {
                ctx.drawTextWithShadow(tr, Text.literal(Desafio.line2), tx, ty + 1, 0xFFBBBBBB);
                ty += 12;
            }
        }
        if (!Desafio.line3.isEmpty()) { ctx.drawTextWithShadow(tr, Text.literal(Desafio.line3), tx, ty + 1, 0xFF9AA4B8); ty += 12; }
        if (listening) {
            long left = Math.max(0, Desafio.deadline - System.currentTimeMillis());
            long total = st == Desafio.St.CALIBRATE ? 9000 : Math.max(1, ClientConfig.get().seconds * 1000L);
            String s = (Desafio.MIC.speechStarted ? "Ouvindo… " : "Fale agora… ") + (left + 999) / 1000 + " s";
            ctx.drawTextWithShadow(tr, Text.literal(s), x + 8, ty + 2, 0xFF9FD3FF);
            int bx = x + 100, bw = w - 108;
            ctx.fill(bx, ty + 3, bx + bw, ty + 6, 0xFF2A3346);
            ctx.fill(bx, ty + 3, bx + (int) (bw * Math.min(1.0, (double) left / total)), ty + 6, 0xFF6FB1FF);
            ctx.fill(bx, ty + 8, bx + bw, ty + 10, 0xFF2A3346);
            ctx.fill(bx, ty + 8, bx + (int) (bw * Desafio.MIC.level), ty + 10, 0xFF7CE38B);
        }
        if (st == Desafio.St.THINK) ctx.drawTextWithShadow(tr, Text.literal("…"), x + w - 14, y + 7, 0xFFFFFFFF);
    }
}
