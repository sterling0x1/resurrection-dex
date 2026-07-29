#!/usr/bin/env python3
"""Convert DualScreenDex pokedex.csv into a basic Resurrection Dex JSON pack."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("output", type=Path, nargs="?", default=Path("resurrection_dex.json"))
    parser.add_argument("--name", default="Project Resurrection")
    args = parser.parse_args()

    species = []
    with args.csv_file.open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            try:
                dex_id = int(row["id"].strip())
            except (KeyError, TypeError, ValueError):
                continue
            name = (row.get("name") or "").strip()
            types = [
                value.strip().lower()
                for value in (row.get("type1", ""), row.get("type2", ""))
                if value and value.strip()
            ]
            if name:
                species.append({"id": dex_id, "name": name, "types": types})

    pack = {
        "manifest": {
            "name": args.name,
            "formatVersion": 1,
            "source": str(args.csv_file),
        },
        "species": sorted(species, key=lambda item: (item["id"], item["name"])),
    }
    args.output.write_text(json.dumps(pack, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(species)} species to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
