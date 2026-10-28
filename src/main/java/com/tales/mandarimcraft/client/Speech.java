package com.tales.mandarimcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tales.mandarimcraft.MandarimCraft;
import com.tales.mandarimcraft.Vocab;
import net.fabricmc.loader.api.FabricLoader;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reconhecimento de voz offline (Vosk, modelo chinês pequeno). Só escolhe entre as palavras do mod
 * (gramática fechada), o que deixa bem mais preciso para palavras soltas. Devolve o tempo de cada caractere.
 */
public final class Speech {
    private Speech() {}

    public record Piece(String text, double start, double end, double conf) {}
    public record Heard(String text, List<Piece> pieces) {
        public String joined() { return text.replace(" ", ""); }
    }

    private static Model model;
    private static String grammar;
    private static String status = "carregando";
    private static Set<String> modelWords = null;

    public static String status() { return status; }
    public static boolean ready() { return model != null; }

    /** carrega o modelo (tira do .jar na primeira vez). Rode fora da thread do jogo. */
    public static synchronized void load() {
        if (model != null) return;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("mandarimcraft").resolve("model");
            Path ok = dir.resolve(".pronto");
            if (!Files.exists(ok)) {
                status = "preparando o reconhecimento de voz (só na primeira vez)";
                unzipModel(dir);
                Files.writeString(ok, "ok");
            }
            Path root = findModelRoot(dir);
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(root.toString());
            modelWords = readWords(root);
            grammar = buildGrammar();
            status = "pronto";
            MandarimCraft.LOG.info("Vosk pronto: {} palavras no modelo", modelWords == null ? "?" : modelWords.size());
        } catch (Throwable t) {
            status = "erro: " + t.getMessage();
            MandarimCraft.LOG.error("Vosk não carregou", t);
        }
    }

    private static void unzipModel(Path dir) throws Exception {
        try (InputStream in = Speech.class.getResourceAsStream("/mandarimcraft/model.zip")) {
            if (in == null) throw new IllegalStateException("model.zip não está no mod");
            Files.createDirectories(dir);
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    Path out = dir.resolve(e.getName()).normalize();
                    if (!out.startsWith(dir)) continue;
                    if (e.isDirectory()) Files.createDirectories(out);
                    else { Files.createDirectories(out.getParent()); Files.copy(z, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
    }

    /** o zip tem uma pasta (vosk-model-small-cn-0.22/) com am/, conf/, graph/… */
    private static Path findModelRoot(Path dir) throws Exception {
        if (Files.isDirectory(dir.resolve("am"))) return dir;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isDirectory(p.resolve("am"))) return p;
        }
        throw new IllegalStateException("modelo incompleto em " + dir);
    }

    private static Set<String> readWords(Path root) {
        for (String rel : new String[]{"graph/words.txt", "words.txt"}) {
            Path f = root.resolve(rel);
            if (!Files.exists(f)) continue;
            try {
                Set<String> s = new HashSet<>();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int sp = line.indexOf(' ');
                    s.add(sp > 0 ? line.substring(0, sp) : line.trim());
                }
                return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** como escrever a palavra na gramática: inteira se o modelo conhece, senão caractere por caractere */
    static String phrase(String zh) {
        if (modelWords != null && modelWords.contains(zh)) return zh;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < zh.length(); i++) {
            String c = zh.substring(i, i + 1);
            if (b.length() > 0) b.append(' ');
            b.append(c);
        }
        return b.toString();
    }

    private static String buildGrammar() {
        JsonArray a = new JsonArray();
        Set<String> seen = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (Vocab.Word w : Vocab.all()) for (Vocab.Alt f : w.forms()) {
            String p = f.rec() != null && !f.rec().isEmpty() ? f.rec() : phrase(f.zh());
            if (modelWords != null) for (String tok : p.split(" ")) if (!modelWords.contains(tok)) missing.add(tok);
            if (seen.add(p)) a.add(p);
        }
        a.add("[unk]");
        if (!missing.isEmpty()) MandarimCraft.LOG.warn("Caracteres que o modelo não conhece: {}", missing);
        return a.toString();
    }

    /** reconhece um trecho de áudio 16 kHz (float). Devolve null se o modelo não está pronto. */
    public static Heard recognize(float[] pcm) {
        if (model == null) return null;
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            int s = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * 32767f)));
            b[2 * i] = (byte) (s & 0xff); b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        try (Recognizer rec = new Recognizer(model, 16000f, grammar)) {
            rec.setWords(true);
            rec.acceptWaveForm(b, b.length);
            JsonObject o = JsonParser.parseString(rec.getFinalResult()).getAsJsonObject();
            List<Piece> ps = new ArrayList<>();
            if (o.has("result")) for (JsonElement el : o.getAsJsonArray("result")) {
                JsonObject r = el.getAsJsonObject();
                ps.add(new Piece(r.get("word").getAsString(), r.get("start").getAsDouble(), r.get("end").getAsDouble(), r.has("conf") ? r.get("conf").getAsDouble() : 1.0));
            }
            String text = o.has("text") ? o.get("text").getAsString() : "";
            return new Heard(text.replace("[unk]", "").trim(), ps);
        } catch (Throwable t) {
            MandarimCraft.LOG.error("Erro no reconhecimento", t);
            return new Heard("", List.of());
        }
    }
}
