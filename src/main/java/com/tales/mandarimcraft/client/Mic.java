package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Microfone: grava em 16 kHz mono e percebe quando a pessoa começou e terminou de falar
 * (energia acima do ruído de fundo; 0,7 s de silêncio depois de falar = terminou).
 *
 * Abrir e fechar o microfone demora (no Windows, de dezenas a centenas de milissegundos): isso roda numa thread
 * própria, uma operação de cada vez e na ordem pedida, para o jogo não engasgar no começo e no fim de cada desafio.
 */
public final class Mic {
    private static final ExecutorService CTRL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "mandarimcraft-mic-ctl"); t.setDaemon(true); return t; });
    private static final float[] RATES = {16000f, 48000f, 44100f};

    /** uma gravação: cada start() cria a sua, então a thread de uma gravação velha nunca mexe no estado da nova */
    private static final class Rec {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile TargetDataLine line;
        volatile Thread thread;
        volatile boolean running, closed;
        volatile float rate = 16000f;
    }

    private volatile Rec cur;

    /** ainda abrindo o microfone: o que for dito agora não é gravado */
    public volatile boolean opening;
    public volatile boolean speechStarted, speechEnded;
    public volatile float level;          // 0..1 para a barrinha na tela
    public volatile String error;
    /**
     * a gravação só trouxe zeros (nem o chiado normal de um microfone): o sistema está bloqueando o mic
     * (Windows: Privacidade → Microfone; macOS: permissão do Minecraft/Java). Não é o jogador que ficou quieto.
     */
    public volatile boolean mute;
    /** maior amostra da última gravação (0..32767), para o log */
    public volatile int peak;
    /** quanto silêncio depois de falar conta como "terminou" (na calibração é maior: quem está aprendendo lê sílaba por sílaba) */
    public volatile int endSilenceMs = 700;
    /** o microfone que abriu na última gravação ("" = o padrão do sistema), para o log */
    public volatile String opened = "";
    private volatile float lastRate = 16000f;

    /** nomes dos microfones que o Java enxerga. Demora no Windows: chame fora da thread do jogo. */
    public static List<String> devices() {
        List<String> out = new ArrayList<>();
        Line.Info want = new Line.Info(TargetDataLine.class);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(want) && !out.contains(mi.getName())) out.add(mi.getName());
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** começa a abrir o microfone escolhido nos ajustes e volta na hora; veja opening e error */
    public void start() { start(ClientConfig.get().mic); }

    /** device = nome de devices(), ou "" para o padrão do sistema */
    public void start(String device) {
        final Rec old = cur, r = new Rec();
        cur = r;
        speechStarted = false; speechEnded = false; level = 0; error = null; mute = false; opening = true;
        CTRL.execute(() -> { if (old != null) close(old); open(r, device == null ? "" : device); });
    }

    public boolean active() { return cur != null; }

    private void open(Rec r, String device) {
        if (cur != r) return;   // já foi cancelada antes de abrir
        // o microfone escolhido e, se ele sumiu (headset desligado), o padrão do sistema
        for (String dev : device.isEmpty() ? new String[]{""} : new String[]{device, ""}) {
            for (float rate : RATES) {
                TargetDataLine l = null;
                try {
                    AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
                    l = line(dev, fmt);
                    if (l == null) continue;
                    l.open(fmt, (int) (rate * 0.2) * 2);
                    l.start();
                    r.line = l; r.rate = rate; r.running = true;
                    Thread t = new Thread(() -> loop(r), "mandarimcraft-mic");
                    t.setDaemon(true);
                    r.thread = t;
                    t.start();
                    if (cur == r) { opened = dev; lastRate = rate; opening = false; }
                    if (!dev.equals(device)) MandarimCraft.LOG.warn("Microfone '{}' não abriu: usando o padrão do sistema", device);
                    return;
                } catch (Exception e) {
                    MandarimCraft.LOG.warn("Microfone '{}' em {} Hz não abriu: {}", dev.isEmpty() ? "padrão" : dev, rate, e.toString());
                    try { if (l != null) l.close(); } catch (Exception ignored) {}
                }
            }
        }
        if (cur == r) { error = "Não achei o microfone. Veja se está ligado e liberado no sistema."; opening = false; }
    }

    private static TargetDataLine line(String device, AudioFormat fmt) throws Exception {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (device.isEmpty()) return AudioSystem.isLineSupported(info) ? (TargetDataLine) AudioSystem.getLine(info) : null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!mi.getName().equals(device)) continue;
            Mixer m = AudioSystem.getMixer(mi);
            if (m.isLineSupported(info)) return (TargetDataLine) m.getLine(info);
        }
        return null;
    }

    private void loop(Rec r) {
        final TargetDataLine l = r.line;
        int frame = (int) (r.rate * 0.02) * 2;  // 20 ms
        byte[] b = new byte[frame];
        long t0 = System.currentTimeMillis(), lastVoiceMs = 0;
        double noise = -1;
        int loudRun = 0;
        boolean started = false;
        while (r.running && l.isOpen()) {   // linha fechada: read() voltaria 0 na hora e o laço giraria à toa
            int n = l.read(b, 0, b.length);
            if (n <= 0) continue;
            synchronized (r.buf) { r.buf.write(b, 0, n); }
            double e = 0; int cnt = n / 2;
            for (int i = 0; i + 1 < n; i += 2) { int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8)); e += (double) s * s; }
            double rms = Math.sqrt(e / Math.max(1, cnt)) / 32768.0;
            long now = System.currentTimeMillis();
            if (noise < 0) noise = rms;
            else if (!started) noise = Math.min(noise * 1.02 + 1e-5, rms < noise ? 0.9 * noise + 0.1 * rms : noise * 1.002);
            double thr = Math.max(0.006, noise * 3.5);   // piso -44 dBFS: com 0.012, voz baixa de mic de notebook passava batida
            boolean mine = cur == r;   // uma gravação que já foi trocada por outra não mexe mais no que a tela lê
            if (mine) level = (float) Math.min(1.0, rms / 0.12);
            if (rms > thr) {
                if (++loudRun >= 3 && now - t0 > 150) { started = true; lastVoiceMs = now; if (mine) speechStarted = true; }
                if (started) lastVoiceMs = now;
            } else loudRun = 0;
            if (started && now - lastVoiceMs > endSilenceMs && mine) speechEnded = true;
        }
    }

    private static void close(Rec r) {
        if (r.closed) return;
        r.closed = true; r.running = false;
        Thread t = r.thread;
        try { if (t != null) t.join(300); } catch (InterruptedException ignored) {}
        TargetDataLine l = r.line;
        try { if (l != null) { l.stop(); l.close(); } } catch (Exception ignored) {}
    }

    /** para e devolve o áudio em 16 kHz (float -1..1). Espera o microfone fechar: chame fora da thread do jogo. */
    public float[] stop() {
        final Rec r = cur;
        cur = null; opening = false; level = 0;
        if (r == null) return new float[0];
        try { CTRL.submit(() -> close(r)).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        byte[] raw;
        synchronized (r.buf) { raw = r.buf.toByteArray(); }
        int n = raw.length / 2, peak = 0;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
            x[i] = s / 32768f;
            peak = Math.max(peak, Math.abs(s));
        }
        this.peak = peak;
        mute = peak <= 2;
        return r.rate == 16000f ? x : resample(x, r.rate, 16000f);
    }

    /** a taxa em que o microfone abriu (o áudio devolvido por stop() é sempre 16 kHz) */
    public float rate() { return lastRate; }

    /** larga a gravação sem esperar (cancelamento, sair do mundo): fecha em segundo plano */
    public void stopQuiet() {
        final Rec r = cur;
        cur = null; opening = false; level = 0;
        if (r != null) CTRL.execute(() -> close(r));
    }

    static float[] resample(float[] x, float from, float to) {
        int n = (int) ((long) x.length * to / from);
        float[] y = new float[n];
        double step = from / to;
        for (int i = 0; i < n; i++) {
            double p = i * step; int a = (int) p; double f = p - a;
            // média simples da janela (filtro anti-serrilhado barato) + interpolação
            int w = (int) Math.max(1, Math.floor(step));
            double s = 0; int c = 0;
            for (int k = 0; k < w && a + k < x.length; k++) { s += x[a + k]; c++; }
            double v = c > 0 ? s / c : 0;
            if (w == 1 && a + 1 < x.length) v = x[a] * (1 - f) + x[a + 1] * f;
            y[i] = (float) v;
        }
        return y;
    }
}
