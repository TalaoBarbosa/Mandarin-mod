package com.tales.mandarimcraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.tales.mandarimcraft.core.Counting;
import com.tales.mandarimcraft.core.Pinyin;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
    /** de quanto em quanto tempo de jogo (com o mod ligado) vem um desafio de quantidade: 20 min (menos que isso cansa numa sessão longa) */
    public static final int COUNT_EVERY_TICKS = 20 * 60 * 20;
    /** intervalo mínimo entre dois desafios que não são de item novo (revisão ou quantidade): 10 min de jogo */
    public static final int PERIODIC_GAP_TICKS = 10 * 60 * 20;
    /**
     * Revisão espaçada: quanto tempo de jogo depois de acertar a palavra volta (10 min, 30 min, 2 h, 6 h; daí em diante
     * a cada 6 h). Errou, volta para o começo. Só é pedida quando o item está no inventário: o contexto ajuda a lembrar.
     */
    private static final int[] REVIEW_MINUTES = {10, 30, 120, 360};
    /** a partir de quantas revisões seguidas certas a palavra conta como dominada (o dicionário mostra a estrela) */
    public static final int MASTERED_STAGE = REVIEW_MINUTES.length;
    /**
     * sem sinal do cliente por este tempo de servidor (20 s), o desafio é cancelado sem castigo. O cliente manda um sinal
     * a cada 2 s. Em ticks, não em relógio: com o jogo de um jogador só pausado o servidor para, e o tempo não corre.
     */
    private static final int ALIVE_TIMEOUT_TICKS = 400;

    private static WorldData data;
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    /** o desafio em aberto de um jogador; count = tamanho da pilha (quantidade e revisão) */
    private static final class Active {
        final String word, item;
        final int count, kind;
        int silentTicks = 0;
        Active(String word, String item, int count, int kind) { this.word = word; this.item = item; this.count = count; this.kind = kind; }
    }

    private static long reviewDelay(int stage) { return REVIEW_MINUTES[Math.max(0, Math.min(REVIEW_MINUTES.length - 1, stage))] * 1200L; }

    @Override
    public void onInitialize() {
        Vocab.load();
        PayloadTypeRegistry.playS2C().register(Payloads.Challenge.ID, Payloads.Challenge.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Cancel.ID, Payloads.Cancel.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Progress.ID, Payloads.Progress.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Answer.ID, Payloads.Answer.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Alive.ID, Payloads.Alive.CODEC);

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
            sendProgress(p, pd);
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
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Alive.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            context.server().execute(() -> {
                Active a = ACTIVE.get(p.getUuid());
                if (a != null && a.word.equals(payload.word())) a.silentTicks = 0;
            });
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
        if (!off) { pd.countTicks += 5; pd.playTicks += 5; }
        if (a != null) {
            // O cliente avisa a cada 2 s que o desafio continua (na tela, ou esperando um momento calmo). Antes o limite
            // era contado desde a chegada do item: quem minerava 2 min antes de parar tinha o desafio cortado no meio da fala.
            a.silentTicks += 5;
            if (off || a.silentTicks > ALIVE_TIMEOUT_TICKS || countWord(p, a.word) == 0) {
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
            // palavra que ele ainda não acertou: sempre com a dica inteira (errar não pode tirar o pinyin)
            challenge(p, w, s, 0, s.getCount(), Payloads.NEW);
            return;
        }
        // nenhum item novo: de 10 em 10 min, no máximo, uma revisão ou (a cada 20 min) um "quantos você tem?"
        if (pd.playTicks - pd.lastPeriodicTick < PERIODIC_GAP_TICKS) return;
        if (pd.countTicks >= COUNT_EVERY_TICKS) {
            int slot = pickCountSlot(p, pd);
            if (slot >= 0) {   // sem pilha boa agora: pergunta assim que tiver
                ItemStack s = inv.getStack(slot);
                Vocab.Word w = Vocab.forStack(s);
                challenge(p, w, s, pd.countLevel.getOrDefault(w.key, 0), s.getCount(), Payloads.COUNT);
                pd.countTicks = 0;
                pd.lastPeriodicTick = pd.playTicks;
                return;
            }
        }
        int slot = pickReviewSlot(p, pd);
        if (slot >= 0) {
            ItemStack s = inv.getStack(slot);
            Vocab.Word w = Vocab.forStack(s);
            challenge(p, w, s, reviewOf(pd, w.key).level, s.getCount(), Payloads.REVIEW);
            pd.lastPeriodicTick = pd.playTicks;
        }
    }

    private static void challenge(ServerPlayerEntity p, Vocab.Word w, ItemStack s, int level, int count, int kind) {
        String item = Registries.ITEM.getId(s.getItem()).toString();
        ACTIVE.put(p.getUuid(), new Active(w.key, item, count, kind));
        ServerPlayNetworking.send(p, new Payloads.Challenge(w.key, item, SECONDS, level, count, kind));
    }

    /** a revisão de uma palavra aprendida; quem aprendeu antes de existir revisão começa agora (a 1ª vem em 10 min de jogo) */
    private static WorldData.Review reviewOf(WorldData.PlayerData pd, String key) {
        return pd.review.computeIfAbsent(key, k -> { WorldData.Review r = new WorldData.Review(); r.due = pd.playTicks + reviewDelay(0); return r; });
    }

    /** o item do inventário cuja palavra está há mais tempo esperando revisão; -1 se nenhuma está na hora */
    private static int pickReviewSlot(ServerPlayerEntity p, WorldData.PlayerData pd) {
        PlayerInventory inv = p.getInventory();
        int best = -1; long bestDue = Long.MAX_VALUE;
        for (int i = 0; i < inv.size(); i++) {
            Vocab.Word w = Vocab.forStack(inv.getStack(i));
            if (w == null || !pd.learned.containsKey(w.key)) continue;
            long due = reviewOf(pd, w.key).due;
            if (due <= pd.playTicks && due < bestDue) { best = i; bestDue = due; }
        }
        return best;
    }

    /** uma pilha de 2 ou mais de algo que ele já aprendeu, sorteada (ferramenta e armadura não empilham); -1 se não tem */
    private static int pickCountSlot(ServerPlayerEntity p, WorldData.PlayerData pd) {
        PlayerInventory inv = p.getInventory();
        List<Integer> ok = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getCount() < 2) continue;
            Vocab.Word w = Vocab.forStack(s);
            if (w != null && pd.learned.containsKey(w.key)) ok.add(i);
        }
        return ok.isEmpty() ? -1 : ok.get(p.getRandom().nextInt(ok.size()));
    }

    private static void onAnswer(ServerPlayerEntity p, Payloads.Answer ans) {
        Active a = ACTIVE.get(p.getUuid());
        if (a == null || !a.word.equals(ans.word())) return;
        ACTIVE.remove(p.getUuid());
        Vocab.Word w = Vocab.get(a.word);
        if (w == null) return;
        WorldData.PlayerData pd = data.get(p.getUuid());
        if ("__mic__".equals(ans.heard())) {   // microfone ou reconhecimento de voz falhou: não é culpa do jogador, pausa sem tirar nada
            pd.paused = true; data.save();
            p.sendMessage(Text.literal("[Mandarim Craft] O microfone ou o reconhecimento de voz não funcionou, então pausei (sem perder nada). Resolva (/hanyu ajustes mostra o microfone) e use /mandarim continuar.").formatted(Formatting.YELLOW), false);
            sendProgress(p, pd);
            return;
        }
        if (a.kind == Payloads.COUNT) { onCountAnswer(p, pd, w, a, ans.ok()); sendProgress(p, pd); return; }
        if (a.kind == Payloads.REVIEW) { onReviewAnswer(p, pd, w, a, ans.ok()); sendProgress(p, pd); return; }
        if (ans.ok()) {
            pd.learned.put(w.key, System.currentTimeMillis());
            pd.grace.remove(w.key);
            pd.review.remove(w.key);
            reviewOf(pd, w.key);   // a 1ª revisão vem daqui a 10 min de jogo
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
        sendProgress(p, pd);
    }

    /**
     * resposta da revisão. Acertou: a próxima demora mais e vem com menos dica. Errou: a dica volta um passo, a revisão
     * recomeça do intervalo curto e, de castigo, some 1/4 da pilha (arredondado para cima). Item único (ferramenta,
     * armadura) não some na hora: a palavra volta a ser nova e é pedida de novo com o pinyin, aí sim valendo o item.
     */
    private static void onReviewAnswer(ServerPlayerEntity p, WorldData.PlayerData pd, Vocab.Word w, Active a, boolean ok) {
        WorldData.Review r = reviewOf(pd, w.key);
        Text said = Text.literal(w.zh + " " + w.pretty()).formatted(Formatting.WHITE);
        Text what = Text.literal("  (" + w.pt + ")").formatted(Formatting.GRAY);
        if (ok) {
            r.stage++;
            r.level = Math.min(2, r.level + 1);
            r.due = pd.playTicks + reviewDelay(r.stage);
            data.save();
            p.sendMessage(Text.literal("✓ Revisão: ").formatted(Formatting.GREEN).append(said).append(what)
                    .append(Text.literal(r.stage == MASTERED_STAGE ? " ★ dominada" : "").formatted(Formatting.GOLD)), false);
            return;
        }
        pd.fails.merge(w.key, 1, Integer::sum);
        MutableText t = Text.literal("✗ Revisão: ").formatted(Formatting.RED).append(Text.literal("era ").formatted(Formatting.WHITE)).append(said).append(what);
        if (a.count >= 2) {
            r.stage = 0;
            r.level = Math.max(0, r.level - 1);
            r.due = pd.playTicks + reviewDelay(0);
            int lost = removeSome(p, a.item, (a.count + 3) / 4);
            pd.itemsLost += lost;
            if (lost > 0) t.append(Text.literal(" — perdeu " + lost + " de " + a.count + ".").formatted(Formatting.RED));
        } else {
            pd.learned.remove(w.key);
            pd.review.remove(w.key);
            t.append(Text.literal(" — voltou a ser palavra nova: vai ser pedida de novo, com o pinyin.").formatted(Formatting.YELLOW));
        }
        data.save();
        p.sendMessage(t, false);
    }

    /** manda para o cliente o que o jogador já sabe (o dicionário mostra) */
    private static void sendProgress(ServerPlayerEntity p, WorldData.PlayerData pd) {
        if (!ServerPlayNetworking.canSend(p, Payloads.Progress.ID)) return;
        Map<String, Integer> learned = new HashMap<>();
        for (String k : pd.learned.keySet()) { WorldData.Review r = pd.review.get(k); learned.put(k, r == null ? 0 : r.stage); }
        ServerPlayNetworking.send(p, new Payloads.Progress(pd.started && !pd.paused, learned, new HashMap<>(pd.fails)));
    }

    /** resposta do "quantos você tem?": errou, perde 1/4 daquela pilha, arredondado para cima (não tudo, como na palavra nova) */
    private static void onCountAnswer(ServerPlayerEntity p, WorldData.PlayerData pd, Vocab.Word w, Active a, boolean ok) {
        Vocab.Alt mw = w.mw.get(0);
        String said = Counting.zhCount(a.count) + mw.zh() + w.zh + " " + Pinyin.pretty(Counting.pyCount(a.count) + " " + mw.py() + " " + w.py);
        Text what = Text.literal(" (" + a.count + " × " + w.pt + ")").formatted(Formatting.GRAY);
        // a dica diminui um passo quando acerta e volta um passo quando erra
        pd.countLevel.put(w.key, Math.max(0, Math.min(2, pd.countLevel.getOrDefault(w.key, 0) + (ok ? 1 : -1))));
        if (ok) {
            data.save();
            p.sendMessage(Text.literal("✓ ").formatted(Formatting.GREEN).append(Text.literal(said).formatted(Formatting.WHITE)).append(what), false);
            return;
        }
        int lost = removeSome(p, a.item, (a.count + 3) / 4);
        pd.itemsLost += lost;
        data.save();
        MutableText t = Text.literal("✗ ").formatted(Formatting.RED).append(Text.literal("Era " + said).formatted(Formatting.WHITE)).append(what);
        if (lost > 0) t.append(Text.literal(" — perdeu " + lost + " de " + a.count + ".").formatted(Formatting.RED));
        p.sendMessage(t, false);
    }

    /** tira até k deste item do inventário do jogador (e do cursor); devolve quantos tirou */
    private static int removeSome(ServerPlayerEntity p, String itemId, int k) {
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size() && n < k; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(item)) continue;
            int take = Math.min(k - n, s.getCount());
            s.decrement(take);
            n += take;
            if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
        }
        ItemStack cur = p.currentScreenHandler.getCursorStack();
        if (n < k && cur.isOf(item)) { int take = Math.min(k - n, cur.getCount()); cur.decrement(take); n += take; }
        inv.markDirty();
        p.currentScreenHandler.sendContentUpdates();
        return n;
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
                "A cada 20 min: \"quantos você tem?\" (número + classificador: 六十四根木头); errou, perde 1/4 da pilha.\n" +
                "Palavra aprendida volta em revisão (10 min, 30 min, 2 h, 6 h de jogo), com menos dica; errou, perde 1/4 da pilha.\n" +
                "No seu jogo: tecla H = dicionário · /hanyu ajustes (microfone e opções) · calibrar · teste · ouvir <palavra>"), false);
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
        sendProgress(p, pd);
        c.getSource().sendFeedback(() -> Text.literal(on
                ? "Mandarim Craft ativado. Itens novos no inventário viram desafio. Dica: faça /hanyu calibrar antes (lê três frases curtas para o mod conhecer a sua voz)."
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

    /** sugere as palavras que o jogador já aprendeu ou já errou (em 汉字) e "tudo" */
    private static CompletableFuture<Suggestions> suggestLearned(CommandContext<ServerCommandSource> c, SuggestionsBuilder b) {
        Set<String> opts = new LinkedHashSet<>();
        ServerPlayerEntity p = c.getSource().getPlayer();
        if (p != null && data != null) {
            WorldData.PlayerData pd = data.get(p.getUuid());
            for (String k : pd.learned.keySet()) { Vocab.Word w = Vocab.get(k); if (w != null) opts.add(w.zh); }
            for (String k : pd.fails.keySet()) { Vocab.Word w = Vocab.get(k); if (w != null) opts.add(w.zh); }
        }
        opts.add("tudo");
        return CommandSource.suggestMatching(opts, b);
    }

    /** a palavra volta a ser nova: pedida de novo e, com a dica automática, com o pinyin de novo (zera os erros também) */
    private static int cmdForget(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        WorldData.PlayerData pd = data.get(p.getUuid());
        String arg = StringArgumentType.getString(c, "palavra").trim();
        int n;
        if (arg.equals("tudo")) {
            Set<String> all = new HashSet<>(pd.learned.keySet()); all.addAll(pd.fails.keySet());
            n = all.size(); pd.learned.clear(); pd.fails.clear(); pd.countLevel.clear(); pd.review.clear();
        } else {
            n = 0;
            for (Vocab.Word w : Vocab.all()) if (w.key.equals(arg) || w.zh.equals(arg)) {
                boolean had = pd.learned.remove(w.key) != null;
                if (pd.fails.remove(w.key) != null) had = true;
                pd.countLevel.remove(w.key);
                pd.review.remove(w.key);
                if (had) n++;
            }
        }
        data.save();
        sendProgress(p, pd);
        final int fn = n;
        c.getSource().sendFeedback(() -> Text.literal(fn + (fn == 1 ? " palavra esquecida." : " palavras esquecidas.")), false);
        return 1;
    }
}
