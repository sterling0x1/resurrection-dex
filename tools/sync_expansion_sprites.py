#!/usr/bin/env python3
"""Package pokeemerald-expansion front sprites for Resurrection Dex.

Animated front sprites are normally indexed 64x128 PNG sheets containing two
64x64 frames. The original sheet is packaged and the first frame is cropped by
the Android app at runtime, preserving the source pixel art exactly.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import unicodedata
from pathlib import Path

DEFAULT_FORM_NAMES = {
    "altered", "amped", "aria", "average", "curly", "default",
    "familyoffour", "fullbelly", "greenplumage", "incarnate", "land",
    "male", "midday", "normal", "ordinary", "phony", "plantcloak",
    "redstriped", "standard", "twosegment", "zero",
}


def canonical(value: str) -> str:
    value = value.replace("♀", " f").replace("♂", " m")
    value = unicodedata.normalize("NFKD", value)
    return "".join(ch.lower() for ch in value if ch.isalnum())


def safe_name(parts: tuple[str, ...]) -> str:
    joined = "_".join(parts).lower()
    joined = unicodedata.normalize("NFKD", joined)
    joined = "".join(ch for ch in joined if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", "_", joined).strip("_")


def add_alias(aliases: dict[str, str], alias: str, asset_path: str) -> None:
    key = canonical(alias)
    if key:
        aliases.setdefault(key, asset_path)


def discover_sprites(graphics_root: Path) -> list[Path]:
    chosen: dict[Path, Path] = {}
    for source in sorted(graphics_root.rglob("anim_front.png")):
        chosen[source.parent] = source
    for source in sorted(graphics_root.rglob("front.png")):
        chosen.setdefault(source.parent, source)
    return [chosen[key] for key in sorted(chosen, key=lambda path: path.as_posix())]


def package_sprites(expansion_root: Path, output_root: Path) -> int:
    graphics_root = expansion_root / "graphics" / "pokemon"
    if not graphics_root.is_dir():
        raise FileNotFoundError(
            f"Could not find {graphics_root}. Pass the pokeemerald-expansion repository root."
        )

    sources = discover_sprites(graphics_root)
    if not sources:
        raise RuntimeError(f"No front sprites were found below {graphics_root}")

    temporary_root = output_root.with_name(output_root.name + ".tmp")
    shutil.rmtree(temporary_root, ignore_errors=True)
    front_root = temporary_root / "front"
    front_root.mkdir(parents=True)

    aliases: dict[str, str] = {}
    sprites: dict[str, dict[str, str]] = {}
    used_names: set[str] = set()

    for source in sources:
        relative_dir = source.parent.relative_to(graphics_root)
        parts = relative_dir.parts
        asset_name = safe_name(parts)
        if not asset_name:
            continue

        original_name = asset_name
        duplicate = 2
        while asset_name in used_names:
            asset_name = f"{original_name}_{duplicate}"
            duplicate += 1
        used_names.add(asset_name)

        destination = front_root / f"{asset_name}.png"
        shutil.copyfile(source, destination)
        asset_path = f"sprites/front/{destination.name}"

        add_alias(aliases, " ".join(parts), asset_path)
        add_alias(aliases, asset_name, asset_path)
        if len(parts) == 1:
            add_alias(aliases, parts[0], asset_path)
        elif canonical(parts[-1]) in DEFAULT_FORM_NAMES:
            add_alias(aliases, " ".join(parts[:-1]), asset_path)

        sprites[asset_name] = {
            "front": asset_path,
            "source": source.relative_to(expansion_root).as_posix(),
        }

    manifest = {
        "formatVersion": 1,
        "sourceRepository": "sterling0x1/pokeemerald-expansion",
        "spriteCount": len(sprites),
        "aliases": dict(sorted(aliases.items())),
        "sprites": dict(sorted(sprites.items())),
    }
    (temporary_root / "index.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    shutil.rmtree(output_root, ignore_errors=True)
    output_root.parent.mkdir(parents=True, exist_ok=True)
    temporary_root.rename(output_root)
    return len(sprites)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("expansion_root", type=Path)
    parser.add_argument(
        "output_root",
        nargs="?",
        type=Path,
        default=Path("app/src/main/assets/sprites"),
    )
    args = parser.parse_args()

    try:
        count = package_sprites(args.expansion_root.resolve(), args.output_root.resolve())
    except (FileNotFoundError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"Packaged {count} Pokémon front sprites into {args.output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
