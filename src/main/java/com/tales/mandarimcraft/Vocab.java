package com.tales.mandarimcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tales.mandarimcraft.core.Pinyin;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** As palavras do mod: 汉字, pinyin, português e que itens do Minecraft cada uma cobre. */
public final class Vocab {
    private Vocab() {}

    /** rec = como a palavra entra na gramática do reconhecedor (palavras que o modelo conhece, separadas por espaço) */
    public record Alt(String zh, String py, String rec) {}

    public static final class Word {
        public final String key, zh, py, pt;
        public final List<Alt> alts;
        final List<Identifier> ids = new ArrayList<>();
        final List<TagKey<Item>> tags = new ArrayList<>();
        Word(String key, String zh, String py, String pt, List<Alt> alts) { this.key = key; this.zh = zh; this.py = py; this.pt = pt; this.alts = alts; }
        /** todas as formas aceitas (a principal e os apelidos) */
        public String rec = null;
        public List<Alt> forms() { List<Alt> f = new ArrayList<>(); f.add(new Alt(zh, py, rec == null ? zh : rec)); f.addAll(alts); return f; }
        public String pretty() { return Pinyin.pretty(py); }
    }

    private static final Map<String, Word> BY_KEY = new LinkedHashMap<>();
    private static final Map<Identifier, Word> BY_ID = new HashMap<>();
    private static final Map<Item, Optional<Word>> CACHE = new HashMap<>();

    public static synchronized void load() {
        if (!BY_KEY.isEmpty()) return;
        try (InputStream in = Vocab.class.getResourceAsStream("/mandarimcraft/vocab.json")) {
            if (in == null) { MandarimCraft.LOG.error("vocab.json não encontrado"); return; }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("words")) {
                JsonObject o = el.getAsJsonObject();
                List<Alt> alts = new ArrayList<>();
                for (JsonElement a : o.getAsJsonArray("alts")) {
                    JsonObject ao = a.getAsJsonObject();
                    alts.add(new Alt(ao.get("zh").getAsString(), ao.get("py").getAsString(), ao.has("rec") ? ao.get("rec").getAsString() : ao.get("zh").getAsString()));
                }
                Word w = new Word(o.get("key").getAsString(), o.get("zh").getAsString(), o.get("py").getAsString(), o.get("pt").getAsString(), alts);
                if (o.has("rec")) w.rec = o.get("rec").getAsString();
                JsonArray items = o.getAsJsonArray("items");
                for (JsonElement it : items) {
                    String s = it.getAsString();
                    if (s.startsWith("#")) {
                        Identifier id = Identifier.tryParse(s.substring(1));
                        if (id != null) w.tags.add(TagKey.of(RegistryKeys.ITEM, id));
                    } else {
                        Identifier id = Identifier.tryParse(s);
                        if (id != null) { w.ids.add(id); BY_ID.putIfAbsent(id, w); }
                    }
                }
                BY_KEY.put(w.key, w);
            }
            MandarimCraft.LOG.info("Mandarim Craft: {} palavras", BY_KEY.size());
        } catch (Exception e) {
            MandarimCraft.LOG.error("Erro lendo vocab.json", e);
        }
    }

    public static Word get(String key) { load(); return BY_KEY.get(key); }
    public static Collection<Word> all() { load(); return BY_KEY.values(); }

    /** a palavra que cobre este item (ou null se o item não está no vocabulário) */
    public static Word forStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        load();
        Item item = stack.getItem();
        Optional<Word> c = CACHE.get(item);
        if (c != null) return c.orElse(null);
        Word w = BY_ID.get(Registries.ITEM.getId(item));
        if (w == null) {
            outer:
            for (Word x : BY_KEY.values()) for (TagKey<Item> t : x.tags) if (stack.isIn(t)) { w = x; break outer; }
        }
        CACHE.put(item, Optional.ofNullable(w));
        return w;
    }

    /** as tags podem mudar quando os dados do mundo recarregam */
    public static void clearCache() { CACHE.clear(); }
}
