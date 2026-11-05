package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.core.Answer;
import com.tales.mandarimcraft.core.Counting;
import com.tales.mandarimcraft.core.Pinyin;
import com.tales.mandarimcraft.core.Pitch;
import com.tales.mandarimcraft.core.ToneCheck;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
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

/** O desafio na tela: calibrar a voz, ouvir, analisar (fora da thread do jogo) e mostrar o resultado. */
public final class Desafio {
    private Desafio() {}

    public enum St { IDLE, WAIT_MODEL, CALIBRATE, LISTEN, THINK, RETRY_WAIT, SHOW }

    static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "mandarimcraft-voz"); t.setDaemon(true); return t; });
    static final Mic MIC = new Mic();

    // estado (só mexido na thread do jogo)
    static St state = St.IDLE;
    static Payloads.Challenge cur, pending;
    static Vocab.Word word;
    static ItemStack icon = ItemStack.EMPTY;
    static boolean practice, calibrateThenChallenge;
    static int attempt;
    /** desafio de quantidade: quantos itens ele tem ("六十四根木头"); 0 = palavra nova */
    static int count;
    /** a dica de classificador (个 no lugar de 根, 二 no lugar de 两) já foi dada neste desafio: ela não gasta tentativa, mas só uma vez */
    static boolean tipGiven;
    static long deadline, showUntil, retryAt, playAt;
    static String playKey, retryTitle = "";
    /** muda a cada cancelamento: a análise que terminar depois disso é de um desafio que já acabou e é jogada fora */
    static int round;
    // o que o quadro mostra (Hud): cabeçalho na cor do momento, nome em português, 汉字 grande + pinyin, duas linhas menores
    static String title = "", line1 = "", zh = "", py = "", line2 = "", line3 = "";
    static int color = Hud.GRAY;

    /** troca tudo o que o quadro mostra de uma vez. Cor: Hud.GRAY neutro, GREEN acertou, YELLOW quase, RED errou */
    static void screen(int color, String title, String line1, String zh, String py, String line2, String line3) {
        Desafio.color = color; Desafio.title = title; Desafio.line1 = line1;
        Desafio.zh = zh; Desafio.py = py; Desafio.line2 = line2; Desafio.line3 = line3;
    }

    public static final String CAL_ZH = "你好，我是巴西人。", CAL_PY = "nǐ hǎo, wǒ shì Bāxī rén";

    static ClientConfig cfg() { return ClientConfig.get(); }

    // ------------------------------------------------------------ entradas

    public static void onChallenge(Payloads.Challenge c) {
        // espera até a tela do resultado acabar: dá para ler o que o mod ouviu e escutar a pronúncia antes de falar de novo
        if (state != St.IDLE) { pending = c; return; }
        cur = c; practice = false; attempt = 1; count = c.count(); tipGiven = false;
        word = Vocab.get(c.word());
        if (word == null) { cur = null; return; }
        // na quantidade o ícone leva o número da pilha, como no inventário
        try { icon = new ItemStack(Registries.ITEM.get(Identifier.of(c.item())), Math.max(1, count)); } catch (Exception e) { icon = ItemStack.EMPTY; }
        begin();
    }

    public static void onCancel(Payloads.Cancel c) {
        if (cur != null && cur.word().equals(c.word())) {
            MIC.stopQuiet();
            round++;
            cur = null; word = null; state = St.IDLE; calibrateThenChallenge = false; retryCalibrate = false;
        }
        if (pending != null && pending.word().equals(c.word())) pending = null;
    }

    /** /hanyu teste: diga o nome de qualquer item, sem perder nada. false = está no meio de outro desafio */
    public static boolean startPractice() {
        if (state != St.IDLE && state != St.SHOW) return false;
        cur = null; word = null; icon = new ItemStack(Items.WRITABLE_BOOK); practice = true; attempt = 1; count = 0;
        begin();
        return true;
    }

    /** /hanyu calibrar. false = está no meio de outro desafio */
    public static boolean startCalibration() {
        if (state != St.IDLE && state != St.SHOW) return false;
        cur = null; word = null; icon = new ItemStack(Items.NOTE_BLOCK); practice = false; calibrateThenChallenge = false;
        if (!Speech.ready()) { state = St.WAIT_MODEL; return true; }
        calibrate();
        return true;
    }

    public static void reset() {
        MIC.stopQuiet();
        round++;
        state = St.IDLE; cur = null; pending = null; word = null; playKey = null; count = 0;
        practice = false; calibrateThenChallenge = false; retryCalibrate = false; calTries = 0;
    }

    private static void begin() {
        if (!Speech.ready()) { state = St.WAIT_MODEL; return; }
        if (cfg().refHz <= 0) { calibrateThenChallenge = true; calibrate(); return; }
        listen();
    }

    static int calTries = 0;

    private static void calibrate() {
        MIC.endSilenceMs = 1300;
        if (!MIC.start()) { micFailed(); return; }
        state = St.CALIBRATE;
        deadline = System.currentTimeMillis() + 9000;
        screen(Hud.GRAY, "Primeiro, deixe o mod conhecer a sua voz", "Leia em voz normal:", CAL_ZH, CAL_PY, "", "");
    }

    private static void listen() {
        MIC.endSilenceMs = 700;
        if (!MIC.start()) { micFailed(); return; }
        state = St.LISTEN;
        deadline = System.currentTimeMillis() + cfg().seconds * 1000L;
        if (practice) { screen(Hud.GRAY, "Treino: diga o nome de um item", "Nada se perde aqui.", "", "", "", ""); return; }
        String hint = hintFor(cur.seen());
        if (count > 0) {   // a mesma dica que vai sumindo, só que da quantidade: frase inteira → só o classificador → nada
            Vocab.Alt mw = word.mw.get(0);
            boolean auto = cfg().hint.equals("auto");
            screen(Hud.GRAY, attempt > 1 ? retryTitle : "Quantos você tem? Diga em chinês:", count + " × " + word.pt,
                    switch (hint) { case "pinyin", "hanzi" -> countZh(); case "sem-tom" -> mw.zh(); default -> ""; },
                    switch (hint) { case "pinyin" -> countPy(); case "sem-tom" -> Pinyin.noTones(mw.py()); default -> ""; },
                    !auto ? "" : cur.seen() == 0 ? "Número + classificador (o nome é opcional)." : hint.equals("sem-tom") ? "Esse é o classificador; o número é com você." : "",
                    "");
            return;
        }
        String note = !cfg().hint.equals("auto") ? "" : cur.seen() == 0 ? "Palavra nova: leia o pinyin em voz alta." : hint.equals("sem-tom") ? "Agora sem as marcas: lembre dos tons." : "";
        screen(Hud.GRAY, attempt > 1 ? retryTitle : "Diga em chinês:", word.pt,
                hint.equals("nenhuma") ? "" : word.zh,
                switch (hint) { case "pinyin" -> word.pretty(); case "sem-tom" -> Pinyin.noTones(word.py); default -> ""; },
                note, "");
    }

    /** a quantidade certa do desafio de agora: 六十四根木头 (com o 1º classificador da palavra) */
    static String countZh() { return Counting.zhCount(count) + word.mw.get(0).zh() + word.zh; }

    static String countPy() { return Pinyin.pretty(Counting.pyCount(count) + " " + word.mw.get(0).py() + " " + word.py); }

    /**
     * Dica automática conforme quantas vezes já errou a palavra neste mundo: nas 3 primeiras com o pinyin inteiro
     * (pegar um bloco pela primeira vez na vida e perder por não saber a palavra pune demais), nas 2 seguintes sem as
     * marcas de tom, depois só o português. Os outros ajustes do /hanyu dica valem sempre.
     */
    static String hintFor(int seen) {
        String h = cfg().hint;
        if (!h.equals("auto")) return h;
        return seen < 3 ? "pinyin" : seen < 5 ? "sem-tom" : "nenhuma";
    }

    private static void micFailed() {
        techFailed("Microfone não funcionou", MIC.error != null ? MIC.error : "Não consegui abrir o microfone.");
    }

    /** problema técnico (microfone, modelo de voz): nunca tira item; o servidor pausa o desafio */
    private static void techFailed(String what, String detail) {
        state = St.SHOW; showUntil = System.currentTimeMillis() + 7000; icon = new ItemStack(Items.BARRIER);
        screen(Hud.RED, what, detail, "", "", cur != null ? "Sem castigo: o mod pausou. Depois use /mandarim continuar." : "", "");
        if (cur != null) sendAnswer(false, "__mic__");
        cur = null; practice = false; calibrateThenChallenge = false;
    }

    // ------------------------------------------------------------ a cada tick

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (playKey != null && now >= playAt) { playWord(playKey); playKey = null; }
        switch (state) {
            case WAIT_MODEL -> {
                screen(Hud.GRAY, "Preparando o reconhecimento de voz…", Speech.status(), "", "", "", "");
                if (Speech.status().startsWith("erro")) { techFailed("O reconhecimento de voz não carregou", Speech.status()); return; }
                if (Speech.ready()) {
                    if (cur != null || practice) begin(); else calibrate();
                }
            }
            case LISTEN, CALIBRATE -> {
                if (client.isPaused()) { deadline += 50; return; }
                boolean timeUp = now > deadline + (MIC.speechStarted && !MIC.speechEnded ? 2500 : 0);
                if (MIC.speechEnded || timeUp) finishListening();
            }
            case RETRY_WAIT -> { if (now >= retryAt) { if (retryCalibrate) { retryCalibrate = false; calibrate(); } else listen(); } }
            case SHOW -> { if (now >= showUntil) state = St.IDLE; }
            default -> {}
        }
        // desafio que chegou com a tela ocupada; vale para qualquer caminho que volte a IDLE (não só o fim do SHOW)
        if (state == St.IDLE && pending != null) { Payloads.Challenge p = pending; pending = null; onChallenge(p); }
    }

    private static void finishListening() {
        final St was = state;
        final float[] audio = MIC.stop();
        final boolean spoke = MIC.speechStarted;
        if (!spoke && MIC.mute) {   // só zeros: o sistema bloqueou o microfone, não é o jogador que ficou quieto
            calTries = 0;
            techFailed("O microfone só mandou silêncio", "Libere o microfone para o Minecraft no sistema.");
            return;
        }
        state = St.THINK;
        title = "Analisando"; line2 = ""; line3 = "";   // o Hud anima as reticências
        final int r0 = round;
        final String mic = micInfo(audio, spoke);
        if (was == St.CALIBRATE) {
            WORK.submit(() -> {
                double[] f0 = spoke ? Pitch.clean(Pitch.yin(audio)) : new double[0];
                int voiced = 0; for (double f : f0) if (f > 0) voiced++;
                final double m = Pitch.median(f0); final int v = voiced;
                MandarimCraft.LOG.info("[calibração] altura {} Hz, {} quadros com voz | {}", Math.round(m), v, mic);
                MinecraftClient.getInstance().execute(() -> { if (r0 == round) onCalibrated(m, v); });
            });
            return;
        }
        final Vocab.Word target = word;
        final boolean prac = practice;
        final int cnt = practice ? 0 : count;
        final double ref = cfg().refHz, tol = cfg().tol();
        WORK.submit(() -> {
            Result r = cnt > 0 ? Quantity.of(target).analyze(audio, spoke, cnt, ref, tol) : analyze(audio, spoke, target, prac, ref, tol);
            logAttempt(target, cnt, r, ref, mic);
            MinecraftClient.getInstance().execute(() -> { if (r0 == round) onResult(r); });
        });
    }

    /** volume e duração da gravação, para o log */
    private static String micInfo(float[] audio, boolean spoke) {
        String pk = MIC.peak > 0 ? Math.round(20 * Math.log10(MIC.peak / 32768.0)) + " dBFS" : "zero";
        return String.format("mic: pico %s, fala %s, %.1f s a %d Hz", pk, spoke ? "detectada" : "não detectada", audio.length / 16000.0, Math.round(MIC.rate()));
    }

    /** uma linha por tentativa no latest.log: o que o mod ouviu e por que aceitou ou não */
    private static void logAttempt(Vocab.Word target, int cnt, Result r, double ref, String mic) {
        MandarimCraft.LOG.info("[tentativa] pedida: {} | ouvi: '{}' | sílabas {} | tons: {} | voz {} Hz (ref {} Hz) | {}",
                target == null ? "(treino)" : cnt > 0 ? cnt + "× " + target.zh : target.zh, r.heard(), r.syllablesOk() ? "ok" : r.tip() != null ? "dica: " + r.tip() : "erradas",
                r.tones() == null ? "-" : (r.tones().ok() ? "ok " : "erro ") + r.tones().summary(),
                r.tones() == null ? "-" : Math.round(r.tones().medianHz()), Math.round(ref), mic);
    }

    private static void onCalibrated(double med, int voiced) {
        if (med <= 0 || voiced < 40) {   // não ouviu o bastante: tenta de novo sozinho (até 3 vezes)
            if (++calTries >= 3) { calTries = 0; techFailed("Não consegui ouvir a sua voz", "Confira o microfone e tente /hanyu calibrar de novo."); return; }
            screen(Hud.YELLOW, "Não deu para ouvir direito", "Fale um pouco mais alto e perto do microfone.", "", "", "", "");
            state = St.RETRY_WAIT; retryCalibrate = true; retryAt = System.currentTimeMillis() + 2500;
            return;
        }
        calTries = 0;
        cfg().refHz = 0; cfg().samples = 0; cfg().addVoice(med);
        if (calibrateThenChallenge && (cur != null || practice)) {
            calibrateThenChallenge = false;
            screen(Hud.GREEN, "✓ Voz calibrada (" + Math.round(med) + " Hz)", "Agora o item!", "", "", "", "");
            state = St.RETRY_WAIT; retryCalibrate = false; retryAt = System.currentTimeMillis() + 1200;
            return;
        }
        state = St.SHOW; showUntil = System.currentTimeMillis() + 3000;
        screen(Hud.GREEN, "✓ Voz calibrada", "Altura média: " + Math.round(med) + " Hz", "", "", "", "");
    }

    static boolean retryCalibrate = false;

    // ------------------------------------------------------------ análise (fora da thread do jogo)

    /** tip: na quantidade, o número está certo mas o classificador (ou o 两) não: vem a dica em vez de contar como erro */
    record Result(boolean spoke, boolean pass, boolean syllablesOk, String heard, Answer.Form form, Vocab.Word heardWord, ToneCheck.Result tones, String tip) {
        Result(boolean spoke, boolean pass, boolean syllablesOk, String heard, Answer.Form form, Vocab.Word heardWord, ToneCheck.Result tones) {
            this(spoke, pass, syllablesOk, heard, form, heardWord, tones, null);
        }
    }

    private static List<Answer.Form> forms(Vocab.Word w) {
        List<Answer.Form> f = new ArrayList<>();
        for (Vocab.Alt a : w.forms()) f.add(new Answer.Form(a.zh(), a.py(), a.rec()));
        return f;
    }

    private static Result analyze(float[] audio, boolean spoke, Vocab.Word target, boolean prac, double ref, double tol) {
        try {
            // reconhece mesmo se o detector de fala não disparou (voz baixa): com só silêncio o Vosk devolve vazio
            Speech.Heard h = Speech.recognize(audio);
            String heard = h == null ? "" : h.joined();
            if (!spoke && heard.isEmpty()) return new Result(false, false, false, "", null, null, null);
            List<Answer.Form> all = new ArrayList<>();
            for (Vocab.Word w : Vocab.all()) all.addAll(forms(w));
            Vocab.Word tw = target;
            if (prac) {  // treino: a palavra "pedida" é a que ele disse (a forma mais longa que aparece no que ouviu)
                tw = null; int best = 0;
                for (Vocab.Word w : Vocab.all()) for (Vocab.Alt a : w.forms()) {
                    String r = a.rec().replace(" ", "");
                    if (r.length() > best && heard.contains(r)) { tw = w; best = r.length(); }
                }
                if (tw == null) return new Result(true, false, false, heard, null, null, null);
            }
            Answer.Match m = Answer.match(heard, forms(tw), all);
            if (!m.syllablesOk()) return new Result(true, false, false, heard, null, tw, null);
            Answer.Form f = m.form();
            double from = -1, to = -1; double[][] syl = null;
            if (h != null && !h.pieces().isEmpty()) {
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
            ToneCheck.Result tr = ToneCheck.check(audio, from, to, syl, f.zh(), f.py(), ref, tol);
            return new Result(true, tr.ok(), true, heard, f, tw, tr);
        } catch (Throwable t) {
            MandarimCraft.LOG.error("Erro analisando a fala", t);
            return new Result(true, false, false, "", null, null, null);
        }
    }

    // ------------------------------------------------------------ resultado (thread do jogo)

    private static void onResult(Result r) {
        // só fala aceita entra na média da voz: tentativa errada (ou ruído) puxaria a referência dos tons para longe
        if (r.pass() && r.tones().medianHz() > 0) cfg().addVoice(r.tones().medianHz());
        long now = System.currentTimeMillis();
        String tons = r.tones() != null ? r.tones().summary() : "";
        if (practice) {
            state = St.SHOW; showUntil = now + 5000;
            if (r.heardWord() == null) { screen(Hud.YELLOW, r.spoke() ? "Não reconheci nenhum item" : "Não ouvi nada", r.heard().isEmpty() ? "" : "Ouvi: " + r.heard(), "", "", "", ""); return; }
            Vocab.Word w = r.heardWord();
            ItemStack it = w.icon();   // no treino o quadro mostra o item da palavra que você disse
            if (!it.isEmpty()) icon = it;
            screen(r.pass() ? Hud.GREEN : Hud.RED, r.pass() ? "✓ Isso!" : "✗ O tom não saiu certo", w.pt, w.zh, w.pretty(), tons, "");
            if (!r.pass()) { playKey = w.key; playAt = now + 700; }
            return;
        }
        if (cur == null || word == null) { state = St.IDLE; return; }
        if (count > 0) { onCountResult(r, now, tons); return; }
        if (r.pass()) {
            sendAnswer(true, r.heard());
            state = St.SHOW; showUntil = now + 3000;
            screen(Hud.GREEN, "✓ Acertou!", word.pt, word.zh, word.pretty(), tons, "");
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f, 0.6f));
            cur = null;
            return;
        }
        if (attempt <= cfg().retries) {   // outra chance em qualquer erro: o reconhecedor também se engana, até com nativos
            attempt++;
            state = St.RETRY_WAIT; retryCalibrate = false;
            if (r.syllablesOk()) {   // já disse a palavra certa: mostra como ela é e toca a pronúncia
                screen(Hud.YELLOW, "Quase! As sílabas estão certas, o tom não:", word.pt, word.zh, word.pretty(), tons, "");
                retryTitle = "Mais uma vez, cuidado com o tom:";
                playKey = word.key; playAt = now + 300; retryAt = now + 2600;
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
                !r.spoke() ? "Não ouvi nada a tempo." : r.syllablesOk() ? "Tons: " + tons : (r.heard().isEmpty() ? "Não entendi." : "Ouvi: " + r.heard()),
                "Ouça e tente quando pegar de novo.");
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.7f));
        playKey = word.key; playAt = now + 900;
        cur = null;
    }

    /** resultado do "quantos você tem?": a dica de classificador não gasta tentativa (uma vez só); errou de vez, o servidor tira 1/4 da pilha */
    private static void onCountResult(Result r, long now, String tons) {
        String what = count + " × " + word.pt;
        if (r.pass()) {
            sendAnswer(true, r.heard());
            state = St.SHOW; showUntil = now + 3000;
            screen(Hud.GREEN, "✓ Isso!", what, countZh(), countPy(), tons, "");
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f, 0.6f));
            cur = null;
            return;
        }
        if (r.tip() != null && !tipGiven) {
            tipGiven = true;
            state = St.RETRY_WAIT; retryCalibrate = false; retryAt = now + 3500;
            screen(Hud.YELLOW, "Quase! O número está certo.", r.tip(), countZh(), countPy(), "", "");
            retryTitle = "Mais uma vez:";
            return;
        }
        if (attempt <= cfg().retries) {
            attempt++;
            state = St.RETRY_WAIT; retryCalibrate = false;
            if (r.syllablesOk()) {
                screen(Hud.YELLOW, "Quase! O tom não saiu certo:", what, countZh(), countPy(), tons, "");
                retryTitle = "Mais uma vez, cuidado com o tom:"; retryAt = now + 2600;
            } else {
                screen(Hud.YELLOW, !r.spoke() ? "Não ouvi nada" : r.heard().isEmpty() ? "Não entendi" : "Ouvi: " + r.heard(),
                        r.spoke() && !r.heard().isEmpty() ? "Confira o número e tente de novo." : "Fale de novo, mais perto do microfone.", "", "", "", "");
                retryTitle = "Mais uma vez:"; retryAt = now + 1800;
            }
            return;
        }
        sendAnswer(false, r.heard());
        state = St.SHOW; showUntil = now + 6500;
        screen(Hud.RED, "✗ Não foi dessa vez", what, countZh(), countPy(),
                !r.spoke() ? "Não ouvi nada a tempo." : r.syllablesOk() ? "Tons: " + tons : r.tip() != null ? r.tip() : r.heard().isEmpty() ? "Não entendi." : "Ouvi: " + r.heard(),
                "Perdeu ¼ da pilha: " + (count + 3) / 4 + " de " + count + ".");
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.7f));
        cur = null;
    }

    static void sendAnswer(boolean ok, String heard) {
        if (cur == null) return;
        if (ClientPlayNetworking.canSend(Payloads.Answer.ID)) ClientPlayNetworking.send(new Payloads.Answer(cur.word(), ok, heard == null ? "" : heard));
    }

    public static void playWord(String key) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of(MandarimCraft.MOD_ID, "w." + key)), 1.0f, 1.0f));
    }
}
