#!/usr/bin/env python3
"""Extract Pokédex descriptions from pokeemerald-expansion for Resurrection Dex.

The expansion stores most descriptions directly in species blocks and some in
shared text constants. This tool resolves both forms and writes one compact JSON
asset that the Android app can use completely offline.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
import unicodedata
from pathlib import Path

SPECIES_START = re.compile(r"\[\s*(SPECIES_[A-Z0-9_]+)\s*\]\s*=\s*\{")
SPECIES_NAME = re.compile(r'\.speciesName\s*=\s*_\(\s*("(?:\\.|[^"\\])*")\s*\)')
DESCRIPTION_COMPOUND = re.compile(r"\.description\s*=\s*COMPOUND_STRING\s*\(")
DESCRIPTION_SYMBOL = re.compile(r"\.description\s*=\s*(g[A-Za-z0-9_]+)\s*,")
SHARED_TEXT = re.compile(
    r"\b(?:static\s+)?const\s+u8\s+(g[A-Za-z0-9_]+)\s*\[\s*\]\s*=\s*(?:_|COMPOUND_STRING)\s*\("
)
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')


def canonical(value: str) -> str:
    value = value.replace("♀", " f").replace("♂", " m")
    value = unicodedata.normalize("NFKD", value)
    return "".join(ch.lower() for ch in value if ch.isalnum())


def decode_c_strings(expression: str) -> str:
    pieces: list[str] = []
    for token in STRING_LITERAL.findall(expression):
        try:
            pieces.append(ast.literal_eval(token))
        except (SyntaxError, ValueError):
            # Description text normally uses standard C/Python-compatible escapes.
            # Keep malformed text readable rather than aborting the whole export.
            pieces.append(token[1:-1].replace(r"\n", "\n").replace(r'\"', '"').replace(r"\\", "\\"))
    return " ".join("".join(pieces).split())


def find_balanced(text: str, opening_index: int, opening: str, closing: str) -> tuple[str, int]:
    depth = 0
    index = opening_index
    in_string = False
    escaped = False
    line_comment = False
    block_comment = False

    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""

        if line_comment:
            if char == "\n":
                line_comment = False
            index += 1
            continue
        if block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                index += 2
            else:
                index += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue

        if char == "/" and next_char == "/":
            line_comment = True
            index += 2
            continue
        if char == "/" and next_char == "*":
            block_comment = True
            index += 2
            continue
        if char == '"':
            in_string = True
            index += 1
            continue
        if char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return text[opening_index + 1:index], index + 1
        index += 1

    raise ValueError(f"Unbalanced {opening}{closing} expression")


def collect_shared_text(species_root: Path) -> dict[str, str]:
    shared: dict[str, str] = {}
    for path in sorted(species_root.rglob("*.h")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in SHARED_TEXT.finditer(text):
            try:
                expression, _ = find_balanced(text, match.end() - 1, "(", ")")
            except ValueError:
                continue
            description = decode_c_strings(expression)
            if description:
                shared[match.group(1)] = description
    return shared


def species_blocks(text: str):
    for match in SPECIES_START.finditer(text):
        opening = text.find("{", match.start())
        if opening < 0:
            continue
        try:
            body, end = find_balanced(text, opening, "{", "}")
        except ValueError:
            continue
        yield match.group(1), body, end


def extract_description(body: str, shared: dict[str, str]) -> str:
    direct = DESCRIPTION_COMPOUND.search(body)
    if direct:
        try:
            expression, _ = find_balanced(body, direct.end() - 1, "(", ")")
            description = decode_c_strings(expression)
            if description:
                return description
        except ValueError:
            pass

    symbol = DESCRIPTION_SYMBOL.search(body)
    if symbol:
        return shared.get(symbol.group(1), "")
    return ""


def add_alias(aliases: dict[str, str], alias: str, entry_key: str) -> None:
    key = canonical(alias)
    if key:
        aliases.setdefault(key, entry_key)


def extract_descriptions(expansion_root: Path, output_path: Path) -> int:
    species_root = expansion_root / "src" / "data" / "pokemon" / "species_info"
    if not species_root.is_dir():
        raise FileNotFoundError(
            f"Could not find {species_root}. Pass the pokeemerald-expansion repository root."
        )

    shared = collect_shared_text(species_root)
    aliases: dict[str, str] = {}
    entries: dict[str, dict[str, str]] = {}

    for path in sorted(species_root.rglob("*.h")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for species_constant, body, _ in species_blocks(text):
            description = extract_description(body, shared)
            if not description:
                continue

            constant_name = species_constant.removeprefix("SPECIES_")
            entry_key = canonical(constant_name)
            if not entry_key:
                continue

            name_match = SPECIES_NAME.search(body)
            display_name = ""
            if name_match:
                try:
                    display_name = ast.literal_eval(name_match.group(1))
                except (SyntaxError, ValueError):
                    display_name = name_match.group(1)[1:-1]

            entries.setdefault(entry_key, {
                "species": species_constant,
                "name": display_name,
                "description": description,
                "source": path.relative_to(expansion_root).as_posix(),
            })

            add_alias(aliases, constant_name, entry_key)
            if display_name:
                add_alias(aliases, display_name, entry_key)

                display_key = canonical(display_name)
                if entry_key.startswith(display_key) and len(entry_key) > len(display_key):
                    suffix = entry_key[len(display_key):]
                    add_alias(aliases, display_name + " " + suffix, entry_key)
                    add_alias(aliases, suffix + " " + display_name, entry_key)

    manifest = {
        "formatVersion": 1,
        "sourceRepository": "sterling0x1/pokeemerald-expansion",
        "descriptionCount": len(entries),
        "aliases": dict(sorted(aliases.items())),
        "entries": dict(sorted(entries.items())),
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output_path)
    return len(entries)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("expansion_root", type=Path)
    parser.add_argument(
        "output_path",
        nargs="?",
        type=Path,
        default=Path("app/src/main/assets/dex_descriptions.json"),
    )
    args = parser.parse_args()

    try:
        count = extract_descriptions(args.expansion_root.resolve(), args.output_path.resolve())
    except (FileNotFoundError, RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"Packaged {count} Pokédex descriptions into {args.output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
