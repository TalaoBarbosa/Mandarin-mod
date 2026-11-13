package com.tales.mandarimcraft.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Lado do cliente: microfone, reconhecimento, tela do desafio, dicionário, ajustes, teclas e o comando /hanyu. */
public class MandarimClient implements ClientModInitializer {
    private static final String KEYS = "key.categories.mandarimcraft";
    private static KeyBinding keyDict, keyReplay, keySettings;

    @Override
    public void onInitializeClient() {
        Vocab.load();
        ClientConfig.get();
        Desafio.WORK.submit(Speech::load);   // carrega o modelo de voz em segundo plano

        ClientPlayNetworking.registerGlobalReceiver(Payloads.Challenge.ID, (payload, context) -> context.client().execute(() -> Desafio.onChallenge(payload)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Cancel.ID, (payload, context) -> context.client().execute(() -> Desafio.onCancel(payload)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Progress.ID, (payload, context) -> context.client().execute(() -> Progresso.set(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { Desafio.reset(); Progresso.clear(); }));
        ClientTickEvents.END_CLIENT_TICK.register(Desafio::tick);

        // teclas (dá para trocar em Opções → Controles → Mandarim Craft)
        keyDict = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mandarimcraft.dicionario", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, KEYS));
        keyReplay = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mandarimcraft.ouvir", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, KEYS));
        keySettings = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mandarimcraft.ajustes", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEYS));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyDict.wasPressed()) if (client.currentScreen == null) client.setScreen(new DicionarioScreen(null));
            while (keySettings.wasPressed()) if (client.currentScreen == null) client.setScreen(new AjustesScreen(null));
            while (keyReplay.wasPressed()) {
                if (!Desafio.replay() && client.player != null) client.player.sendMessage(Text.literal(
                        Desafio.lastKey == null ? "汉语: ainda não teve palavra para ouvir de novo." : "汉语: espere o desafio terminar para ouvir.").formatted(Formatting.GRAY), true);
            }
        });
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> { if (MinecraftClient.getInstance().currentScreen == null) Hud.render(ctx); });
        // 汉字 + pinyin na dica do item: dá para ver (e ouvir com /hanyu ouvir) a palavra antes do desafio
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ClientConfig.get().names || lines.isEmpty()) return;
            Vocab.Word w = Vocab.forStack(stack);
            if (w == null || (Desafio.state != Desafio.St.IDLE && w == Desafio.word)) return;   // não entrega a resposta do desafio aberto
            lines.add(1, Text.empty().append(Hud.kai(w.zh, Hud.KAI).formatted(Formatting.GOLD)).append(Text.literal("  " + w.pretty()).formatted(Formatting.GRAY)));
        });
        // com inventário ou baú aberto (tirar item de um baú é o jeito mais comum de começar um desafio) o HUD fica
        // escurecido atrás da tela: aí o quadro é desenhado por cima dela
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> Hud.renderOnTop(ctx)));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("hanyu")
                        .executes(c -> {
                            ClientConfig k = ClientConfig.get();
                            String ver = FabricLoader.getInstance().getModContainer(MandarimCraft.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
                            c.getSource().sendFeedback(Text.literal("汉语 (Mandarim Craft " + ver + "): tons " + k.tones + " · tempo " + k.seconds + " s · dica " + k.hint + " · chances extras: " + k.retries + " · nomes nos itens: " + (k.names ? "sim" : "nao") +
                                    " · microfone: " + (k.mic.isEmpty() ? "padrão do sistema" : k.mic) +
                                    " · voz " + (k.refHz > 0 ? Math.round(k.refHz) + " Hz" : "não calibrada") + " · reconhecimento: " + Speech.status() +
                                    "\n/hanyu ajustes (microfone e opções) · dicionario (tecla " + keyDict.getBoundKeyLocalizedText().getString() + ") · calibrar · teste · ouvir <palavra>" +
                                    "\n/hanyu tons rigido|normal|leve · tempo <s> · dica auto|nenhuma|hanzi|pinyin · nomes sim|nao · chances <0-2>").formatted(Formatting.GRAY));
                            return 1;
                        })
                        // a tela do chat fecha depois do comando: abrir a nossa tem que ficar para o passo seguinte
                        .then(literal("ajustes").executes(c -> { MinecraftClient mc = c.getSource().getClient(); mc.send(() -> mc.setScreen(new AjustesScreen(null))); return 1; }))
                        .then(literal("dicionario").executes(c -> { MinecraftClient mc = c.getSource().getClient(); mc.send(() -> mc.setScreen(new DicionarioScreen(null))); return 1; }))
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
                        .then(literal("nomes").then(argument("sim_ou_nao", StringArgumentType.word())
                                .suggests((c, b) -> { b.suggest("sim"); b.suggest("nao"); return b.buildFuture(); })
                                .executes(c -> {
                                    String v = StringArgumentType.getString(c, "sim_ou_nao");
                                    if (!v.equals("sim") && !v.equals("nao")) { c.getSource().sendError(Text.literal("Use sim ou nao.")); return 0; }
                                    ClientConfig.get().names = v.equals("sim"); ClientConfig.get().save();
                                    c.getSource().sendFeedback(Text.literal("Nome em chinês na dica dos itens: " + v)); return 1;
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
                                    c.getSource().sendFeedback(Text.literal("Dica no desafio: " + v + (v.equals("auto") ? " (pinyin até você acertar a palavra; na revisão e na quantidade some conforme você acerta)" : ""))); return 1;
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
