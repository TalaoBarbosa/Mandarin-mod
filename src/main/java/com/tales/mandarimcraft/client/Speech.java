package com.tales.mandarimcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
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
 *
 * Contexto: quem ouve já espera a resposta. O Vosk monta, com as frases da gramática, um modelo de bigramas pelas
 * contagens; então a palavra pedida entra mais vezes na lista (CONTEXT_WEIGHT) e, além disso, olhamos as NBEST
 * melhores hipóteses em vez de só a primeira: o reconhecedor costuma ficar entre duas ou três com nota quase igual.
 */
public final class Speech {
    private Speech() {}

    public record Piece(String text, double start, double end, double conf) {}
    public record Heard(String text, List<Piece> pieces) {
        public String joined() { return text.replace(" ", ""); }
    }

    /** quantas hipóteses olhar: a palavra pedida vale se estiver entre elas */
    public static final int NBEST = 3;
    /**
     * Quantas vezes a palavra pedida entra na gramática do desafio dela (1 = sem contexto). Medido com NBEST = 3:
     * nas gravações de nativos as palavras certas aceitas vão de 90–94% (peso 1, só a 1ª hipótese) para 96–98%, sem
     * passar a aceitar outra palavra qualquer; em fala muito ruim (voz sintética) vão de 13% para 27%, e outra palavra
     * qualquer passa nas sílabas 4% das vezes (era 0,4%). Com peso 20 seriam 33% e 7%. Depois das sílabas ainda vêm os tons.
     */
    public static final int CONTEXT_WEIGHT = 8;
    /** menos voz que isto (em quadros de 10 ms) no trecho da palavra não é fala: é ruído que o reconhecedor tomou pela palavra */
    public static final int MIN_VOICED = 8;

    // escritos na thread do reconhecimento e lidos na do jogo
    private static volatile Model model;
    private static String grammar;
    private static final Map<String, String> CONTEXT = new HashMap<>();
    private static volatile String status = "carregando";
    private static Set<String> modelWords = null;

    public static String status() { return status; }
    public static boolean ready() { return model != null; }

    /** carrega o modelo (tira do .jar na primeira vez). Rode fora da thread do jogo. */
    public static synchronized void load() {
        if (model != null) return;
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("mandarimcraft").resolve("model");
            Path ok = dir.resolve(".pronto");
            String stamp = modelStamp();
            if (!Files.exists(ok) || (stamp != null && !Files.readString(ok).equals(stamp))) {   // primeira vez, ou o mod veio com outro modelo
                status = "preparando o reconhecimento de voz (só na primeira vez)";
                deleteTree(dir);
                unzipModel(dir);
                Files.writeString(ok, stamp);
            }
            Path root = findModelRoot(dir);
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            bindUtf8();
            model = new Model(root.toString());
            modelWords = readWords(root);
            grammar = buildGrammar();
            status = "pronto";
            MandarimCraft.LOG.info("Vosk pronto: {} palavras no modelo", modelWords == null ? "?" : modelWords.size());
        } catch (Throwable t) {
            status = "erro: " + (t.getMessage() != null ? t.getMessage() : t.toString());
            MandarimCraft.LOG.error("Vosk não carregou", t);
        }
    }

    /**
     * O JNA converte texto pela codificação do sistema (no Windows em português, Cp1252), que não tem chinês:
     * a gramática chegava ao Vosk como "??" e nada era reconhecido. Estas duas chamadas vão em UTF-8; o resto
     * (inclusive o caminho do modelo, que pode ter acento no nome do usuário) continua como o Vosk faz.
     */
    private static Function setGrm, finalResult;

    private static void bindUtf8() {
        // a mesma biblioteca que o LibVosk acabou de registrar (mesmo nome e opções = mesma instância, sem carregar de novo)
        NativeLibrary lib = NativeLibrary.getInstance(Platform.isWindows() ? "libvosk" : "vosk",
                Collections.singletonMap(Library.OPTION_CLASSLOADER, LibVosk.class.getClassLoader()));
        setGrm = lib.getFunction("vosk_recognizer_set_grm", Function.C_CONVENTION, "UTF-8");
        finalResult = lib.getFunction("vosk_recognizer_final_result", Function.C_CONVENTION, "UTF-8");
    }

    /**
     * identifica o model.zip que veio no mod (a 1ª entrada é a pasta com nome e versão, ex.: vosk-model-small-cn-0.22/);
     * null se o mod veio sem ele (aí usa o que já foi extraído, se tiver)
     */
    private static String modelStamp() throws Exception {
        try (InputStream in = Speech.class.getResourceAsStream("/mandarimcraft/model.zip")) {
            if (in == null) return null;
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry e = z.getNextEntry();
                return "ok " + (e == null ? "" : e.getName());
            }
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
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

    private static String rec(Vocab.Alt f) { return f.rec() != null && !f.rec().isEmpty() ? f.rec() : phrase(f.zh()); }

    private static String buildGrammar() {
        JsonArray a = new JsonArray();
        Set<String> seen = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (Vocab.Word w : Vocab.all()) for (Vocab.Alt f : w.forms()) {
            String p = rec(f);
            if (modelWords != null) for (String tok : p.split(" ")) if (!modelWords.contains(tok)) missing.add(tok);
            if (seen.add(p)) a.add(p);
        }
        // sem "[unk]": com ele, a pronúncia de quem está aprendendo virava "não entendi"; sem ele o Vosk escolhe a
        // palavra mais parecida e quem decide é a comparação com a pedida e o tom (com só ruído ele continua devolvendo vazio)
        if (!missing.isEmpty()) MandarimCraft.LOG.warn("Caracteres que o modelo não conhece: {}", missing);
        return a.toString();
    }

    /** a gramática de todas as palavras, sem contexto (treino: vale qualquer item) */
    public static String grammar() { return grammar; }

    /** a gramática do desafio desta palavra: todas as palavras, com as formas da pedida repetidas (o contexto) */
    public static synchronized String grammarFor(Vocab.Word target) {
        if (target == null || grammar == null) return grammar;
        return CONTEXT.computeIfAbsent(target.key, k -> {
            JsonArray a = JsonParser.parseString(grammar).getAsJsonArray();
            for (int i = 1; i < CONTEXT_WEIGHT; i++) for (Vocab.Alt f : target.forms()) a.add(rec(f));
            return a.toString();
        });
    }

    /** pedaços seguidos (palavras do reconhecedor) que juntos dão exatamente este texto: [início, fim) ou null */
    public static int[] span(List<Piece> ps, String text) {
        for (int i = 0; i < ps.size(); i++) {
            StringBuilder b = new StringBuilder();
            for (int j = i; j < ps.size() && b.length() < text.length(); j++) {
                b.append(ps.get(j).text());
                if (b.toString().equals(text)) return new int[]{i, j + 1};
            }
        }
        return null;
    }

    /** início e fim de cada sílaba: pedaço de 1 caractere (os números) já é uma sílaba; o de mais (o nome) é dividido por igual. null se não bate */
    public static double[][] perSyllable(List<Piece> ps, int n) {
        int chars = 0;
        for (Piece p : ps) chars += p.text().length();
        if (chars != n) return null;
        double[][] out = new double[n][2];
        int k = 0;
        for (Piece p : ps) {
            int c = p.text().length();
            double d = (p.end() - p.start()) / c;
            for (int i = 0; i < c; i++, k++) { out[k][0] = p.start() + d * i; out[k][1] = p.start() + d * (i + 1); }
        }
        return out;
    }

    /**
     * Reconhece um trecho de áudio 16 kHz (float) com a gramática dada (lista JSON de frases). Devolve as nbest melhores
     * hipóteses, a preferida primeiro; lista vazia = só silêncio ou ruído; null = o modelo ainda não carregou.
     * Erro do reconhecedor vira exceção: quem chama decide (não pode contar como resposta errada do jogador).
     */
    public static List<Heard> recognize(float[] pcm, String grammar, int nbest) {
        if (model == null) return null;
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            int s = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * 32767f)));
            b[2 * i] = (byte) (s & 0xff); b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        try (Recognizer rec = new Recognizer(model, 16000f)) {
            setGrm.invokeVoid(new Object[]{rec.getPointer(), grammar});   // não new Recognizer(model, rate, grammar) nem getFinalResult(): veja bindUtf8
            rec.setMaxAlternatives(Math.max(1, nbest));
            rec.setWords(true);
            rec.acceptWaveForm(b, b.length);
            return parse(finalResult.invokeString(new Object[]{rec.getPointer()}, false));
        } catch (Exception e) {
            throw new IllegalStateException("erro no reconhecimento de voz", e);
        }
    }

    /** {"alternatives":[{"confidence":…,"result":[{"word","start","end"}…],"text":"…"}…]}; hipóteses vazias ou repetidas ficam de fora */
    static List<Heard> parse(String json) {
        List<Heard> out = new ArrayList<>();
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (!o.has("alternatives")) return out;
        Set<String> seen = new HashSet<>();
        for (JsonElement alt : o.getAsJsonArray("alternatives")) {
            JsonObject ao = alt.getAsJsonObject();
            String text = ao.has("text") ? ao.get("text").getAsString().replace("[unk]", "").trim() : "";
            if (text.isEmpty() || !seen.add(text.replace(" ", ""))) continue;
            List<Piece> ps = new ArrayList<>();
            if (ao.has("result")) for (JsonElement el : ao.getAsJsonArray("result")) {
                JsonObject r = el.getAsJsonObject();
                ps.add(new Piece(r.get("word").getAsString(), r.get("start").getAsDouble(), r.get("end").getAsDouble(), r.has("conf") ? r.get("conf").getAsDouble() : 1.0));
            }
            out.add(new Heard(text, ps));
        }
        return out;
    }
}
