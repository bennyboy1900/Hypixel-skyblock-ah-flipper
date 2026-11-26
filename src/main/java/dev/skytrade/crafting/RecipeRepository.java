package dev.skytrade.crafting;

import com.google.gson.*;
import dev.skytrade.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Holds verified crafting recipes. Sources:
 * <ul>
 *   <li>the community-maintained NotEnoughUpdates item repository ({@code items/*.json}, crafting-table recipes only),</li>
 *   <li>an optional user file {@code recipes.json} for recipes the user has verified in-game.</li>
 * </ul>
 * Items without a recipe from one of these sources are simply not craftable — nothing is guessed.
 */
public final class RecipeRepository {
    private static final String[] SLOTS = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};
    private volatile Map<String, List<Recipe>> recipes = Map.of();

    public Map<String, List<Recipe>> all() {
        return recipes;
    }

    public List<Recipe> forOutput(String id) {
        return recipes.getOrDefault(id, List.of());
    }

    public int size() {
        return recipes.size();
    }

    /** Replaces all recipes (used by tests and offline tools). */
    public void replaceAll(Map<String, List<Recipe>> all) {
        Map<String, List<Recipe>> frozen = new HashMap<>();
        all.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        recipes = Collections.unmodifiableMap(frozen);
    }

    public synchronized void load(Path neuZip, Path userFile) {
        Map<String, List<Recipe>> map = new HashMap<>();
        if (neuZip != null && Files.exists(neuZip)) {
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(neuZip)))) {
                ZipEntry e;
                int files = 0;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    if (e.isDirectory() || !n.endsWith(".json") || !n.contains("/items/")) continue;
                    files++;
                    byte[] data = zin.readNBytes(4 * 1024 * 1024);
                    try {
                        JsonObject o = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (Recipe r : parseNeuItem(o)) map.computeIfAbsent(r.outputId(), k -> new ArrayList<>()).add(r);
                    } catch (RuntimeException ex) {
                        // malformed entry: skip
                    }
                }
                Log.info("recipes.neu_loaded", "files", files, "outputs", map.size());
            } catch (IOException ex) {
                Log.warn("recipes.neu_failed", "error", ex.getMessage());
            }
        }
        if (userFile != null && Files.exists(userFile)) {
            try (Reader r = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonArray()) {
                    for (JsonElement x : el.getAsJsonArray()) {
                        Recipe rec = parseUser(x.getAsJsonObject());
                        if (rec != null) map.computeIfAbsent(rec.outputId(), k -> new ArrayList<>()).add(rec);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                Log.warn("recipes.user_failed", "error", ex.getMessage());
            }
        }
        Map<String, List<Recipe>> frozen = new HashMap<>();
        map.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        recipes = Collections.unmodifiableMap(frozen);
    }

    /** Parses crafting-table recipes from one NEU item file. Other recipe types (forge, npc, ...) are ignored. */
    static List<Recipe> parseNeuItem(JsonObject o) {
        List<Recipe> out = new ArrayList<>();
        if (!o.has("internalname")) return out;
        String id = o.get("internalname").getAsString();
        if (o.has("recipe") && o.get("recipe").isJsonObject()) {
            Recipe r = parseGrid(id, o.getAsJsonObject("recipe"), "NEU repo");
            if (r != null) out.add(r);
        }
        if (o.has("recipes") && o.get("recipes").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("recipes")) {
                if (!el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                String type = r.has("type") ? r.get("type").getAsString() : "crafting";
                if (!"crafting".equals(type)) continue;
                Recipe rec = parseGrid(id, r, "NEU repo");
                if (rec != null && out.stream().noneMatch(x -> x.inputs().equals(rec.inputs()))) out.add(rec);
            }
        }
        return out;
    }

    static Recipe parseGrid(String outputId, JsonObject grid, String source) {
        Map<String, Integer> inputs = new TreeMap<>();
        for (String slot : SLOTS) {
            if (!grid.has(slot) || !grid.get(slot).isJsonPrimitive()) continue;
            String v = grid.get(slot).getAsString().trim();
            if (v.isEmpty()) continue;
            String item = v;
            int count = 1;
            int c = v.lastIndexOf(':');
            if (c > 0) {
                item = v.substring(0, c);
                try {
                    count = (int) Math.round(Double.parseDouble(v.substring(c + 1)));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (count <= 0 || item.isEmpty()) return null;
            inputs.merge(item, count, Integer::sum);
        }
        if (inputs.isEmpty()) return null;
        int outCount = 1;
        if (grid.has("count") && grid.get("count").isJsonPrimitive()) {
            try {
                outCount = Math.max(1, grid.get("count").getAsInt());
            } catch (NumberFormatException ignored) {
            }
        }
        if (inputs.containsKey(outputId)) return null; // self-referential
        return new Recipe(outputId, outCount, Collections.unmodifiableMap(inputs), source);
    }

    static Recipe parseUser(JsonObject o) {
        if (!o.has("output") || !o.has("inputs")) return null;
        Map<String, Integer> inputs = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("inputs").entrySet()) {
            int n = e.getValue().getAsInt();
            if (n > 0) inputs.put(e.getKey(), n);
        }
        if (inputs.isEmpty()) return null;
        int count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
        return new Recipe(o.get("output").getAsString(), count, Collections.unmodifiableMap(inputs), "user recipes.json");
    }
}
