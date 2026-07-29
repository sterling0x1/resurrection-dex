package com.sterling0x1.resurrectiondex;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DexRepository {
    private static final String SAVED_FILE = "active_dex_pack";
    private static final String SAVED_FORMAT = "active_dex_format";
    private static final String BUNDLED_PROFILE = "expansion_profile.json";
    private static final String BUNDLED_SAMPLE = "sample_dex.json";

    static final class LoadResult {
        final List<PokemonEntry> entries;
        final String sourceName;
        final boolean rich;

        LoadResult(List<PokemonEntry> entries, String sourceName, boolean rich) {
            this.entries = entries;
            this.sourceName = sourceName;
            this.rich = rich;
        }
    }

    LoadResult loadActive(Context context) throws IOException, JSONException {
        String format = context.getSharedPreferences("dex", Context.MODE_PRIVATE)
                .getString(SAVED_FORMAT, "");
        File saved = new File(context.getFilesDir(), SAVED_FILE);
        if (saved.isFile() && !format.isEmpty()) {
            LoadResult parsed;
            try (InputStream input = new FileInputStream(saved)) {
                parsed = parse(input, format, "Imported Resurrection profile");
            }

            // Migrate profiles saved by older builds, which stored raw CSV and
            // therefore discarded all richer fields after an app restart.
            if ("csv".equals(format)) {
                LoadResult merged = mergeCsvWithDetails(context, parsed, null);
                saveMergedProfile(context, merged);
                return merged;
            }

            // Re-enrich profiles saved by earlier builds from the current
            // generated expansion profile. Imported fields always win.
            return enrichWithBundled(context, parsed);
        }
        return loadBundledSample(context);
    }

    LoadResult importDocument(Context context, Uri uri) throws IOException, JSONException {
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Android returned no data for the selected file.");
            bytes = readAll(input);
        }

        String textStart = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8).trim();
        String format = textStart.startsWith("{") || textStart.startsWith("[") ? "json" : "csv";
        LoadResult parsed = parse(new ByteArrayInputStream(bytes), format, "Imported Resurrection profile");
        if (parsed.entries.isEmpty()) throw new IOException("The selected file contained no valid Pokémon rows.");

        if ("csv".equals(format)) {
            LoadResult previous = null;
            try {
                previous = loadActive(context);
            } catch (IOException | JSONException ignored) {
                // A broken previous profile must never prevent a fresh import.
            }

            LoadResult merged = mergeCsvWithDetails(context, parsed, previous);
            saveMergedProfile(context, merged);
            return merged;
        }

        saveProfile(context, bytes, "json");
        return enrichWithBundled(context, parsed);
    }

    private LoadResult loadBundledSample(Context context) throws IOException, JSONException {
        try (InputStream input = context.getAssets().open(BUNDLED_PROFILE)) {
            return parseJson(input, "Generated Project Resurrection profile");
        } catch (IOException missingGeneratedProfile) {
            try (InputStream input = context.getAssets().open(BUNDLED_SAMPLE)) {
                return parseJson(input, "Bundled sample — import your pokedex.csv");
            }
        }
    }

    private LoadResult enrichWithBundled(
            Context context,
            LoadResult profile
    ) throws IOException, JSONException {
        LoadResult bundled = loadBundledSample(context);
        DetailIndex fallback = new DetailIndex(bundled.entries);

        List<PokemonEntry> enriched = new ArrayList<>();
        boolean rich = false;
        for (PokemonEntry current : profile.entries) {
            PokemonEntry entry = mergeEntry(
                    current,
                    current,
                    fallback.find(current)
            );
            rich |= entry.hasRichData();
            enriched.add(entry);
        }

        sort(enriched);
        return new LoadResult(enriched, profile.sourceName, rich);
    }

    private LoadResult mergeCsvWithDetails(
            Context context,
            LoadResult csv,
            LoadResult preferredDetails
    ) throws IOException, JSONException {
        LoadResult bundled = loadBundledSample(context);
        DetailIndex preferred = preferredDetails == null ? null : new DetailIndex(preferredDetails.entries);
        DetailIndex fallback = new DetailIndex(bundled.entries);

        List<PokemonEntry> merged = new ArrayList<>();
        boolean rich = false;
        for (PokemonEntry basic : csv.entries) {
            PokemonEntry first = preferred == null ? null : preferred.find(basic);
            PokemonEntry second = fallback.find(basic);
            PokemonEntry entry = mergeEntry(basic, first, second);
            rich |= entry.hasRichData();
            merged.add(entry);
        }
        sort(merged);
        return new LoadResult(
                merged,
                "Imported Resurrection profile (CSV + preserved details)",
                rich
        );
    }

    private static PokemonEntry mergeEntry(
            PokemonEntry basic,
            PokemonEntry preferred,
            PokemonEntry fallback
    ) {
        PokemonEntry.Stats stats = firstKnownStats(preferred, fallback);
        List<String> abilities = firstList(preferred == null ? null : preferred.abilities,
                fallback == null ? null : fallback.abilities);
        String hiddenAbility = firstText(preferred == null ? "" : preferred.hiddenAbility,
                fallback == null ? "" : fallback.hiddenAbility);
        List<String> evolutions = firstList(preferred == null ? null : preferred.evolutions,
                fallback == null ? null : fallback.evolutions);
        List<String> moves = firstList(preferred == null ? null : preferred.moves,
                fallback == null ? null : fallback.moves);
        String description = firstText(preferred == null ? "" : preferred.description,
                fallback == null ? "" : fallback.description);

        List<String> types = basic.types.isEmpty()
                ? firstList(preferred == null ? null : preferred.types,
                        fallback == null ? null : fallback.types)
                : basic.types;

        return new PokemonEntry(
                basic.id,
                basic.name,
                types,
                stats,
                abilities,
                hiddenAbility,
                evolutions,
                moves,
                description
        );
    }

    private static PokemonEntry.Stats firstKnownStats(PokemonEntry... entries) {
        for (PokemonEntry entry : entries) {
            if (entry != null && entry.stats.isKnown()) return entry.stats;
        }
        return PokemonEntry.Stats.unknown();
    }

    @SafeVarargs
    private static <T> List<T> firstList(List<T>... values) {
        for (List<T> value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return new ArrayList<>();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static final class DetailIndex {
        private final Map<String, PokemonEntry> byName = new HashMap<>();
        private final Map<Integer, PokemonEntry> byId = new HashMap<>();

        DetailIndex(List<PokemonEntry> entries) {
            if (entries == null) return;
            for (PokemonEntry entry : entries) {
                String key = canonical(entry.name);
                if (!key.isEmpty()) byName.putIfAbsent(key, entry);
                byId.putIfAbsent(entry.id, entry);
            }
        }

        PokemonEntry find(PokemonEntry basic) {
            PokemonEntry match = byName.get(canonical(basic.name));
            return match != null ? match : byId.get(basic.id);
        }
    }

    private void saveMergedProfile(Context context, LoadResult result) throws IOException, JSONException {
        saveProfile(context, serialise(result), "json");
    }

    private void saveProfile(Context context, byte[] bytes, String format) throws IOException {
        File target = new File(context.getFilesDir(), SAVED_FILE);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        }
        context.getSharedPreferences("dex", Context.MODE_PRIVATE)
                .edit()
                .putString(SAVED_FORMAT, format)
                .apply();
    }

    private static byte[] serialise(LoadResult result) throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject manifest = new JSONObject();
        manifest.put("name", "Imported Resurrection profile");
        manifest.put("formatVersion", 1);
        manifest.put("formatLabel", "CSV + details");
        root.put("manifest", manifest);

        JSONArray species = new JSONArray();
        for (PokemonEntry entry : result.entries) {
            JSONObject item = new JSONObject();
            item.put("id", entry.id);
            item.put("name", entry.name);
            item.put("types", toJson(entry.types));

            if (entry.stats.isKnown()) {
                JSONObject stats = new JSONObject();
                stats.put("hp", entry.stats.hp);
                stats.put("attack", entry.stats.attack);
                stats.put("defense", entry.stats.defense);
                stats.put("spAttack", entry.stats.spAttack);
                stats.put("spDefense", entry.stats.spDefense);
                stats.put("speed", entry.stats.speed);
                item.put("stats", stats);
            }
            if (!entry.abilities.isEmpty()) item.put("abilities", toJson(entry.abilities));
            if (!entry.hiddenAbility.isEmpty()) item.put("hiddenAbility", entry.hiddenAbility);
            if (!entry.evolutions.isEmpty()) item.put("evolutions", toJson(entry.evolutions));
            if (!entry.moves.isEmpty()) item.put("moves", toJson(entry.moves));
            if (!entry.description.isEmpty()) item.put("description", entry.description);
            species.put(item);
        }
        root.put("species", species);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONArray toJson(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private LoadResult parse(InputStream input, String format, String sourceName) throws IOException, JSONException {
        if ("json".equals(format)) return parseJson(input, sourceName);
        return parseCsv(input, sourceName);
    }

    private LoadResult parseCsv(InputStream input, String sourceName) throws IOException {
        List<PokemonEntry> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 3) continue;
                int id;
                try {
                    id = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException ignored) {
                    continue; // header or malformed row
                }
                String name = repairCsvName(id, titleCase(parts[1].trim()));
                List<String> types = new ArrayList<>();
                addType(types, parts[2]);
                if (parts.length > 3) addType(types, parts[3]);
                result.add(new PokemonEntry(
                        id, name, types, PokemonEntry.Stats.unknown(),
                        new ArrayList<>(), "", new ArrayList<>(), new ArrayList<>(), ""
                ));
            }
        }
        sort(result);
        return new LoadResult(result, sourceName + " (CSV)", false);
    }

    private LoadResult parseJson(InputStream input, String sourceName) throws IOException, JSONException {
        String text = new String(readAll(input), StandardCharsets.UTF_8);
        JSONObject root;
        JSONArray species;
        if (text.trim().startsWith("[")) {
            root = new JSONObject();
            species = new JSONArray(text);
        } else {
            root = new JSONObject(text);
            species = root.optJSONArray("species");
            if (species == null) species = root.optJSONArray("pokemon");
            if (species == null) throw new JSONException("JSON must contain a species array.");
        }

        JSONObject manifest = root.optJSONObject("manifest");
        String manifestName = manifest == null ? "" : manifest.optString("name", "");
        String label = manifestName.isEmpty() ? sourceName : manifestName;
        String formatLabel = manifest == null ? "JSON" : manifest.optString("formatLabel", "JSON");

        List<PokemonEntry> result = new ArrayList<>();
        boolean rich = false;
        for (int i = 0; i < species.length(); i++) {
            JSONObject item = species.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("id", -1);
            String name = item.optString("name", "").trim();
            if (id < 0 || name.isEmpty()) continue;

            List<String> types = jsonStrings(item.optJSONArray("types"));
            if (types.isEmpty()) {
                addType(types, item.optString("type1", ""));
                addType(types, item.optString("type2", ""));
            }

            JSONObject statsJson = item.optJSONObject("stats");
            PokemonEntry.Stats stats = PokemonEntry.Stats.unknown();
            if (statsJson != null) {
                stats = new PokemonEntry.Stats(
                        statsJson.optInt("hp", -1),
                        statsJson.optInt("attack", statsJson.optInt("atk", -1)),
                        statsJson.optInt("defense", statsJson.optInt("def", -1)),
                        statsJson.optInt("spAttack", statsJson.optInt("spAtk", -1)),
                        statsJson.optInt("spDefense", statsJson.optInt("spDef", -1)),
                        statsJson.optInt("speed", statsJson.optInt("spe", -1))
                );
            }

            List<String> abilities = jsonStrings(item.optJSONArray("abilities"));
            String hidden = item.optString("hiddenAbility", item.optString("hidden_ability", ""));
            List<String> evolutions = jsonReadable(item.optJSONArray("evolutions"));
            List<String> moves = jsonReadable(item.optJSONArray("moves"));
            String description = item.optString("description", "");

            PokemonEntry entry = new PokemonEntry(
                    id, titleCase(name), types, stats, abilities, hidden,
                    evolutions, moves, description
            );
            rich |= entry.hasRichData();
            result.add(entry);
        }
        sort(result);
        return new LoadResult(result, label + " (" + formatLabel + ")", rich);
    }

    private static List<String> jsonStrings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) result.add(titleCase(value));
        }
        return result;
    }

    private static List<String> jsonReadable(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                String display = object.optString("display", "");
                if (display.isEmpty()) display = object.optString("name", object.toString());
                result.add(display);
            } else if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    private static void addType(List<String> result, String raw) {
        String type = raw == null ? "" : raw.trim();
        if (!type.isEmpty() && !"unknown".equalsIgnoreCase(type)) result.add(titleCase(type));
    }

    private static String repairCsvName(int id, String parsedName) {
        // The generated CSV has already replaced these Unicode characters
        // with '?'. National Pokédex IDs are stable, so repair the names here.
        switch (id) {
            case 29:
                return "Nidoran♀";
            case 32:
                return "Nidoran♂";
            case 669:
                return "Flabébé";
            default:
                return parsedName;
        }
    }

    private static String titleCase(String raw) {
        String value = raw == null ? "" : raw.trim().replace('-', ' ');
        if (value.isEmpty()) return value;
        String[] words = value.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String canonical(String value) {
        if (value == null) return "";
        String normalised = value.replace("♀", " f").replace("♂", " m");
        normalised = Normalizer.normalize(normalised, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);

        StringBuilder result = new StringBuilder(normalised.length());
        for (int index = 0; index < normalised.length(); index++) {
            char character = normalised.charAt(index);
            if (Character.isLetterOrDigit(character)) result.append(character);
        }
        return result.toString();
    }

    private static void sort(List<PokemonEntry> entries) {
        entries.sort(Comparator.comparingInt((PokemonEntry p) -> p.id).thenComparing(p -> p.name));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
