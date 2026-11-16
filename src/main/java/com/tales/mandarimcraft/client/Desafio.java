package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.core.Answer;
import com.tales.mandarimcraft.core.Counting;
import com.tales.mandarimcraft.core.Pinyin;
import com.tales.mandarimcraft.core.ToneCheck;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** O desafio na tela: ouvir, analisar (fora da thread do jogo) e mostrar o resultado. */
public final class Desafio {
    private Desafio() {}

    public enum St { IDLE, WAIT_MODEL, LISTEN, THINK, RETRY_WAIT, SHOW }

    static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "mandarimcraft-voz"); t.setDaemon(true); return t; });
    static final Mic MIC = new Mic();

    // estado (só mexido na thread do jogo)
    static St state = St.IDLE;
    static Payloads.Challenge cur, pending;
    static Vocab.Word word;
    static ItemStack icon = ItemStack.EMPTY;
    static boolean practice;
    static int attempt;
    /** o tipo do desafio de agora: Payloads.NEW (item novo), COUNT (quantos você tem) ou REVIEW (palavra já aprendida) */
    static int kind;
    /** o tamanho da pilha do desafio: na quantidade é a resposta ("六十四根木头"); na revisão, o que está em jogo */
    static int count;
    /** a dica de classificador (个 no lugar de 根, 二 no lugar de 两) já foi dada neste desafio: ela não gasta tentativa, mas só uma vez */
    static boolean tipGiven;
    /** já aconteceu neste desafio de ele dizer a palavra e não dar para medir o tom: a 1ª vez não gasta tentativa */
    static boolean blindGiven;
    /** quantas vezes a análise deu erro interno neste desafio (não conta como tentativa; na 2ª o mod pausa, sem castigo) */
    static int errors;
    static long deadline, showUntil, retryAt, playAt;
    /** quanto tempo ele tem para falar nesta escuta (para a barra) */
    static long total = 7000;
    /** a escuta acabou de começar: o tempo só corre depois que o microfone abre (numa retomada o relógio não volta) */
    private static boolean fresh;
    private static long thinkSince, aliveAt;
    static String playKey, retryTitle = "";
    /** a palavra do último desafio que terminou: a tecla "ouvir de novo" toca esta */
    static String lastKey;
    /** muda a cada cancelamento: a análise que terminar depois disso é de um desafio que já acabou e é jogada fora */
    static int round;
    // o que o quadro mostra (Hud): cabeçalho na cor do momento, nome em português, 汉字 grande + pinyin, duas linhas menores
    static String title = "", line1 = "", zh = "", py = "", line2 = "", line3 = "";
    static int color = Hud.GRAY;
    /** os tons da última fala, para o quadro desenhar a curva de cada sílaba; null = sem desenho */
    static ToneCheck.Result curves;

    /** troca tudo o que o quadro mostra de uma vez (e tira o desenho dos tons). Cor: Hud.GRAY neutro, GREEN acertou, YELLOW quase, RED errou */
    static void screen(int color, String title, String line1, String zh, String py, String line2, String line3) {
        Desafio.color = color; Desafio.title = title; Desafio.line1 = line1;
        Desafio.zh = zh; Desafio.py = py; Desafio.line2 = line2; Desafio.line3 = line3;
        curves = null;
    }

    static ClientConfig cfg() { return ClientConfig.get(); }

    // ------------------------------------------------------------ entradas

    public static void onChallenge(Payloads.Challenge c) {
        // espera até a tela do resultado acabar (dá para ler o que o mod ouviu e escutar a pronúncia antes de falar de
        // novo) e até um momento calmo: o tick tenta de novo
        waiting = state == St.IDLE ? waitReason(MinecraftClient.getInstance(), System.currentTimeMillis()) : null;
        if (state != St.IDLE || waiting != null) { pending = c; return; }
        cur = c; practice = false; attempt = 1; kind = c.kind(); count = c.count(); tipGiven = false; blindGiven = false; errors = 0;
        word = Vocab.get(c.word());
        if (word == null) { cur = null; return; }
        // na quantidade o ícone leva o número da pilha, como no inventário
        try { icon = new ItemStack(Registries.ITEM.get(Identifier.of(c.item())), kind == Payloads.COUNT ? Math.max(1, count) : 1); } catch (Exception e) { icon = ItemStack.EMPTY; }
        begin();
    }

    public static void onCancel(Payloads.Cancel c) {
        if (cur != null && cur.word().equals(c.word())) {
            MIC.stopQuiet();
            round++;
            cur = null; word = null; state = St.IDLE;
        }
        if (pending != null && pending.word().equals(c.word())) { pending = null; waiting = null; }
    }

    /** /hanyu teste: diga o nome de qualquer item, sem perder nada. false = está no meio de outro desafio */
    public static boolean startPractice() {
        if (state != St.IDLE && state != St.SHOW) return false;
        cur = null; word = null; icon = new ItemStack(Items.WRITABLE_BOOK); practice = true; attempt = 1; count = 0; kind = Payloads.NEW; errors = 0;
        begin();
        return true;
    }

    public static void reset() {
        MIC.stopQuiet();
        round++;
        state = St.IDLE; cur = null; pending = null; word = null; playKey = null; count = 0; waiting = null; curves = null;
        practice = false;
    }

    /** tecla "ouvir de novo": a pronúncia da última palavra pedida. Com um desafio aberto não toca (entregaria a resposta). */
    public static boolean replay() {
        if (cur != null || practice || lastKey == null || (state != St.IDLE && state != St.SHOW)) return false;
        playWord(lastKey);
        return true;
    }

    private static void begin() {
        if (!Speech.ready()) { state = St.WAIT_MODEL; return; }
        listen();
    }

    /** abre o microfone (em segundo plano: se falhar, o tick vê em MIC.error) e mostra o desafio */
    private static void listen() {
        MIC.endSilenceMs = 700;
        MIC.start();
        state = St.LISTEN;
        fresh = true;
        total = cfg().seconds * 1000L;
        deadline = System.currentTimeMillis() + total;
        listenScreen();
    }

    /**
     * O microfone disparou com um barulho (clique, respiração) antes de a pessoa falar e o reconhecedor não ouviu nada:
     * volta a ouvir com o tempo que sobra, sem gastar a tentativa.
     */
    private static void resume() {
        MIC.start();
        state = St.LISTEN;
        fresh = false;
        listenScreen();
    }

    /** o quadro do desafio (palavra nova, revisão ou quantidade), com a dica do nível que veio do servidor */
    static void listenScreen() {
        if (practice) { screen(Hud.GRAY, "Treino: diga o nome de um item", "Nada se perde aqui.", "", "", "", ""); return; }
        String hint = hintFor(cur.level());
        boolean auto = cfg().hint.equals("auto");
        if (kind == Payloads.COUNT) {   // a mesma dica que vai sumindo, só que da quantidade: frase inteira → só o classificador → nada
            Vocab.Alt mw = word.mw.get(0);
            screen(Hud.GRAY, attempt > 1 ? retryTitle : "Quantos você tem? Diga em chinês:", count + " × " + word.pt,
                    switch (hint) { case "pinyin", "hanzi" -> countZh(); case "sem-tom" -> mw.zh(); default -> ""; },
                    switch (hint) { case "pinyin" -> countPy(); case "sem-tom" -> Pinyin.noTones(mw.py()); default -> ""; },
                    !auto ? "" : hint.equals("pinyin") ? "Número + classificador (o nome é opcional)." : hint.equals("sem-tom") ? "Esse é o classificador; o número é com você." : "",
                    "");
            return;
        }
        boolean review = kind == Payloads.REVIEW;
        String note = !auto ? "" : hint.equals("pinyin") ? "Leia o pinyin em voz alta, com os tons."
                : hint.equals("sem-tom") ? (review ? "Você já acertou esta. Agora sem as marcas: lembre dos tons." : "Agora sem as marcas: lembre dos tons.")
                : review ? "Você já sabe esta: de cabeça!" : "";
        screen(Hud.GRAY, attempt > 1 ? retryTitle : review ? "Revisão: diga em chinês:" : "Diga em chinês:", word.pt,
                hint.equals("nenhuma") ? "" : word.zh,
                switch (hint) { case "pinyin" -> word.pretty(); case "sem-tom" -> Pinyin.noTones(word.py); default -> ""; },
                note, "");
    }

    /** a quantidade certa do desafio de agora: 六十四根木头 (com o 1º classificador da palavra) */
    static String countZh() { return Counting.zhCount(count) + word.mw.get(0).zh() + word.zh; }

    static String countPy() { return Pinyin.pretty(Counting.pyCount(count) + " " + word.mw.get(0).py() + " " + word.py); }

    /**
     * Dica automática pelo nível que o servidor manda (Payloads.Challenge.level): 0 = pinyin inteiro, 1 = sem as marcas
     * de tom (na quantidade: só o classificador), 2 = nada. O nível só sobe quando ele acerta: palavra que ainda não
     * acertou vem sempre com o pinyin, por mais que erre. Os outros ajustes do /hanyu dica valem sempre.
     */
    static String hintFor(int level) {
        String h = cfg().hint;
        if (!h.equals("auto")) return h;
        return level <= 0 ? "pinyin" : level == 1 ? "sem-tom" : "nenhuma";
    }

    private static void micFailed() {
        MIC.stopQuiet();
        techFailed("Microfone não funcionou", MIC.error != null ? MIC.error : "Não consegui abrir o microfone.");
    }

    /** problema técnico (microfone, modelo de voz, erro na análise): nunca tira item; o servidor pausa o desafio */
    private static void techFailed(String what, String detail) {
        state = St.SHOW; showUntil = System.currentTimeMillis() + 7000; icon = new ItemStack(Items.BARRIER);
        screen(Hud.RED, what, detail, "", "", cur != null ? "Sem castigo: o mod pausou. Depois use /mandarim continuar." : "", "");
        if (cur != null) sendAnswer(false, "__mic__");
        cur = null; practice = false;
    }

    // ------------------------------------------------------------ momento calmo (ideia do mod Learn or Die)

    /** por que o desafio que chegou ainda espera (aparece no alto da tela); null = não está esperando */
    static String waiting;
    private static long lastHurtAt, busyUntil, lastActiveAt;
    private static double lastX, lastZ;
    private static float lastYaw, lastPitch;

    /** guarda, a cada tick, quando o jogador levou dano, cavou/usou algo e mexeu nele pela última vez */
    private static void track(MinecraftClient mc, long now) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (p.hurtTime > 0) lastHurtAt = now;
        boolean acting = mc.options.attackKey.isPressed() || mc.options.useKey.isPressed() || p.isUsingItem()
                || (mc.interactionManager != null && mc.interactionManager.isBreakingBlock());
        if (acting) busyUntil = now + 1500;
        if (acting || p.getX() != lastX || p.getZ() != lastZ || p.getYaw() != lastYaw || p.getPitch() != lastPitch) lastActiveAt = now;
        lastX = p.getX(); lastZ = p.getZ(); lastYaw = p.getYaw(); lastPitch = p.getPitch();
    }

    /**
     * null = pode começar o desafio agora. Senão, o motivo de esperar: no meio de uma luta, caindo, na água, dormindo,
     * cavando ou usando um item, parado há tempo (longe do teclado: aí ele nem ouviria a pergunta), com o HUD
     * escondido (F1: o desafio correria sem ele ver) ou com uma tela do mod aberta.
     * Montado pode: dá para falar andando de cavalo ou de barco.
     */
    static String waitReason(MinecraftClient mc, long now) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return null;
        if (p.isDead()) return "morto";
        if (mc.options.hudHidden) return "HUD escondido (F1)";
        if (mc.currentScreen instanceof AjustesScreen || mc.currentScreen instanceof DicionarioScreen) return "menu do mod aberto";
        if (now - lastHurtAt < 5000) return "levou dano";
        if (p.isSleeping()) return "dormindo";
        if (p.isSubmergedInWater() || p.isInLava()) return "debaixo d'água";
        boolean supported = p.hasVehicle() || p.isOnGround() || p.isClimbing() || p.getAbilities().flying;
        if (!supported && p.isTouchingWater()) return "nadando";
        if (p.isFallFlying() || !supported) return "no ar";
        if (now < busyUntil) return "ocupado";
        if (now - lastActiveAt > 30_000) return "parado";
        return null;
    }

    // ------------------------------------------------------------ a cada tick

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        track(client, now);
        alive(now);
        if (playKey != null && now >= playAt) { playWord(playKey); playKey = null; }
        switch (state) {
            case WAIT_MODEL -> {
                screen(Hud.GRAY, "Preparando o reconhecimento de voz…", Speech.status(), "", "", "", "");
                if (Speech.status().startsWith("erro")) { techFailed("O reconhecimento de voz não carregou", Speech.status()); return; }
                if (Speech.ready()) {
                    if (cur != null || practice) begin(); else state = St.IDLE;
                }
            }
            case LISTEN -> {
                if (MIC.error != null) { micFailed(); return; }
                if (client.isPaused()) { deadline += 50; return; }
                if (MIC.opening) { if (fresh) deadline = now + total; return; }   // o tempo só corre com o microfone aberto
                boolean timeUp = now > deadline + (MIC.speechStarted && !MIC.speechEnded ? 2500 : 0);
                if (MIC.speechEnded || timeUp) finishListening();
            }
            case THINK -> {
                // a análise leva 1 ou 2 s; se passou muito disso ela travou: larga, sem castigo
                if (now - thinkSince > 20_000) { round++; techFailed("A análise da fala travou", "Veja o logs/latest.log."); }
            }
            case RETRY_WAIT -> { if (now >= retryAt) listen(); }
            case SHOW -> { if (now >= showUntil) state = St.IDLE; }
            default -> {}
        }
        // desafio que chegou com a tela ocupada; vale para qualquer caminho que volte a IDLE (não só o fim do SHOW)
        if (state == St.IDLE && pending != null) { Payloads.Challenge p = pending; pending = null; onChallenge(p); }
    }

    /**
     * a cada 2 s, enquanto um desafio do servidor está aberto ou esperando um momento calmo, avisa o servidor que o
     * cliente continua nele; sem aviso por 20 s o servidor cancela (sem castigo)
     */
    private static void alive(long now) {
        Payloads.Challenge c = cur != null ? cur : pending;
        if (c == null || now - aliveAt < 2000) return;
        aliveAt = now;
        if (ClientPlayNetworking.canSend(Payloads.Alive.ID)) ClientPlayNetworking.send(new Payloads.Alive(c.word()));
    }

    private static void finishListening() {
        final boolean spoke = MIC.speechStarted;
        state = St.THINK; thinkSince = System.currentTimeMillis();
        title = "Analisando"; line2 = ""; line3 = "";   // o Hud anima as reticências
        final int r0 = round;
        final Vocab.Word target = word;
        final boolean prac = practice, counting = !practice && kind == Payloads.COUNT, save = cfg().saveAudio;
        final int cnt = count;
        final double tol = cfg().tol();
        WORK.submit(() -> {
            final float[] audio = MIC.stop();   // fechar o microfone também demora: fica aqui, fora da thread do jogo
            final String mic = micInfo(audio, spoke);
            if (!spoke && MIC.mute) {   // só zeros: o sistema bloqueou o microfone, não é o jogador que ficou quieto
                post(r0, () -> techFailed("O microfone só mandou silêncio", "Libere o microfone para o Minecraft no sistema."));
                return;
            }
            Result r;
            try {
                r = counting ? Quantity.of(target).analyze(audio, spoke, cnt, tol) : analyze(audio, spoke, target, prac, tol);
            } catch (Throwable t) {   // falha do mod, não do jogador: não pode contar como resposta errada
                MandarimCraft.LOG.error("Erro analisando a fala", t);
                r = Result.failed();
            }
            final Result res = r;
            String file = save ? Gravacoes.save(audio, (target == null ? "treino" : target.key) + "_" + res.outcome()) : null;
            logAttempt(target, counting ? cnt : 0, res, mic, file);
            post(r0, () -> onResult(res));
        });
    }

    /** volta para a thread do jogo, se o desafio ainda é o mesmo */
    private static void post(int r0, Runnable what) {
        MinecraftClient.getInstance().execute(() -> { if (r0 == round) what.run(); });
    }

    /** volume e duração da gravação, para o log */
    private static String micInfo(float[] audio, boolean spoke) {
        String pk = MIC.peak > 0 ? Math.round(20 * Math.log10(MIC.peak / 32768.0)) + " dBFS" : "zero";
        return String.format("mic%s: pico %s, fala %s, %.1f s a %d Hz", MIC.opened.isEmpty() ? "" : " '" + MIC.opened + "'", pk, spoke ? "detectada" : "não detectada", audio.length / 16000.0, Math.round(MIC.rate()));
    }

    /** uma linha por tentativa no latest.log: o que o mod ouviu, por que aceitou ou não, e os números de cada sílaba */
    private static void logAttempt(Vocab.Word target, int cnt, Result r, String mic, String file) {
        MandarimCraft.LOG.info("[tentativa] pedida: {} | ouvi: '{}'{} | sílabas {} | tons: {} | voz {} Hz | {}{}{}",
                target == null ? "(treino)" : cnt > 0 ? cnt + "× " + target.zh : target.zh, r.heard(),
                r.first() == null ? "" : " (a 1ª opção do reconhecedor era '" + r.first() + "')",
                r.error() ? "erro interno" : r.unmeasured() ? "ok, tom sem medida" : r.syllablesOk() ? "ok" : r.tip() != null ? "dica: " + r.tip() : "erradas",
                r.tones() == null ? "-" : (r.tones().ok() ? "ok " : "erro ") + r.tones().summary(),
                r.tones() == null ? "-" : Math.round(r.tones().medianHz()), mic,
                r.tones() == null ? "" : " | medidas: " + r.tones().detail(),
                file == null ? "" : " | gravação: " + file);
    }

    // ------------------------------------------------------------ análise (fora da thread do jogo)

    /**
     * O que saiu de uma fala.
     * heard = a hipótese do reconhecedor que valeu; first = a 1ª opção dele, quando a que valeu foi outra (para o log);
     * tip = na quantidade, o número está certo mas o classificador (ou o 两) não: vem a dica em vez de contar como erro;
     * error = a análise falhou por dentro (não é resposta errada);
     * unmeasured = disse a palavra, mas em nenhuma sílaba deu para medir a voz (sussurro, voz rouca): o tom não foi conferido.
     */
    record Result(boolean spoke, boolean pass, boolean syllablesOk, String heard, Vocab.Word heardWord, ToneCheck.Result tones, String tip, boolean error, String first, boolean unmeasured) {
        /** não falou nada */
        static Result silent() { return new Result(false, false, false, "", null, null, null, false, null, false); }
        /** falou, mas não foi a palavra (w = a pedida, ou null no treino quando não reconheceu item nenhum) */
        static Result wrong(String heard, Vocab.Word w) { return new Result(true, false, false, heard, w, null, null, false, null, false); }
        static Result tip(String heard, Vocab.Word w, String tip) { return new Result(true, false, false, heard, w, null, tip, false, null, false); }
        /**
         * as sílabas são as da palavra; passa se os tons também. Se a palavra tem tom para conferir e não deu para
         * medir a voz em nenhuma sílaba, não passa: aceitar seria dar de graça.
         */
        static Result said(String heard, String first, Vocab.Word w, ToneCheck.Result tones) {
            boolean blind = tones.checked() > 0 && tones.measured() == 0;
            return new Result(true, tones.ok() && !blind, !blind, heard, w, tones, null, false, first, blind);
        }
        static Result failed() { return new Result(true, false, false, "", null, null, null, true, null, false); }

        /** para o nome da gravação e o log */
        String outcome() { return error ? "erro" : pass ? "ok" : unmeasured ? "semmedida" : syllablesOk ? "tom" : tip != null ? "dica" : heard.isEmpty() ? "nada" : "silabas"; }
    }

    private static List<Answer.Form> forms(Vocab.Word w) {
        List<Answer.Form> f = new ArrayList<>();
        for (Vocab.Alt a : w.forms()) f.add(new Answer.Form(a.zh(), a.py(), a.rec()));
        return f;
    }

    private static Result analyze(float[] audio, boolean spoke, Vocab.Word target, boolean prac, double tol) {
        // reconhece mesmo se o detector de fala não disparou (voz baixa): com só silêncio o Vosk devolve vazio.
        // No desafio a gramática leva o contexto (a palavra pedida pesa mais) e valem as NBEST melhores hipóteses;
        // no treino vale qualquer item, então é sem contexto e só a 1ª.
        List<Speech.Heard> hs = prac ? Speech.recognize(audio, Speech.grammar(), 1) : Speech.recognize(audio, Speech.grammarFor(target), Speech.NBEST);
        if (hs == null) throw new IllegalStateException("o reconhecimento de voz não está pronto");
        String top = hs.isEmpty() ? "" : hs.get(0).joined();
        if (!spoke && top.isEmpty()) return Result.silent();
        List<Answer.Form> all = new ArrayList<>();
        for (Vocab.Word w : Vocab.all()) all.addAll(forms(w));
        Vocab.Word tw = target;
        if (prac) {  // treino: a palavra "pedida" é a que ele disse (a forma mais longa que aparece no que ouviu)
            tw = null; int best = 0;
            for (Vocab.Word w : Vocab.all()) for (Vocab.Alt a : w.forms()) {
                String r = a.rec().replace(" ", "");
                if (r.length() > best && top.contains(r)) { tw = w; best = r.length(); }
            }
            if (tw == null) return Result.wrong(top, null);
        }
        Speech.Heard h = null; Answer.Match m = null;
        for (Speech.Heard x : hs) {
            Answer.Match mx = Answer.match(x.joined(), forms(tw), all);
            if (mx.syllablesOk()) { h = x; m = mx; break; }
        }
        if (m == null) return Result.wrong(top, tw);
        Answer.Form f = m.form();
        double from = -1, to = -1; double[][] syl = null;
        if (!h.pieces().isEmpty()) {
            // só o trecho da palavra pedida (ouviu "木头石头": o tom é conferido em 木头, não nas quatro sílabas)
            int[] sp = Speech.span(h.pieces(), f.recJoined());
            if (sp == null) sp = Speech.span(h.pieces(), f.zh());
            if (sp == null) sp = new int[]{0, h.pieces().size()};
            List<Speech.Piece> ps = h.pieces().subList(sp[0], sp[1]);
            from = ps.get(0).start(); to = ps.get(ps.size() - 1).end();
            int ns = Pinyin.parse(f.py()).size();
            if (ps.size() == ns && ns > 1) {
                syl = new double[ns][2];
                for (int i = 0; i < ns; i++) { syl[i][0] = ps.get(i).start(); syl[i][1] = ps.get(i).end(); }
            }
        }
        ToneCheck.Result tr = ToneCheck.check(audio, from, to, syl, f.zh(), f.py(), tol);
        // quase sem voz no trecho: foi ruído que o reconhecedor (que espera a palavra) tomou por ela
        if (tr.voiced() < Speech.MIN_VOICED) return Result.wrong("", tw);
        return Result.said(h.joined(), h == hs.get(0) ? null : top, tw, tr);
    }

    // ------------------------------------------------------------ resultado (thread do jogo)

    /** o que dizer quando a pessoa falou a palavra mas não deu para medir o tom */
    private static final String UNMEASURED = "Não deu para medir o tom: fale firme, sem sussurrar.";

    private static void onResult(Result r) {
        long now = System.currentTimeMillis();
        String tons = r.tones() != null ? r.tones().problems() : "";   // só o que não saiu limpo: o desenho mostra o resto
        if (r.error()) { onError(now); return; }
        // o reconhecedor não ouviu nada e ainda tem tempo: foi barulho antes da fala; continua ouvindo, sem gastar a tentativa
        if (cur != null && word != null && r.heard().isEmpty() && deadline - now > 1500) { resume(); return; }
        if (practice) {
            practice = false;
            state = St.SHOW; showUntil = now + 5000;
            if (r.heardWord() == null) { screen(Hud.YELLOW, r.spoke() ? "Não reconheci nenhum item" : "Não ouvi nada", r.heard().isEmpty() ? "" : "Ouvi: " + r.heard(), "", "", "", ""); return; }
            Vocab.Word w = r.heardWord();
            ItemStack it = w.icon();   // no treino o quadro mostra o item da palavra que você disse
            if (!it.isEmpty()) icon = it;
            screen(r.pass() ? Hud.GREEN : Hud.RED, r.pass() ? "✓ Isso!" : "✗ O tom não saiu certo", w.pt, w.zh, w.pretty(), tons, "");
            curves = r.tones();
            lastKey = w.key;
            if (!r.pass()) { playKey = w.key; playAt = now + 700; }
            return;
        }
        if (cur == null || word == null) { state = St.IDLE; return; }
        if (r.unmeasured() && !blindGiven) {   // disse a palavra, mas o mod não conseguiu medir a voz: a 1ª vez é por conta da casa
            blindGiven = true;
            screen(Hud.YELLOW, "Ouvi: " + r.heard(), UNMEASURED, "", "", "Não contou como tentativa.", "");
            retryTitle = "Mais uma vez, com a voz firme:";
            state = St.RETRY_WAIT; retryAt = now + 2600;
            return;
        }
        if (kind == Payloads.COUNT) { onCountResult(r, now, tons); return; }
        boolean review = kind == Payloads.REVIEW;
        if (r.pass()) {
            sendAnswer(true, r.heard());
            state = St.SHOW; showUntil = now + 3500;
            screen(Hud.GREEN, review ? "✓ Lembrou!" : "✓ Acertou!", word.pt, word.zh, word.pretty(), tons, "");
            curves = r.tones();
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f, 0.6f));
            lastKey = word.key; cur = null;
            return;
        }
        if (attempt <= cfg().retries) {   // outra chance em qualquer erro: o reconhecedor também se engana, até com nativos
            attempt++;
            state = St.RETRY_WAIT;
            if (r.syllablesOk()) {   // já disse a palavra certa: mostra como ela é e toca a pronúncia
                screen(Hud.YELLOW, "Quase! As sílabas estão certas, o tom não:", word.pt, word.zh, word.pretty(), tons, "");
                curves = r.tones();
                retryTitle = "Mais uma vez, cuidado com o tom:";
                playKey = word.key; playAt = now + 300; retryAt = now + 3200;
            } else if (r.unmeasured()) {
                screen(Hud.YELLOW, "Ouvi: " + r.heard(), UNMEASURED, "", "", "", "");
                retryTitle = "Mais uma vez, com a voz firme:";
                retryAt = now + 2600;
            } else {   // sem tocar a pronúncia: aqui ainda vale lembrar da palavra
                screen(Hud.YELLOW, !r.spoke() ? "Não ouvi nada" : r.heard().isEmpty() ? "Não entendi" : "Ouvi: " + r.heard(),
                        r.spoke() && !r.heard().isEmpty() ? "Tente de novo." : "Fale de novo, mais perto do microfone.", "", "", "", "");
                retryTitle = "Mais uma vez:";
                retryAt = now + 1800;
            }
            return;
        }
        sendAnswer(false, r.heard());
        state = St.SHOW; showUntil = now + 6500;
        screen(Hud.RED, "✗ Não foi dessa vez", word.pt, word.zh, word.pretty(),
                !r.spoke() ? "Não ouvi nada a tempo." : r.syllablesOk() ? tons : r.unmeasured() ? UNMEASURED : (r.heard().isEmpty() ? "Não entendi." : "Ouvi: " + r.heard()),
                !review ? "Ouça e tente quando pegar de novo."
                        : count >= 2 ? "Perdeu ¼ da pilha: " + (count + 3) / 4 + " de " + count + "."
                        : "Voltou a ser palavra nova: vem de novo, com o pinyin.");
        if (r.syllablesOk()) curves = r.tones();
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.7f));
        playKey = word.key; playAt = now + 900;
        lastKey = word.key; cur = null;
    }

    /** a análise falhou por dentro: não gasta tentativa; ouve de novo uma vez e, se repetir, pausa sem castigo */
    private static void onError(long now) {
        if (practice || cur == null || ++errors >= 2) {
            boolean had = cur != null;
            techFailed("Tive um erro ao analisar a fala", had ? "Não foi você. Veja o logs/latest.log." : "Veja o logs/latest.log.");
            return;
        }
        screen(Hud.YELLOW, "Tive um problema ao analisar", "Não contou como tentativa. Fale de novo.", "", "", "", "");
        retryTitle = "Mais uma vez:";
        state = St.RETRY_WAIT; retryAt = now + 1800;
    }

    /** resultado do "quantos você tem?": a dica de classificador não gasta tentativa (uma vez só); errou de vez, o servidor tira 1/4 da pilha */
    private static void onCountResult(Result r, long now, String tons) {
        String what = count + " × " + word.pt;
        if (r.pass()) {
            sendAnswer(true, r.heard());
            state = St.SHOW; showUntil = now + 3500;
            screen(Hud.GREEN, "✓ Isso!", what, countZh(), countPy(), tons, "");
            curves = r.tones();
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f, 0.6f));
            lastKey = word.key; cur = null;
            return;
        }
        if (r.tip() != null && !tipGiven) {
            tipGiven = true;
            state = St.RETRY_WAIT; retryAt = now + 3500;
            screen(Hud.YELLOW, "Quase! O número está certo.", r.tip(), countZh(), countPy(), "", "");
            retryTitle = "Mais uma vez:";
            return;
        }
        if (attempt <= cfg().retries) {
            attempt++;
            state = St.RETRY_WAIT;
            if (r.syllablesOk()) {
                screen(Hud.YELLOW, "Quase! O tom não saiu certo:", what, countZh(), countPy(), tons, "");
                curves = r.tones();
                retryTitle = "Mais uma vez, cuidado com o tom:"; retryAt = now + 3200;
            } else {
                screen(Hud.YELLOW, !r.spoke() ? "Não ouvi nada" : r.heard().isEmpty() ? "Não entendi" : "Ouvi: " + r.heard(),
                        r.unmeasured() ? UNMEASURED : r.spoke() && !r.heard().isEmpty() ? "Confira o número e tente de novo." : "Fale de novo, mais perto do microfone.", "", "", "", "");
                retryTitle = "Mais uma vez:"; retryAt = now + 1800;
            }
            return;
        }
        sendAnswer(false, r.heard());
        state = St.SHOW; showUntil = now + 6500;
        screen(Hud.RED, "✗ Não foi dessa vez", what, countZh(), countPy(),
                !r.spoke() ? "Não ouvi nada a tempo." : r.syllablesOk() ? tons : r.unmeasured() ? UNMEASURED : r.tip() != null ? r.tip() : r.heard().isEmpty() ? "Não entendi." : "Ouvi: " + r.heard(),
                "Perdeu ¼ da pilha: " + (count + 3) / 4 + " de " + count + ".");
        if (r.syllablesOk()) curves = r.tones();
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.7f));
        lastKey = word.key; cur = null;
    }

    static void sendAnswer(boolean ok, String heard) {
        if (cur == null) return;
        if (ClientPlayNetworking.canSend(Payloads.Answer.ID)) ClientPlayNetworking.send(new Payloads.Answer(cur.word(), ok, heard == null ? "" : heard));
    }

    public static void playWord(String key) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of(MandarimCraft.MOD_ID, "w." + key)), 1.0f, 1.0f));
    }
}
