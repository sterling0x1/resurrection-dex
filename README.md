# Resurrection Dex

Offline Android companion Pokédex designed for the **Anbernic RG DS** and
**Project Resurrection / pokeemerald-expansion**.

This is the first working foundation, focused on reliability:

- no internet permission;
- no Accessibility service;
- no OCR or screen capture;
- landscape 640×480-friendly two-pane interface;
- touch, D-pad, A/B/X and L/R navigation;
- import and permanently store `pokedex.csv` offline;
- import richer Resurrection JSON packs;
- type badges and complete defensive weakness/resistance calculation;
- optional stats, abilities, evolution and move sections when supplied by JSON.

## Current scope — v0.1

The APK ships with a small rich-data sample so the interface can be tested
immediately. For the complete ROM-specific species list, import the
`pokedex.csv` generated from your fork:

1. Open **Resurrection Dex** on the RG DS bottom display.
2. Press **Import CSV / JSON**.
3. Choose `dualscreendex_export/pokedex.csv`.
4. The app copies it into private storage and remains fully offline afterward.

CSV packs provide IDs, names and types. The app computes weaknesses,
resistances and immunities locally.

A future full exporter can add stats, abilities, evolutions, learnsets,
encounters, trainers, moves and items using the JSON schema demonstrated by
`app/src/main/assets/sample_dex.json`.

## Controls

| Input | Action |
|---|---|
| D-pad / touch | Navigate the species list |
| A / Enter | Open selected species |
| B | Clear search, then Android back |
| X / Search | Focus search |
| L / R | Previous / next species |

## Build

The repository includes a GitHub Actions workflow. Every push to `main` or
`master` builds and uploads:

- `app-debug.apk` — debug-signed and immediately installable;
- `app-release-unsigned.apk` — unsigned release build for your own key.

Local build with Android Studio or command-line Gradle:

```bash
gradle assembleDebug assembleRelease
```

The project uses:

- Android Gradle Plugin 8.11.0
- compile/target SDK 35
- minimum Android 8.0 / API 26
- Java 17
- no third-party runtime dependencies

## Signing the release APK

After downloading the unsigned release APK, align and sign it using your own
Android build-tools installation:

```bash
zipalign -p -f 4 app-release-unsigned.apk resurrection-dex-aligned.apk
apksigner sign \
  --ks your-release-key.jks \
  --out resurrection-dex.apk \
  resurrection-dex-aligned.apk
apksigner verify --verbose resurrection-dex.apk
```

## Convert the existing CSV to JSON

A small conversion helper is included:

```bash
python3 tools/csv_to_resurrection_json.py \
  /path/to/dualscreendex_export/pokedex.csv \
  resurrection_dex.json \
  --name "Project Resurrection"
```

This creates a basic JSON pack. It does not invent stats or learnsets that are
absent from the CSV.

## Data-pack schema

```json
{
  "manifest": {
    "name": "Project Resurrection",
    "formatVersion": 1,
    "romBuild": "optional build identifier"
  },
  "species": [
    {
      "id": 1,
      "name": "Bulbasaur",
      "types": ["grass", "poison"],
      "stats": {
        "hp": 45,
        "attack": 49,
        "defense": 49,
        "spAttack": 65,
        "spDefense": 65,
        "speed": 45
      },
      "abilities": ["Overgrow"],
      "hiddenAbility": "Chlorophyll",
      "evolutions": ["Level 16 → Ivysaur"],
      "moves": ["Tackle", "Growl", "Vine Whip"]
    }
  ]
}
```

## Privacy

The Android manifest deliberately requests no internet, accessibility,
screenshot or storage-wide permissions. File imports use Android's system file
picker and are copied into the app's private storage.

## Project status

This is an MVP foundation, not yet full PokeREX feature parity. The next data
milestone is a compiled `pokeemerald-expansion` exporter for complete stats,
abilities, evolutions and learnsets, followed by encounters, trainers, moves
and items.

Pokémon names and related trademarks belong to their respective owners. This
is an unofficial fan-made companion tool.
