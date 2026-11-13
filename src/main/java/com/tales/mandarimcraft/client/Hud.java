package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.client.mixin.BossBarHudAccessor;
import com.tales.mandarimcraft.core.Pinyin;
import com.tales.mandarimcraft.core.ToneCheck;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * O quadro do desafio no alto da tela, no jeito das dicas de item do Minecraft: fundo escuro translúcido com a borda
 * roxa delas (verde, amarela ou vermelha no resultado), o item grande à esquerda, 汉字 em tamanho dobrado com o pinyin
 * dourado ao lado e o tempo numa barra de chefe que muda de cor quando está acabando. Entra e sai deslizando.
 * No resultado, a curva da voz em cada sílaba aparece desenhada por cima da forma certa do tom.
 */
public final class Hud {
    private Hud() {}

    // cores de texto do Minecraft (§7 §a §e §c §6 §f §8)
    static final int GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, RED = 0xFFFF5555, GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, DARK = 0xFF777777;

    private static final int BG = 0xB0100010;   // o fundo das dicas de item (0xF0100010), mais transparente
    private static final int PAD = 6, ICON = 32, GAP = 8, BAR = 182, MIN_TEXT = 160;
    /** o desenho do tom de uma sílaba: largura e altura da caixinha, e o espaço entre duas */
    private static final int CW = 34, CH = 24, CGAP = 6;
    private static final int SHAPE = 0xFFB4B4B4;
    private static final String[] BAR_COLORS = {"blue", "yellow", "red", "pink"};
    private static final Identifier[] BAR_BG = new Identifier[BAR_COLORS.length], BAR_FG = new Identifier[BAR_COLORS.length];
    static {
        for (int i = 0; i < BAR_COLORS.length; i++) {
            BAR_BG[i] = Identifier.ofVanilla("boss_bar/" + BAR_COLORS[i] + "_background");
            BAR_FG[i] = Identifier.ofVanilla("boss_bar/" + BAR_COLORS[i] + "_progress");
        }
    }

    /** fonte chinesa (recorte da LXGW WenKai GB, em assets/mandarimcraft/font): normal e grande; o que ela não tem cai na do Minecraft */
    static final Identifier KAI = Identifier.of("mandarimcraft", "kai"), KAI_BIG = Identifier.of("mandarimcraft", "kai_big");

    static MutableText kai(String s, Identifier font) { return Text.literal(s).setStyle(Style.EMPTY.withFont(font)); }

    private static long openedAt;
    private static boolean hidden = true;

    /**
     * Por cima de uma tela aberta (inventário, baú…), à frente dos itens dos slots como uma dica de item. Aqui vai a
     * versão compacta, numa faixa: o quadro inteiro cobriria a parte de cima do inventário (e a 1ª fileira de um baú).
     */
    public static void renderOnTop(DrawContext ctx) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        waitingHint(ctx);
        if (visible()) compact(ctx);
        ctx.getMatrices().pop();
    }

    /** desafio esperando um momento calmo: uma linha discreta no alto, para ele saber por que ainda não começou */
    private static void waitingHint(DrawContext ctx) {
        if (Desafio.state != Desafio.St.IDLE || Desafio.waiting == null || Desafio.pending == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        String s = "汉语: desafio assim que você parar (" + Desafio.waiting + ")";
        int w = tr.getWidth(s) + 12, x = (ctx.getScaledWindowWidth() - w) / 2, y = top(ctx) - 4;
        panel(ctx, x, y, w, 15, GRAY);
        ctx.drawTextWithShadow(tr, s, x + 6, y + 4, GRAY);
    }

    /**
     * onde o quadro começa: no alto da tela, ou logo abaixo das barras de chefe (dragão, Wither, invasão), que o
     * Minecraft desenha ali (a 1ª em y = 12, 19 de altura cada, até 1/3 da tela)
     */
    private static int top(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return 6;   // com uma tela aberta as barras ficam atrás dela
        int n = 0;
        try {
            int total = ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).mandarimcraft$bossBars().size();
            for (int j = 12; n < total; ) { n++; j += 19; if (j >= ctx.getScaledWindowHeight() / 3) break; }
        } catch (RuntimeException ignored) {}   // sem o acesso (outro mod mexeu nessa classe): fica no alto
        return n == 0 ? 6 : 19 * n + 3;
    }

    /** marca quando o quadro aparece (pela mudança de estado: contar o tempo entre quadros falhava com o jogo travando) */
    private static boolean visible() {
        if (Desafio.state == Desafio.St.IDLE || MinecraftClient.getInstance().options.hudHidden) { hidden = true; return false; }
        if (hidden) { hidden = false; openedAt = System.currentTimeMillis(); }
        return true;
    }

    /** 0 = escondido acima da tela, 1 = no lugar: desliza ao aparecer e no fim do resultado (se não vem outro desafio logo atrás) */
    private static float slide(long now) {
        float k = ease((now - openedAt) / 180f);
        if (Desafio.state == Desafio.St.SHOW && Desafio.pending == null) k = Math.min(k, ease((Desafio.showUntil - now) / 180f));
        return k;
    }

    public static void render(DrawContext ctx) {
        waitingHint(ctx);
        if (!visible()) return;
        Desafio.St st = Desafio.state;
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();

        TextRenderer tr = mc.textRenderer;
        boolean listening = st == Desafio.St.LISTEN || st == Desafio.St.CALIBRATE;
        String title = st == Desafio.St.THINK ? Desafio.title + ".".repeat(1 + (int) (now / 350 % 3)) : Desafio.title;
        String zh = Desafio.zh, py = Desafio.py, l1 = Desafio.line1, l2 = Desafio.line2, l3 = Desafio.line3;
        Text zhText = kai(zh, KAI_BIG);
        int zhW = zh.isEmpty() ? 0 : tr.getWidth(zhText), pyW = py.isEmpty() ? 0 : tr.getWidth(py);
        boolean pyBeside = zhW + 6 + pyW <= 200;   // pinyin ao lado do 汉字 se couber; senão embaixo (a frase da calibração)
        ToneCheck.Result cv = st == Desafio.St.THINK ? null : Desafio.curves;
        int cvN = cv == null ? 0 : cv.syls().size();
        String legA = "tom certo", legB = "sua voz";
        int cvW = cvN == 0 ? 0 : cvN * (CW + CGAP) + 4 + 11 + Math.max(tr.getWidth(legA), tr.getWidth(legB));

        // tamanho: a coluna de texto cabe a linha mais comprida; o item fica numa coluna própria à esquerda
        int textW = Math.max(MIN_TEXT, Math.max(Math.max(tr.getWidth(title), tr.getWidth(l1)), Math.max(tr.getWidth(l2), tr.getWidth(l3))));
        textW = Math.max(textW, pyBeside ? zhW + (pyW > 0 ? 6 + pyW : 0) : Math.max(zhW, pyW));
        if (listening) textW = Math.max(textW, BAR);
        textW = Math.max(textW, cvW);
        int textH = 11 + (l1.isEmpty() ? 0 : 11) + (zh.isEmpty() ? 0 : 19 + (!pyBeside && pyW > 0 ? 11 : 0))
                + (cvN > 0 ? CH + 14 : 0)
                + (l2.isEmpty() ? 0 : 11) + (l3.isEmpty() ? 0 : 11) + (listening ? 22 : 0) - 2;
        boolean hasIcon = !Desafio.icon.isEmpty();
        int sw = ctx.getScaledWindowWidth();
        int w = Math.min(sw - 8, PAD + (hasIcon ? ICON + GAP : 2) + textW + PAD);
        int h = PAD + Math.max(textH, hasIcon ? ICON : 0) + PAD;
        int x = (sw - w) / 2;

        int top = top(ctx);
        int y = top - Math.round((1 - slide(now)) * (h + top + 2));

        panel(ctx, x, y, w, h, Desafio.color);

        if (hasIcon) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + PAD, y + (h - ICON) / 2f, 0);
            ctx.getMatrices().scale(2f, 2f, 1f);
            ctx.drawItem(Desafio.icon, 0, 0);
            ctx.drawItemInSlot(tr, Desafio.icon, 0, 0);   // o número da pilha, no "quantos você tem?" (com 1 não desenha nada)
            ctx.getMatrices().pop();
        }

        int tx = x + PAD + (hasIcon ? ICON + GAP : 2), ty = y + PAD + Math.max(0, (hasIcon ? ICON : 0) - textH) / 2;
        ctx.drawTextWithShadow(tr, title, tx, ty, Desafio.color);
        ty += 11;
        if (!l1.isEmpty()) { ctx.drawTextWithShadow(tr, l1, tx, ty, WHITE); ty += 11; }
        if (!zh.isEmpty()) {
            ctx.drawTextWithShadow(tr, zhText, tx, ty, WHITE);
            if (pyW > 0 && pyBeside) ctx.drawTextWithShadow(tr, py, tx + zhW + 6, ty + 5, GOLD);
            ty += 19;
            if (pyW > 0 && !pyBeside) { ctx.drawTextWithShadow(tr, py, tx, ty, GOLD); ty += 11; }
        }
        if (cvN > 0) {
            for (int i = 0; i < cvN; i++) curve(ctx, tr, tx + i * (CW + CGAP), ty + 1, cv.syls().get(i), i == cvN - 1);
            int lx = tx + cvN * (CW + CGAP) + 4;
            ctx.fill(lx, ty + 6, lx + 8, ty + 7, SHAPE);
            ctx.drawTextWithShadow(tr, legA, lx + 11, ty + 2, DARK);
            ctx.fill(lx, ty + 16, lx + 8, ty + 18, WHITE);
            ctx.drawTextWithShadow(tr, legB, lx + 11, ty + 13, GRAY);
            ty += CH + 14;
        }
        if (!l2.isEmpty()) { ctx.drawTextWithShadow(tr, l2, tx, ty, GRAY); ty += 11; }
        if (!l3.isEmpty()) { ctx.drawTextWithShadow(tr, l3, tx, ty, DARK); ty += 11; }

        if (listening) {
            boolean hearing = Desafio.MIC.speechStarted, opening = Desafio.MIC.opening;
            ctx.drawTextWithShadow(tr, opening ? "Abrindo o microfone…" : hearing ? "♪ Ouvindo…" : "Fale agora", tx, ty, opening ? GRAY : hearing ? GREEN : WHITE);
            String secs = secondsLeft(now);
            ctx.drawTextWithShadow(tr, secs, tx + BAR - tr.getWidth(secs), ty, GRAY);
            bars(ctx, tx, ty + 11, BAR, now);
        }
    }

    /** a faixa por cima das telas: item, nome, 汉字 + pinyin e o tempo, numa linha só */
    private static void compact(DrawContext ctx) {
        Desafio.St st = Desafio.state;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        long now = System.currentTimeMillis();
        boolean listening = st == Desafio.St.LISTEN || st == Desafio.St.CALIBRATE;
        // no resultado o nome vem na cor dele (verde, amarelo, vermelho); "Analisando…" substitui o tempo
        String name = Desafio.line1.isEmpty() ? Desafio.title : Desafio.line1;
        String right = listening ? secondsLeft(now) : st == Desafio.St.THINK ? "…" : "";
        Text zhText = kai(Desafio.zh, KAI);
        int nameW = tr.getWidth(name), zhW = Desafio.zh.isEmpty() ? 0 : tr.getWidth(zhText), pyW = tr.getWidth(Desafio.py), rightW = tr.getWidth(right);
        int rowW = nameW + (zhW > 0 ? 8 + zhW : 0) + (pyW > 0 ? 4 + pyW : 0) + (rightW > 0 ? 10 + rightW : 0);
        int barW = listening ? Math.max(120, rowW) : 0;
        boolean hasIcon = !Desafio.icon.isEmpty();
        int sw = ctx.getScaledWindowWidth();
        int w = Math.min(sw - 8, 5 + (hasIcon ? 20 : 0) + Math.max(rowW, barW) + 5);
        int h = listening ? 33 : 22;
        int x = (sw - w) / 2, y = 2 - Math.round((1 - slide(now)) * (h + 4));

        panel(ctx, x, y, w, h, Desafio.color);
        int tx = x + 5;
        if (hasIcon) { ctx.drawItem(Desafio.icon, tx, y + 3); ctx.drawItemInSlot(tr, Desafio.icon, tx, y + 3); tx += 20; }
        int ty = y + 7;
        ctx.drawTextWithShadow(tr, name, tx, ty, Desafio.color == GRAY ? WHITE : Desafio.color);
        int cx = tx + nameW;
        if (zhW > 0) { ctx.drawTextWithShadow(tr, zhText, cx + 8, ty - 1, WHITE); cx += 8 + zhW; }
        if (pyW > 0) ctx.drawTextWithShadow(tr, Desafio.py, cx + 4, ty, GOLD);
        if (rightW > 0) ctx.drawTextWithShadow(tr, right, x + w - 5 - rightW, ty, GRAY);
        if (listening) bars(ctx, tx, y + 21, Math.min(barW, x + w - 5 - tx), now);
    }

    /**
     * O tom de uma sílaba: a forma certa em cinza e, por cima, a voz do jogador (verde se passou, vermelha se não).
     * A linha pontilhada do meio é a altura média da voz dele; a caixa vai de 8 semitons abaixo a 7 acima dela.
     */
    private static void curve(DrawContext ctx, TextRenderer tr, int x, int y, ToneCheck.SylResult s, boolean fin) {
        ctx.fill(x, y, x + CW, y + CH, 0x90000000);   // fundo escuro: as duas linhas têm que aparecer com qualquer paisagem atrás
        int mid = y + row(0);
        for (int i = 1; i < CW - 1; i += 3) ctx.fill(x + i, mid, x + i + 1, mid + 1, 0x40FFFFFF);
        boolean checked = s.expected() >= 1 && s.expected() <= 4;
        int prev = -1;
        if (checked) for (int i = 2; i < CW - 2; i++) {
            int r = y + row(ToneCheck.shape(s.expected(), fin, (i - 2) / (double) (CW - 5)));
            stroke(ctx, x + i, prev < 0 ? r : prev, r, 1, SHAPE);
            prev = r;
        }
        double[] c = s.curve();
        int color = !checked ? GRAY : s.ok() ? GREEN : RED;
        prev = -1;
        if (c.length >= 2) for (int i = 2; i < CW - 2; i++) {
            double p = (i - 2) / (double) (CW - 5) * (c.length - 1);
            int a = (int) p;
            double v = a + 1 < c.length ? c[a] + (c[a + 1] - c[a]) * (p - a) : c[c.length - 1];
            int r = y + row(v);
            stroke(ctx, x + i, prev < 0 ? r : prev, r, 2, color);
            prev = r;
        }
        String name = Pinyin.mark(s.syl().base(), s.syl().tone());
        ctx.drawTextWithShadow(tr, name, x + (CW - tr.getWidth(name)) / 2, y + CH + 2, color);
    }

    /** semitons em relação à voz média → linha dentro da caixinha (em cima = agudo) */
    private static int row(double st) {
        double v = Math.max(-8, Math.min(7, st));
        return 1 + (int) Math.round((7 - v) / 15.0 * (CH - 4));
    }

    /** um trecho da linha: liga a altura da coluna anterior à desta, para a curva não ficar picotada */
    private static void stroke(DrawContext ctx, int x, int from, int to, int thick, int color) {
        ctx.fill(x, Math.min(from, to), x + 1, Math.max(from, to) + thick, color);
    }

    private static String secondsLeft(long now) {
        return (Math.max(0, Desafio.deadline - now) + 999) / 1000 + " s";
    }

    /** tempo numa barra de chefe azul → amarela → vermelha (rosa na calibração); volume do microfone numa linha fina embaixo */
    private static void bars(DrawContext ctx, int x, int y, int width, long now) {
        boolean cal = Desafio.state == Desafio.St.CALIBRATE;
        long total = Math.max(1, Desafio.total);
        float frac = Math.min(1f, (float) Math.max(0, Desafio.deadline - now) / total);
        int c = cal ? 3 : frac > 0.4f ? 0 : frac > 0.2f ? 1 : 2;
        int bw = Math.min(BAR, width);   // a textura tem 182 de largura: mais curta, corta a ponta direita
        ctx.drawGuiTexture(BAR_BG[c], BAR, 5, 0, 0, x, y, bw, 5);
        int fill = Math.round(bw * frac);
        if (fill > 0) ctx.drawGuiTexture(BAR_FG[c], BAR, 5, 0, 0, x, y, fill, 5);
        boolean hearing = Desafio.MIC.speechStarted;
        ctx.fill(x, y + 7, x + bw, y + 9, 0x40FFFFFF);
        ctx.fill(x, y + 7, x + Math.round(bw * Desafio.MIC.level), y + 9, hearing ? GREEN : GRAY);
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
