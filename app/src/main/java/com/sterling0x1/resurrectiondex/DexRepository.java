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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class DexRepository {
    private static final String SAVED_FILE = "active_dex_pack";
    private static final String SAVED_FORMAT = "active_dex_format";

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
            try (InputStream input = new FileInputStream(saved)) {
                return parse(input, format, "Imported Resurrection profile");
            }
        }
        try (InputStream input = context.getAssets().open("sample_dex.json")) {
            return parseJson(input, "Bundled sample — import your pokedex.csv");
        }
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

        File target = new File(context.getFilesDir(), SAVED_FILE);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        }
        context.getSharedPreferences("dex", Context.MODE_PRIVATE)
                .edit()
                .putString(SAVED_FORMAT, format)
                .apply();
        return parsed;
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
                String name = titleCase(parts[1].trim());
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
        return new LoadResult(result, label + " (JSON)", rich);
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
