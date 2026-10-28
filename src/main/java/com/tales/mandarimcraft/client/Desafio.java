package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import com.tales.mandarimcraft.core.Answer;
import com.tales.mandarimcraft.core.Pinyin;
import com.tales.mandarimcraft.core.Pitch;
import com.tales.mandarimcraft.core.ToneCheck;
import com.tales.mandarimcraft.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
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
    static long deadline, showUntil, retryAt, playAt;
    static String playKey;
    // o que a tela mostra
    static String title = "", line1 = "", line2 = "", line3 = "";
    static int color = 0xFFFFFFFF;
    static boolean good;

    public static final String CAL_ZH = "你好，我是巴西人。", CAL_PY = "nǐ hǎo, wǒ shì Bāxī rén";

    static ClientConfig cfg() { return ClientConfig.get(); }

    // ------------------------------------------------------------ entradas

    public static void onChallenge(Payloads.Challenge c) {
        if (state != St.IDLE && state != St.SHOW) { pending = c; return; }
        cur = c; practice = false; attempt = 1;
        word = Vocab.get(c.word());
        if (word == null) { cur = null; return; }
        try { icon = new ItemStack(Registries.ITEM.get(Identifier.of(c.item()))); } catch (Exception e) { icon = ItemStack.EMPTY; }
        begin();
    }

    public static void onCancel(Payloads.Cancel c) {
        if (cur != null && cur.word().equals(c.word())) {
            MIC.stopQuiet();
            cur = null; word = null; state = St.IDLE;
        }
        if (pending != null && pending.word().equals(c.word())) pending = null;
    }

    /** /hanyu teste: diga o nome de qualquer item, sem perder nada */
    public static void startPractice() {
        if (state != St.IDLE && state != St.SHOW) return;
        cur = null; word = null; icon = ItemStack.EMPTY; practice = true; attempt = 1;
        begin();
    }

    /** /hanyu calibrar */
    public static void startCalibration() {
        if (state != St.IDLE && state != St.SHOW) return;
        cur = null; word = null; practice = false; calibrateThenChallenge = false;
        if (!Speech.ready()) { state = St.WAIT_MODEL; return; }
        calibrate();
    }

    public static void reset() { MIC.stopQuiet(); state = St.IDLE; cur = null; pending = null; word = null; }

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
        title = "Primeiro, deixe o mod conhecer a sua voz";
        line1 = "Leia em voz normal:";
        line2 = CAL_ZH; line3 = CAL_PY; color = 0xFFFFFFFF;
    }

    private static void listen() {
        MIC.endSilenceMs = 700;
        if (!MIC.start()) { micFailed(); return; }
        state = St.LISTEN;
        deadline = System.currentTimeMillis() + cfg().seconds * 1000L;
        if (practice) { title = "Treino: diga o nome de um item"; line1 = "Nada se perde aqui."; line2 = ""; line3 = ""; }
        else {
            title = attempt > 1 ? "Mais uma vez, cuidado com o tom:" : "Diga em chinês:";
            line1 = word.pt;
            line2 = switch (cfg().hint) { case "hanzi" -> word.zh; case "pinyin" -> word.zh + "  " + word.pretty(); default -> ""; };
            line3 = "";
        }
        color = 0xFFFFFFFF;
    }

    private static void micFailed() {
        techFailed("Microfone não funcionou", MIC.error != null ? MIC.error : "Não consegui abrir o microfone.");
    }

    /** problema técnico (microfone, modelo de voz): nunca tira item; o servidor pausa o desafio */
    private static void techFailed(String what, String detail) {
        state = St.SHOW; showUntil = System.currentTimeMillis() + 7000; good = false; color = 0xFFFF5555;
        title = what; line1 = detail;
        line2 = cur != null ? "Sem castigo: o mod pausou. Depois use /mandarim continuar." : ""; line3 = "";
        if (cur != null) sendAnswer(false, "__mic__");
        cur = null; practice = false;
    }

    // ------------------------------------------------------------ a cada tick

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (playKey != null && now >= playAt) { playWord(playKey); playKey = null; }
        switch (state) {
            case WAIT_MODEL -> {
                title = "Preparando o reconhecimento de voz…"; line1 = Speech.status(); line2 = ""; line3 = "";
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
            case SHOW -> {
                if (now >= showUntil) {
                    state = St.IDLE;
                    if (pending != null) { Payloads.Challenge p = pending; pending = null; onChallenge(p); }
                }
            }
            default -> {}
        }
    }

    private static void finishListening() {
        final St was = state;
        final float[] audio = MIC.stop();
        final boolean spoke = MIC.speechStarted;
        state = St.THINK;
        title = "Analisando…"; line3 = "";
        if (was == St.CALIBRATE) {
            WORK.submit(() -> {
                double[] f0 = spoke ? Pitch.clean(Pitch.yin(audio)) : new double[0];
                int voiced = 0; for (double f : f0) if (f > 0) voiced++;
                final double m = Pitch.median(f0); final int v = voiced;
                MinecraftClient.getInstance().execute(() -> onCalibrated(m, v));
            });
            return;
        }
        final Vocab.Word target = word;
        final boolean prac = practice;
        final double ref = cfg().refHz, tol = cfg().tol();
        WORK.submit(() -> {
            Result r = analyze(audio, spoke, target, prac, ref, tol);
            MinecraftClient.getInstance().execute(() -> onResult(r));
        });
    }

    private static void onCalibrated(double med, int voiced) {
        if (med <= 0 || voiced < 40) {   // não ouviu o bastante: tenta de novo sozinho (até 3 vezes)
            if (++calTries >= 3) { calTries = 0; techFailed("Não consegui ouvir a sua voz", "Confira o microfone e tente /hanyu calibrar de novo."); return; }
            color = 0xFFFFFF55; good = false;
            title = "Não deu para ouvir direito"; line1 = "Fale um pouco mais alto e perto do microfone."; line2 = ""; line3 = "";
            state = St.RETRY_WAIT; retryCalibrate = true; retryAt = System.currentTimeMillis() + 2500;
            return;
        }
        calTries = 0;
        cfg().refHz = 0; cfg().samples = 0; cfg().addVoice(med);
        if (calibrateThenChallenge && (cur != null || practice)) {
            calibrateThenChallenge = false;
            title = "Voz calibrada (" + Math.round(med) + " Hz). Agora o item!"; line1 = ""; line2 = ""; line3 = ""; color = 0xFF55FF55;
            state = St.RETRY_WAIT; retryCalibrate = false; retryAt = System.currentTimeMillis() + 1200;
            return;
        }
        state = St.SHOW; showUntil = System.currentTimeMillis() + 3000; color = 0xFF55FF55; good = true;
        title = "Voz calibrada ✓"; line1 = "Altura média: " + Math.round(med) + " Hz"; line2 = ""; line3 = "";
    }

    static boolean retryCalibrate = false;

    // ------------------------------------------------------------ análise (fora da thread do jogo)

    record Result(boolean spoke, boolean pass, boolean syllablesOk, String heard, Answer.Form form, Vocab.Word heardWord, ToneCheck.Result tones) {}

    private static List<Answer.Form> forms(Vocab.Word w) {
        List<Answer.Form> f = new ArrayList<>();
        for (Vocab.Alt a : w.forms()) f.add(new Answer.Form(a.zh(), a.py(), a.rec()));
        return f;
    }

    private static Result analyze(float[] audio, boolean spoke, Vocab.Word target, boolean prac, double ref, double tol) {
        try {
            if (!spoke) return new Result(false, false, false, "", null, null, null);
            Speech.Heard h = Speech.recognize(audio);
            String heard = h == null ? "" : h.joined();
            List<Answer.Form> all = new ArrayList<>();
            for (Vocab.Word w : Vocab.all()) all.addAll(forms(w));
            Vocab.Word tw = target;
            if (prac) {  // treino: a palavra "pedida" é a que ele disse
                tw = null;
                for (Vocab.Word w : Vocab.all()) for (Vocab.Alt a : w.forms()) if (a.zh().equals(heard) || a.rec().replace(" ", "").equals(heard)) tw = w;
                if (tw == null) return new Result(true, false, false, heard, null, null, null);
            }
            Answer.Match m = Answer.match(heard, forms(tw), all);
            if (!m.syllablesOk()) return new Result(true, false, false, heard, null, tw, null);
            Answer.Form f = m.form();
            double from = -1, to = -1; double[][] syl = null;
            if (h != null && !h.pieces().isEmpty()) {
                from = h.pieces().get(0).start(); to = h.pieces().get(h.pieces().size() - 1).end();
                int ns = Pinyin.parse(f.py()).size();
                if (heard.equals(f.recJoined()) && h.pieces().size() == ns && ns > 1) {
                    syl = new double[ns][2];
                    for (int i = 0; i < ns; i++) { syl[i][0] = h.pieces().get(i).start(); syl[i][1] = h.pieces().get(i).end(); }
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
        if (r.tones() != null && r.tones().medianHz() > 0) cfg().addVoice(r.tones().medianHz());
        long now = System.currentTimeMillis();
        String tons = r.tones() != null ? r.tones().summary() : "";
        if (practice) {
            state = St.SHOW; showUntil = now + 5000;
            if (r.heardWord() == null) { good = false; color = 0xFFFFFF55; title = r.spoke() ? "Não reconheci nenhum item" : "Não ouvi nada"; line1 = r.heard().isEmpty() ? "" : "Ouvi: " + r.heard(); line2 = ""; line3 = ""; return; }
            Vocab.Word w = r.heardWord();
            good = r.pass(); color = r.pass() ? 0xFF55FF55 : 0xFFFF5555;
            title = (r.pass() ? "✓ " : "✗ tom: ") + w.zh + " " + w.pretty();
            line1 = w.pt; line2 = tons; line3 = "";
            if (!r.pass()) { playKey = w.key; playAt = now + 700; }
            return;
        }
        if (cur == null || word == null) { state = St.IDLE; return; }
        if (r.pass()) {
            sendAnswer(true, r.heard());
            good = true; color = 0xFF55FF55; state = St.SHOW; showUntil = now + 3000;
            title = "✓ " + word.zh + " " + word.pretty(); line1 = word.pt; line2 = tons; line3 = "";
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.6f, 0.6f));
            cur = null;
            return;
        }
        if (r.syllablesOk() && attempt <= cfg().retries) {
            attempt++;
            good = false; color = 0xFFFFFF55; state = St.RETRY_WAIT; retryAt = now + 2600;
            title = "Quase! As sílabas estão certas, o tom não:"; line1 = tons; line2 = ""; line3 = "";
            playKey = word.key; playAt = now + 300; retryCalibrate = false;
            return;
        }
        sendAnswer(false, r.heard());
        good = false; color = 0xFFFF5555; state = St.SHOW; showUntil = now + 6500;
        title = "✗ Era " + word.zh + " " + word.pretty();
        line1 = word.pt;
        line2 = !r.spoke() ? "Não ouvi nada a tempo." : r.syllablesOk() ? "Tons: " + tons : (r.heard().isEmpty() ? "Não entendi." : "Ouvi: " + r.heard());
        line3 = "Ouça e tente quando pegar de novo.";
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.7f));
        playKey = word.key; playAt = now + 900;
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
