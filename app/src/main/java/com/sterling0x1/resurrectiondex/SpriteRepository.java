package com.sterling0x1.resurrectiondex;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SpriteRepository {
    private static final String TAG = "ResurrectionSprites";
    private static final String INDEX_ASSET = "sprites/index.json";
    private static final String[] DEFAULT_SUFFIXES = {
            "normal", "default", "standard", "altered", "ordinary",
            "redstriped", "male", "plantcloak", "land", "incarnate",
            "midday", "amped", "fullbelly", "curly", "zero", "phony",
            "twosegment", "familyoffour", "greenplumage"
    };

    private final Context context;
    private final Map<String, String> aliases = new HashMap<>();
    private final List<String> keys = new ArrayList<>();
    private final LruCache<String, BitmapDrawable> cache = new LruCache<>(48);
    private int spriteCount;

    SpriteRepository(Context context) {
        this.context = context.getApplicationContext();
        loadIndex();
    }

    int count() {
        return spriteCount;
    }

    BitmapDrawable frontFor(PokemonEntry entry) {
        if (entry == null) return null;

        String assetPath = resolvePath(entry.name);
        if (assetPath == null) return null;

        BitmapDrawable cached = cache.get(assetPath);
        if (cached != null) return cached;

        try (InputStream input = context.getAssets().open(assetPath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap sheet = BitmapFactory.decodeStream(input, null, options);
            if (sheet == null) return null;

            int frameSize = Math.min(sheet.getWidth(), sheet.getHeight());
            Bitmap frame = sheet;
            if (sheet.getWidth() != frameSize || sheet.getHeight() != frameSize) {
                frame = Bitmap.createBitmap(sheet, 0, 0, frameSize, frameSize);
                if (frame != sheet) sheet.recycle();
            }
            frame.setDensity(Bitmap.DENSITY_NONE);

            BitmapDrawable drawable = new BitmapDrawable(context.getResources(), frame);
            drawable.setAntiAlias(false);
            drawable.setFilterBitmap(false);
            drawable.setDither(false);
            cache.put(assetPath, drawable);
            return drawable;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not load " + assetPath, error);
            return null;
        }
    }

    private void loadIndex() {
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            JSONObject root = new JSONObject(readUtf8(input));
            spriteCount = root.optInt("spriteCount", 0);
            JSONObject aliasObject = root.optJSONObject("aliases");
            if (aliasObject == null) return;

            Iterator<String> iterator = aliasObject.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                String path = aliasObject.optString(key, "");
                if (!key.isEmpty() && !path.isEmpty()) aliases.put(key, path);
            }
            keys.addAll(aliases.keySet());
            Collections.sort(keys);
        } catch (IOException | JSONException error) {
            spriteCount = 0;
            aliases.clear();
            keys.clear();
            Log.i(TAG, "No packaged expansion sprite index was found.");
        }
    }

    private String resolvePath(String displayName) {
        String key = canonical(displayName);
        if (key.isEmpty()) return null;

        String direct = aliases.get(key);
        if (direct != null) return direct;

        if (key.endsWith("forme")) {
            direct = aliases.get(key.substring(0, key.length() - "forme".length()));
            if (direct != null) return direct;
        }
        if (key.endsWith("form")) {
            direct = aliases.get(key.substring(0, key.length() - "form".length()));
            if (direct != null) return direct;
        }

        for (String suffix : DEFAULT_SUFFIXES) {
            direct = aliases.get(key + suffix);
            if (direct != null) return direct;
        }

        if (key.length() >= 4) {
            String best = null;
            for (String candidate : keys) {
                if (candidate.startsWith(key)
                        && (best == null || candidate.length() < best.length())) {
                    best = candidate;
                }
            }
            if (best != null) return aliases.get(best);
        }
        return null;
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
