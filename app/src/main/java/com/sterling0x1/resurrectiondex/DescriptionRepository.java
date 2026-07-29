package com.sterling0x1.resurrectiondex;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

final class DescriptionRepository {
    private static final String TAG = "ResurrectionDexText";
    private static final String ASSET = "dex_descriptions.json";

    private final Context context;
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private int descriptionCount;

    DescriptionRepository(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    int count() {
        return descriptionCount;
    }

    String forEntry(PokemonEntry entry) {
        if (entry == null) return "";
        String key = canonical(entry.name);
        if (key.isEmpty()) return "";

        String entryKey = aliases.get(key);
        if (entryKey == null && key.endsWith("forme")) {
            entryKey = aliases.get(key.substring(0, key.length() - "forme".length()));
        }
        if (entryKey == null && key.endsWith("form")) {
            entryKey = aliases.get(key.substring(0, key.length() - "form".length()));
        }
        if (entryKey == null) return "";
        return descriptions.getOrDefault(entryKey, "");
    }

    private void load() {
        try (InputStream input = context.getAssets().open(ASSET)) {
            JSONObject root = new JSONObject(readUtf8(input));
            descriptionCount = root.optInt("descriptionCount", 0);

            JSONObject aliasObject = root.optJSONObject("aliases");
            if (aliasObject != null) {
                Iterator<String> iterator = aliasObject.keys();
                while (iterator.hasNext()) {
                    String alias = iterator.next();
                    String entryKey = aliasObject.optString(alias, "");
                    if (!alias.isEmpty() && !entryKey.isEmpty()) aliases.put(alias, entryKey);
                }
            }

            JSONObject entryObject = root.optJSONObject("entries");
            if (entryObject != null) {
                Iterator<String> iterator = entryObject.keys();
                while (iterator.hasNext()) {
                    String entryKey = iterator.next();
                    JSONObject item = entryObject.optJSONObject(entryKey);
                    if (item == null) continue;
                    String description = item.optString("description", "").trim();
                    if (!description.isEmpty()) descriptions.put(entryKey, description);
                }
            }
        } catch (IOException | JSONException error) {
            descriptionCount = 0;
            aliases.clear();
            descriptions.clear();
            Log.i(TAG, "No packaged expansion Pokédex descriptions were found.");
        }
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

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
