package com.tales.mandarimcraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Lado do servidor (também roda no mundo de um jogador só): vê quando entra um item novo no inventário,
 * pede para o cliente ouvir a palavra e, se errou, tira o item.
 */
public class MandarimCraft implements ModInitializer {
    public static final String MOD_ID = "mandarimcraft";
    public static final Logger LOG = LoggerFactory.getLogger("Mandarim Craft");

    /** segundos para falar (o cliente usa o próprio ajuste; este é o padrão do servidor) */
    public static final int SECONDS = 7;

    private static WorldData data;
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private record Active(String word, String item, long startedMs) {}

    @Override
    public void onInitialize() {
        Vocab.load();
        PayloadTypeRegistry.playS2C().register(Payloads.Challenge.ID, Payloads.Challenge.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Cancel.ID, Payloads.Cancel.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Answer.ID, Payloads.Answer.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            data = WorldData.load(server.getSavePath(WorldSavePath.ROOT).resolve("mandarimcraft.json"));
            ACTIVE.clear();
            Vocab.clearCache();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, rm, ok) -> Vocab.clearCache());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (data != null) data.save(); ACTIVE.clear(); });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            if (data == null) return;
            WorldData.PlayerData pd = data.get(p.getUuid());
            if (!pd.started) {
                p.sendMessage(Text.literal("[Mandarim Craft] ").formatted(Formatting.GOLD)
                        .append(Text.literal("Quando um item novo entrar no inventário, diga o nome dele em chinês, com os tons certos, antes do tempo acabar. Errou: perde o item. ").formatted(Formatting.WHITE))
                        .append(Text.literal("Para ativar neste mundo: /mandarim comecar").formatted(Formatting.YELLOW)), false);
            } else if (pd.paused) {
                p.sendMessage(Text.literal("[Mandarim Craft] pausado neste mundo. /mandarim continuar para voltar.").formatted(Formatting.GRAY), false);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE.remove(handler.getPlayer().getUuid()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (data == null || server.getTicks() % 5 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) tick(p);
        });

        ServerPlayNetworking.registerGlobalReceiver(Payloads.Answer.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            context.server().execute(() -> onAnswer(p, payload));
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("mandarim")
                        .executes(MandarimCraft::cmdHelp)
                        .then(CommandManager.literal("comecar").executes(c -> cmdStart(c, true)))
                        .then(CommandManager.literal("continuar").executes(c -> cmdStart(c, true)))
                        .then(CommandManager.literal("pausar").executes(c -> cmdStart(c, false)))
                        .then(CommandManager.literal("palavras").executes(MandarimCraft::cmdList))
                        .then(CommandManager.literal("faltam").executes(MandarimCraft::cmdMissing))
                        .then(CommandManager.literal("esquecer")
                                // greedyString: word() só aceita a-z, 0-9 e _-.+, então 钻石 nem chegava ao comando
                                .then(CommandManager.argument("palavra", StringArgumentType.greedyString())
                                        .suggests(MandarimCraft::suggestLearned)
                                        .executes(MandarimCraft::cmdForget)))));
        LOG.info("Mandarim Craft pronto");
    }

    // ---------------------------------------------------------------- desafio

    private static void tick(ServerPlayerEntity p) {
        WorldData.PlayerData pd = data.get(p.getUuid());
        Active a = ACTIVE.get(p.getUuid());
        boolean off = pd.paused || p.isCreative() || p.isSpectator() || !ServerPlayNetworking.canSend(p, Payloads.Challenge.ID);
        if (a != null) {
            long limit = 90_000L;   // tempo do cliente (com calibração e segunda tentativa) + folga; sem resposta: cancela sem castigo
            if (off || System.currentTimeMillis() - a.startedMs > limit || countWord(p, a.word) == 0) {
                ACTIVE.remove(p.getUuid());
                if (ServerPlayNetworking.canSend(p, Payloads.Cancel.ID)) ServerPlayNetworking.send(p, new Payloads.Cancel(a.word));
            }
            return;
        }
        if (off) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            Vocab.Word w = Vocab.forStack(s);
            if (w == null || pd.learned.containsKey(w.key)) continue;
            String item = Registries.ITEM.getId(s.getItem()).toString();
            ACTIVE.put(p.getUuid(), new Active(w.key, item, System.currentTimeMillis()));
            ServerPlayNetworking.send(p, new Payloads.Challenge(w.key, item, SECONDS));
            return;
        }
    }

    private static void onAnswer(ServerPlayerEntity p, Payloads.Answer ans) {
        Active a = ACTIVE.get(p.getUuid());
        if (a == null || !a.word.equals(ans.word())) return;
        ACTIVE.remove(p.getUuid());
        Vocab.Word w = Vocab.get(a.word);
        if (w == null) return;
        WorldData.PlayerData pd = data.get(p.getUuid());
        if ("__mic__".equals(ans.heard())) {   // microfone não abriu: não é culpa do jogador, pausa sem tirar nada
            pd.paused = true; data.save();
            p.sendMessage(Text.literal("[Mandarim Craft] O microfone não funcionou, então pausei (sem perder nada). Resolva e use /mandarim continuar.").formatted(Formatting.YELLOW), false);
            return;
        }
        if (ans.ok()) {
            pd.learned.put(w.key, System.currentTimeMillis());
            pd.grace.remove(w.key);
            data.save();
            p.sendMessage(Text.literal("✓ ").formatted(Formatting.GREEN)
                    .append(Text.literal(w.zh + " " + w.pretty()).formatted(Formatting.WHITE))
                    .append(Text.literal("  (" + w.pt + ") — " + pd.learned.size() + " palavras").formatted(Formatting.GRAY)), false);
        } else {
            boolean grace = pd.grace.remove(w.key);
            int lost = grace ? 0 : removeWord(p, w.key);
            pd.fails.merge(w.key, 1, Integer::sum);
            pd.itemsLost += lost;
            data.save();
            MutableText t = Text.literal("✗ ").formatted(Formatting.RED)
                    .append(Text.literal("Era " + w.zh + " " + w.pretty()).formatted(Formatting.WHITE))
                    .append(Text.literal(" (" + w.pt + ")").formatted(Formatting.GRAY));
            if (lost > 0) t.append(Text.literal(" — perdeu " + lost + (lost == 1 ? " item" : " itens") + ". Pegue de novo para tentar.").formatted(Formatting.RED));
            else if (grace) t.append(Text.literal(" — item que você já tinha: dessa vez não perdeu.").formatted(Formatting.YELLOW));
            p.sendMessage(t, false);
        }
    }

    /** quantos itens desta palavra o jogador tem (inventário + o que está no cursor) */
    private static int countWord(ServerPlayerEntity p, String key) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            Vocab.Word w = Vocab.forStack(inv.getStack(i));
            if (w != null && w.key.equals(key)) n += inv.getStack(i).getCount();
        }
        ItemStack cur = p.currentScreenHandler.getCursorStack();
        Vocab.Word wc = Vocab.forStack(cur);
        if (wc != null && wc.key.equals(key)) n += cur.getCount();
        return n;
    }

    /** errou: some todo item desta palavra (só do inventário do jogador, nunca de baús) */
    private static int removeWord(ServerPlayerEntity p, String key) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            Vocab.Word w = Vocab.forStack(s);
            if (w != null && w.key.equals(key)) { n += s.getCount(); inv.setStack(i, ItemStack.EMPTY); }
        }
        ItemStack cur = p.currentScreenHandler.getCursorStack();
        Vocab.Word wc = Vocab.forStack(cur);
        if (wc != null && wc.key.equals(key)) { n += cur.getCount(); p.currentScreenHandler.setCursorStack(ItemStack.EMPTY); }
        inv.markDirty();
        p.currentScreenHandler.sendContentUpdates();
        return n;
    }

    // ---------------------------------------------------------------- comandos

    private static int cmdHelp(CommandContext<ServerCommandSource> c) {
        c.getSource().sendFeedback(() -> Text.literal(
                "Mandarim Craft: /mandarim comecar · pausar · continuar · palavras (o que já aprendeu) · faltam (o que falta) · esquecer <palavra|tudo>\n" +
                "No seu jogo: /hanyu calibrar · teste · tons rigido|normal|leve · tempo <segundos> · dica nenhuma|hanzi|pinyin"), false);
        return 1;
    }

    private static int cmdStart(CommandContext<ServerCommandSource> c, boolean on) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        WorldData.PlayerData pd = data.get(p.getUuid());
        if (on && !pd.started) {
            // o que já estava no inventário na hora de começar também vira desafio, mas errar não tira esses itens (só os novos)
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.size(); i++) { Vocab.Word w = Vocab.forStack(inv.getStack(i)); if (w != null && !pd.learned.containsKey(w.key)) pd.grace.add(w.key); }
            pd.started = true;
        }
        pd.paused = !on;
        data.save();
        if (!on) {
            Active a = ACTIVE.remove(p.getUuid());
            if (a != null) ServerPlayNetworking.send(p, new Payloads.Cancel(a.word));
        }
        c.getSource().sendFeedback(() -> Text.literal(on
                ? "Mandarim Craft ativado. Itens novos no inventário viram desafio. Dica: faça /hanyu calibrar antes (lê uma frase para o mod conhecer a sua voz)."
                : "Mandarim Craft pausado. /mandarim continuar para voltar.").formatted(on ? Formatting.GREEN : Formatting.GRAY), false);
        return 1;
    }

    private static int cmdList(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        WorldData.PlayerData pd = data.get(p.getUuid());
        if (pd.learned.isEmpty()) { c.getSource().sendFeedback(() -> Text.literal("Nenhuma palavra ainda."), false); return 1; }
        MutableText t = Text.literal("Palavras aprendidas (" + pd.learned.size() + " de " + Vocab.all().size() + "):\n").formatted(Formatting.GOLD);
        for (String k : pd.learned.keySet()) {
            Vocab.Word w = Vocab.get(k);
            if (w != null) t.append(Text.literal(w.zh + " " + w.pretty()).formatted(Formatting.WHITE)).append(Text.literal(" " + w.pt + "   ").formatted(Formatting.GRAY));
        }
        c.getSource().sendFeedback(() -> t, false);
        return 1;
    }

    private static int cmdMissing(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        WorldData.PlayerData pd = data.get(p.getUuid());
        List<String> miss = new ArrayList<>();
        for (Vocab.Word w : Vocab.all()) if (!pd.learned.containsKey(w.key)) miss.add(w.pt);
        c.getSource().sendFeedback(() -> Text.literal("Faltam " + miss.size() + ": " + String.join(", ", miss)).formatted(Formatting.GRAY), false);
        return 1;
    }

    /** sugere as palavras que o jogador já aprendeu (em 汉字) e "tudo" */
    private static CompletableFuture<Suggestions> suggestLearned(CommandContext<ServerCommandSource> c, SuggestionsBuilder b) {
        List<String> opts = new ArrayList<>();
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p != null && data != null) for (String k : data.get(p.getUuid()).learned.keySet()) {
            Vocab.Word w = Vocab.get(k);
            if (w != null) opts.add(w.zh);
        }
        opts.add("tudo");
        return CommandSource.suggestMatching(opts, b);
    }

    private static int cmdForget(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        WorldData.PlayerData pd = data.get(p.getUuid());
        String arg = StringArgumentType.getString(c, "palavra").trim();
        int n;
        if (arg.equals("tudo")) { n = pd.learned.size(); pd.learned.clear(); }
        else {
            n = 0;
            for (Vocab.Word w : Vocab.all()) if (w.key.equals(arg) || w.zh.equals(arg)) { if (pd.learned.remove(w.key) != null) n++; }
        }
        data.save();
        final int fn = n;
        c.getSource().sendFeedback(() -> Text.literal(fn + (fn == 1 ? " palavra esquecida." : " palavras esquecidas.")), false);
        return 1;
    }
}
