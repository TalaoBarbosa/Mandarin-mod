package com.tales.mandarimcraft.client;

import com.tales.mandarimcraft.MandarimCraft;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Microfone: grava em 16 kHz mono e percebe quando a pessoa começou e terminou de falar
 * (energia acima do ruído de fundo; 0,7 s de silêncio depois de falar = terminou).
 */
public final class Mic {
    private TargetDataLine line;
    private Thread thread;
    private volatile boolean running;
    private float rate = 16000f;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public volatile boolean speechStarted, speechEnded;
    public volatile float level;          // 0..1 para a barrinha na tela
    public volatile String error;
    /**
     * a gravação só trouxe zeros (nem o chiado normal de um microfone): o sistema está bloqueando o mic
     * (Windows: Privacidade → Microfone; macOS: permissão do Minecraft/Java). Não é o jogador que ficou quieto.
     */
    public volatile boolean mute;
    /** quanto silêncio depois de falar conta como "terminou" (na calibração é maior: a frase tem vírgula) */
    public volatile int endSilenceMs = 700;

    private double noise = -1;
    private int loudRun = 0;
    private long lastVoiceMs = 0;

    public boolean start() {
        stopQuiet();
        synchronized (buf) { buf.reset(); }
        speechStarted = false; speechEnded = false; level = 0; error = null; mute = false; noise = -1; loudRun = 0;
        for (float r : new float[]{16000f, 48000f, 44100f}) {
            try {
                AudioFormat fmt = new AudioFormat(r, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) continue;
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(fmt, (int) (r * 0.2) * 2);
                line.start();
                rate = r;
                running = true;
                final TargetDataLine l = line;   // a thread fica com a própria linha: stop() pode zerar o campo antes dela terminar
                thread = new Thread(() -> loop(l), "mandarimcraft-mic");
                thread.setDaemon(true);
                thread.start();
                return true;
            } catch (Exception e) {
                MandarimCraft.LOG.warn("Microfone em {} Hz não abriu: {}", r, e.toString());
                try { if (line != null) line.close(); } catch (Exception ignored) {}
                line = null;
            }
        }
        error = "Não achei o microfone. Confira se ele está ligado e liberado para o Java/Minecraft.";
        return false;
    }

    private void loop(TargetDataLine l) {
        int frame = (int) (rate * 0.02) * 2;  // 20 ms
        byte[] b = new byte[frame];
        long t0 = System.currentTimeMillis();
        while (running && l.isOpen()) {   // linha fechada: read() voltaria 0 na hora e o laço giraria à toa
            int n = l.read(b, 0, b.length);
            if (n <= 0) continue;
            synchronized (buf) { buf.write(b, 0, n); }
            double e = 0; int cnt = n / 2;
            for (int i = 0; i + 1 < n; i += 2) { int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8)); e += (double) s * s; }
            double rms = Math.sqrt(e / Math.max(1, cnt)) / 32768.0;
            long now = System.currentTimeMillis();
            if (noise < 0) noise = rms;
            else if (!speechStarted) noise = Math.min(noise * 1.02 + 1e-5, rms < noise ? 0.9 * noise + 0.1 * rms : noise * 1.002);
            double thr = Math.max(0.012, noise * 3.5);
            level = (float) Math.min(1.0, rms / 0.12);
            if (rms > thr) {
                if (++loudRun >= 3 && now - t0 > 150) { speechStarted = true; lastVoiceMs = now; }
                if (speechStarted) lastVoiceMs = now;
            } else loudRun = 0;
            if (speechStarted && now - lastVoiceMs > endSilenceMs) speechEnded = true;
        }
    }

    /** para e devolve o áudio em 16 kHz (float -1..1) */
    public float[] stop() {
        running = false;
        try { if (thread != null) thread.join(300); } catch (InterruptedException ignored) {}
        try { if (line != null) { line.stop(); line.close(); } } catch (Exception ignored) {}
        line = null;
        byte[] raw;
        synchronized (buf) { raw = buf.toByteArray(); }
        int n = raw.length / 2, peak = 0;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
            x[i] = s / 32768f;
            peak = Math.max(peak, Math.abs(s));
        }
        mute = peak <= 2;
        return rate == 16000f ? x : resample(x, rate, 16000f);
    }

    public void stopQuiet() {
        if (running || line != null) stop();
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
