package com.tales.mandarimcraft.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.text.Normalizer;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Lado do cliente: microfone, reconhecimento, tela do desafio e o comando /hanyu. */
public class MandarimClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Vocab.load();
        ClientConfig.get();
        Desafio.WORK.submit(Speech::load);   // carrega o modelo de voz em segundo plano

        ClientPlayNetworking.registerGlobalReceiver(Payloads.Challenge.ID, (payload, context) -> context.client().execute(() -> Desafio.onChallenge(payload)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Cancel.ID, (payload, context) -> context.client().execute(() -> Desafio.onCancel(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Desafio::reset));
        ClientTickEvents.END_CLIENT_TICK.register(Desafio::tick);
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> { if (MinecraftClient.getInstance().currentScreen == null) Hud.render(ctx); });
        // com inventário ou baú aberto (tirar item de um baú é o jeito mais comum de começar um desafio) o HUD fica
        // escurecido atrás da tela: aí o quadro é desenhado por cima dela
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> Hud.renderOnTop(ctx)));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("hanyu")
                        .executes(c -> {
                            ClientConfig k = ClientConfig.get();
                            c.getSource().sendFeedback(Text.literal("汉语: tons " + k.tones + " · tempo " + k.seconds + " s · dica " + k.hint + " · chances extras: " + k.retries +
                                    " · voz " + (k.refHz > 0 ? Math.round(k.refHz) + " Hz" : "não calibrada") + " · reconhecimento: " + Speech.status() +
                                    "\n/hanyu calibrar · teste · ouvir <palavra> · tons rigido|normal|leve · tempo <s> · dica auto|nenhuma|hanzi|pinyin · chances <0-2>").formatted(Formatting.GRAY));
                            return 1;
                        })
                        .then(literal("calibrar").executes(c -> busy(c, Desafio.startCalibration())))
                        .then(literal("teste").executes(c -> busy(c, Desafio.startPractice())))
                        .then(literal("ouvir").then(argument("palavra", StringArgumentType.greedyString()).executes(c -> {
                            String a = plain(StringArgumentType.getString(c, "palavra"));
                            // primeiro o nome exato (汉字, pinyin com ou sem acento, português), depois o começo do nome em português
                            Vocab.Word found = null;
                            for (Vocab.Word w : Vocab.all()) if (a.equals(w.key) || a.equals(w.zh) || a.equals(plain(w.pretty()).replace(" ", "")) || a.equals(plain(w.pt))) { found = w; break; }
                            if (found == null) for (Vocab.Word w : Vocab.all()) if (plain(w.pt).startsWith(a)) { found = w; break; }
                            if (found == null) { c.getSource().sendError(Text.literal("Não achei essa palavra.")); return 0; }
                            Desafio.playWord(found.key);
                            c.getSource().sendFeedback(Text.literal(found.zh + " " + found.pretty() + " — " + found.pt));
                            return 1;
                        })))
                        .then(literal("tons").then(argument("nivel", StringArgumentType.word())
                                .suggests((c, b) -> { b.suggest("rigido"); b.suggest("normal"); b.suggest("leve"); return b.buildFuture(); })
                                .executes(c -> {
                                    String v = StringArgumentType.getString(c, "nivel");
                                    if (!v.equals("rigido") && !v.equals("normal") && !v.equals("leve")) { c.getSource().sendError(Text.literal("Use rigido, normal ou leve.")); return 0; }
                                    ClientConfig.get().tones = v; ClientConfig.get().save();
                                    c.getSource().sendFeedback(Text.literal("Tons: " + v)); return 1;
                                })))
                        .then(literal("tempo").then(argument("segundos", IntegerArgumentType.integer(3, 20)).executes(c -> {
                            ClientConfig.get().seconds = IntegerArgumentType.getInteger(c, "segundos"); ClientConfig.get().save();
                            c.getSource().sendFeedback(Text.literal("Tempo para falar: " + ClientConfig.get().seconds + " s")); return 1;
                        })))
                        .then(literal("chances").then(argument("n", IntegerArgumentType.integer(0, 2)).executes(c -> {
                            ClientConfig.get().retries = IntegerArgumentType.getInteger(c, "n"); ClientConfig.get().save();
                            c.getSource().sendFeedback(Text.literal("Chances extras quando erra: " + ClientConfig.get().retries)); return 1;
                        })))
                        .then(literal("dica").then(argument("tipo", StringArgumentType.word())
                                .suggests((c, b) -> { b.suggest("auto"); b.suggest("nenhuma"); b.suggest("hanzi"); b.suggest("pinyin"); return b.buildFuture(); })
                                .executes(c -> {
                                    String v = StringArgumentType.getString(c, "tipo");
                                    if (!v.equals("auto") && !v.equals("nenhuma") && !v.equals("hanzi") && !v.equals("pinyin")) { c.getSource().sendError(Text.literal("Use auto, nenhuma, hanzi ou pinyin.")); return 0; }
                                    ClientConfig.get().hint = v; ClientConfig.get().save();
                                    c.getSource().sendFeedback(Text.literal("Dica no desafio: " + v + (v.equals("auto") ? " (pinyin nas palavras novas; some conforme você erra a mesma palavra)" : ""))); return 1;
                                })))));
    }

    private static int busy(CommandContext<FabricClientCommandSource> c, boolean started) {
        if (!started) c.getSource().sendError(Text.literal("Espere o desafio de agora terminar."));
        return started ? 1 : 0;
    }

    /** minúsculo, sem acento e sem espaço nas pontas: "Baú" = "bau", "mùtou" = "mutou" */
    static String plain(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
