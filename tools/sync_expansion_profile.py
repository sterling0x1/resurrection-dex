#!/usr/bin/env python3
"""Generate a rich Resurrection Dex profile from pokeemerald-expansion.

Version 2 supports species whose SpeciesInfo blocks are produced by
function-like C macros, such as UNOWN_MISC_INFO(...) and
GENESECT_SPECIES_INFO(...).

The exporter:
- keeps one richest record per NATIONAL_DEX_* identity;
- expands outer function-like and object-like SpeciesInfo macros;
- retains a species even if one optional field cannot be evaluated;
- extracts stats, types, abilities, evolutions, and level-up learnsets;
- optionally validates the generated profile against a Pokédex CSV roster.
"""

from __future__ import annotations

import argparse
import ast
import csv
import json
import operator
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

STAT_FIELDS = {
    "hp": "baseHP",
    "attack": "baseAttack",
    "defense": "baseDefense",
    "spAttack": "baseSpAttack",
    "spDefense": "baseSpDefense",
    "speed": "baseSpeed",
}

BINARY_OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.floordiv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
}

UNARY_OPERATORS = {
    ast.UAdd: operator.pos,
    ast.USub: operator.neg,
}

SPECIAL_NAMES = {
    "Mr Mime": "Mr. Mime",
    "Mime Jr": "Mime Jr.",
    "Ho Oh": "Ho-Oh",
    "Porygon Z": "Porygon-Z",
    "Type Null": "Type: Null",
    "Farfetchd": "Farfetch'd",
    "Sirfetchd": "Sirfetch'd",
    "Jangmo O": "Jangmo-o",
    "Hakamo O": "Hakamo-o",
    "Kommo O": "Kommo-o",
    "Nidoran F": "Nidoran♀",
    "Nidoran M": "Nidoran♂",
    "Flabebe": "Flabébé",
}

EVOLUTION_LABELS = {
    "EVO_FRIENDSHIP": "High friendship",
    "EVO_FRIENDSHIP_DAY": "High friendship during the day",
    "EVO_FRIENDSHIP_NIGHT": "High friendship at night",
    "EVO_TRADE": "Trade",
    "EVO_TRADE_ITEM": "Trade while holding {param}",
    "EVO_ITEM": "Use {param}",
    "EVO_ITEM_MALE": "Use {param} (male)",
    "EVO_ITEM_FEMALE": "Use {param} (female)",
    "EVO_LEVEL": "Level {param}",
    "EVO_LEVEL_MALE": "Level {param} (male)",
    "EVO_LEVEL_FEMALE": "Level {param} (female)",
    "EVO_LEVEL_DAY": "Level {param} during the day",
    "EVO_LEVEL_NIGHT": "Level {param} at night",
    "EVO_LEVEL_RAIN": "Level {param} while raining",
    "EVO_LEVEL_ATK_GT_DEF": "Level {param} with Attack higher than Defense",
    "EVO_LEVEL_ATK_EQ_DEF": "Level {param} with Attack equal to Defense",
    "EVO_LEVEL_ATK_LT_DEF": "Level {param} with Attack lower than Defense",
    "EVO_LEVEL_DARK_TYPE_MON_IN_PARTY": "Level {param} with a Dark-type ally",
    "EVO_LEVEL_NATURE_AMPED": "Level {param} with an Amped nature",
    "EVO_LEVEL_NATURE_LOW_KEY": "Level {param} with a Low Key nature",
    "EVO_MOVE": "Level up knowing {param}",
    "EVO_MOVE_TYPE": "Level up knowing a {param}-type move",
    "EVO_BEAUTY": "High Beauty",
    "EVO_MAPSEC": "Level up at {param}",
    "EVO_SPECIFIC_MAP": "Level up at {param}",
    "EVO_SPECIFIC_MON_IN_PARTY": "Level up with {param} in the party",
    "EVO_CRITICAL_HITS": "Land {param} critical hits in one battle",
    "EVO_SCRIPT_TRIGGER_DMG": "Take enough damage, then visit the required location",
    "EVO_DARK_SCROLL": "Use the Scroll of Darkness",
    "EVO_WATER_SCROLL": "Use the Scroll of Waters",
}


@dataclass(frozen=True)
class FunctionMacro:
    parameters: tuple[str, ...]
    body: str


@dataclass
class Candidate:
    first_order: int
    score: int
    species_token: str
    natdex_token: str
    row: dict[str, object]


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", text)


def prefer_modern_branches(text: str) -> str:
    output: list[str] = []
    stack: list[dict[str, object]] = []
    active = True

    for line in text.splitlines():
        directive = re.match(
            r"#\s*(if|ifdef|ifndef|elif|else|endif)\b(.*)",
            line.lstrip(),
        )
        if directive is None:
            if active:
                output.append(line)
            continue

        kind = directive.group(1)
        tail = directive.group(2).strip()

        if kind in {"if", "ifdef", "ifndef"}:
            parent = active
            explicitly_false = (
                kind == "if"
                and re.fullmatch(r"0+[uUlL]*", tail) is not None
            )
            first_selected = not explicitly_false
            stack.append({
                "parent": parent,
                "first": first_selected,
                "later": False,
            })
            active = parent and first_selected
        elif kind in {"elif", "else"}:
            if not stack:
                continue
            frame = stack[-1]
            if not bool(frame["first"]) and not bool(frame["later"]):
                frame["later"] = True
                active = bool(frame["parent"])
            else:
                active = False
        elif kind == "endif":
            if stack:
                active = bool(stack.pop()["parent"])

    return "\n".join(output)


def collapse_continuations(text: str) -> str:
    return re.sub(r"\\\s*\n", " ", text)


def canonical(value: str) -> str:
    value = value.replace("♀", " f").replace("♂", " m")
    value = unicodedata.normalize("NFKD", value)
    return "".join(character.lower() for character in value if character.isalnum())


def pretty_token(token: str, prefix: str = "") -> str:
    if prefix and token.startswith(prefix):
        token = token[len(prefix):]
    value = " ".join(word.capitalize() for word in token.split("_") if word)
    return SPECIAL_NAMES.get(value, value)


def strip_outer_parentheses(value: str) -> str:
    value = value.strip()
    while value.startswith("(") and value.endswith(")"):
        depth = 0
        encloses_all = True
        in_string = False
        escaped = False

        for index, character in enumerate(value):
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue

            if character == '"':
                in_string = True
            elif character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0 and index != len(value) - 1:
                    encloses_all = False
                    break

        if not encloses_all or depth != 0:
            break
        value = value[1:-1].strip()

    return value


def modern_ternary_value(expression: str) -> str:
    expression = strip_outer_parentheses(expression.strip())

    while "?" in expression:
        depth = 0
        question = -1
        question_depth = 0
        in_string = False
        escaped = False

        for index, character in enumerate(expression):
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue

            if character == '"':
                in_string = True
            elif character in "([{":
                depth += 1
            elif character in ")]}":
                depth -= 1
            elif character == "?":
                question = index
                question_depth = depth
                break

        if question < 0:
            break

        nested = 0
        colon = -1
        depth = question_depth
        in_string = False
        escaped = False

        for index in range(question + 1, len(expression)):
            character = expression[index]
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue

            if character == '"':
                in_string = True
            elif character in "([{":
                depth += 1
            elif character in ")]}":
                depth -= 1
            elif depth == question_depth:
                if character == "?":
                    nested += 1
                elif character == ":":
                    if nested:
                        nested -= 1
                    else:
                        colon = index
                        break

        if colon < 0:
            break

        expression = strip_outer_parentheses(
            expression[question + 1:colon].strip()
        )

    return expression


def split_top_level(value: str) -> list[str]:
    parts: list[str] = []
    start = 0
    depth = 0
    in_string = False
    escaped = False

    for index, character in enumerate(value):
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue

        if character == '"':
            in_string = True
        elif character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        elif character == "," and depth == 0:
            parts.append(value[start:index].strip())
            start = index + 1

    parts.append(value[start:].strip())
    return [part for part in parts if part]


def collect_macros(
    texts: Iterable[str],
) -> tuple[dict[str, str], dict[str, FunctionMacro]]:
    object_macros: dict[str, str] = {}
    function_macros: dict[str, FunctionMacro] = {}

    function_pattern = re.compile(
        r"^\s*#\s*define\s+([A-Z][A-Z0-9_]*)"
        r"\(([^)]*)\)\s*(.*?)\s*$"
    )
    object_pattern = re.compile(
        r"^\s*#\s*define\s+([A-Z][A-Z0-9_]*)\s+(.+?)\s*$"
    )

    for raw_text in texts:
        prepared = collapse_continuations(prefer_modern_branches(raw_text))
        for line in prepared.splitlines():
            function_match = function_pattern.match(line)
            if function_match:
                name, raw_parameters, body = function_match.groups()
                parameters = tuple(
                    parameter.strip()
                    for parameter in raw_parameters.split(",")
                    if parameter.strip()
                )
                function_macros.setdefault(
                    name,
                    FunctionMacro(parameters, body.strip()),
                )
                continue

            object_match = object_pattern.match(line)
            if object_match:
                name, body = object_match.groups()
                object_macros.setdefault(name, body.strip())

    return object_macros, function_macros


def safe_arithmetic(expression: str) -> int | None:
    expression = re.sub(r"(?<=\d)[uUlL]+\b", "", expression)

    try:
        tree = ast.parse(expression, mode="eval")
    except SyntaxError:
        return None

    def visit(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return visit(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return int(node.value)
        if isinstance(node, ast.BinOp) and type(node.op) in BINARY_OPERATORS:
            return int(BINARY_OPERATORS[type(node.op)](
                visit(node.left),
                visit(node.right),
            ))
        if isinstance(node, ast.UnaryOp) and type(node.op) in UNARY_OPERATORS:
            return int(UNARY_OPERATORS[type(node.op)](visit(node.operand)))
        raise ValueError("Unsupported arithmetic expression")

    try:
        return visit(tree)
    except (ValueError, ZeroDivisionError):
        return None


def evaluate_number(
    expression: str | None,
    macros: dict[str, str],
    seen: set[str] | None = None,
) -> int | None:
    if expression is None:
        return None

    seen = set() if seen is None else seen
    expression = strip_outer_parentheses(
        modern_ternary_value(expression.strip())
    )

    if re.fullmatch(r"[A-Z][A-Z0-9_]*", expression):
        if expression in seen or expression not in macros:
            return None
        seen.add(expression)
        return evaluate_number(macros[expression], macros, seen)

    return safe_arithmetic(expression)


def resolve_symbol(
    expression: str | None,
    prefix: str,
    macros: dict[str, str],
    seen: set[str] | None = None,
) -> str | None:
    if expression is None:
        return None

    seen = set() if seen is None else seen
    expression = strip_outer_parentheses(
        modern_ternary_value(expression.strip())
    )

    if (
        re.fullmatch(r"[A-Z][A-Z0-9_]*", expression)
        and not expression.startswith(prefix)
        and expression in macros
        and expression not in seen
    ):
        seen.add(expression)
        return resolve_symbol(macros[expression], prefix, macros, seen)

    match = re.search(rf"\b{re.escape(prefix)}[A-Z0-9_]+\b", expression)
    return match.group(0) if match else None


def substitute_function_macro(
    macro: FunctionMacro,
    arguments: list[str],
) -> str:
    body = macro.body
    mapping = {
        parameter: arguments[index].strip() if index < len(arguments) else ""
        for index, parameter in enumerate(macro.parameters)
    }

    for parameter in sorted(mapping, key=len, reverse=True):
        body = re.sub(
            rf"#\s*{re.escape(parameter)}\b",
            json.dumps(mapping[parameter]),
            body,
        )

    for parameter in sorted(mapping, key=len, reverse=True):
        body = re.sub(
            rf"\b{re.escape(parameter)}\b",
            mapping[parameter],
            body,
        )

    body = re.sub(r"\s*##\s*", "", body)
    return body.strip()


def is_species_data_macro(body: str) -> bool:
    """Return whether a macro contributes fields to a SpeciesInfo record."""
    markers = (
        ".baseHP",
        ".baseAttack",
        ".baseDefense",
        ".baseSpeed",
        ".baseSpAttack",
        ".baseSpDefense",
        ".types",
        ".abilities",
        ".speciesName",
        ".natDexNum",
        ".levelUpLearnset",
        ".evolutions",
    )
    return body.lstrip().startswith("{") or any(marker in body for marker in markers)


def matching_parenthesis(text: str, opening: int) -> int | None:
    depth = 0
    in_string = False
    escaped = False

    for index in range(opening, len(text)):
        character = text[index]

        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue

        if character == '"':
            in_string = True
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return index

    return None


def expand_one_embedded_function_macro(
    text: str,
    function_macros: dict[str, FunctionMacro],
) -> tuple[str, bool]:
    pattern = re.compile(r"\b([A-Z][A-Z0-9_]*)\s*\(")

    for match in pattern.finditer(text):
        name = match.group(1)
        macro = function_macros.get(name)
        if macro is None or not is_species_data_macro(macro.body):
            continue

        opening = text.find("(", match.start(1) + len(name))
        closing = matching_parenthesis(text, opening)
        if closing is None:
            continue

        arguments = split_top_level(text[opening + 1:closing])
        replacement = substitute_function_macro(macro, arguments)
        updated = text[:match.start(1)] + replacement + text[closing + 1:]
        if updated != text:
            return updated, True

    return text, False


def expand_one_embedded_object_macro(
    text: str,
    object_macros: dict[str, str],
) -> tuple[str, bool]:
    pattern = re.compile(r"\b([A-Z][A-Z0-9_]*)\b")

    for match in pattern.finditer(text):
        name = match.group(1)
        body = object_macros.get(name)
        if body is None or not is_species_data_macro(body):
            continue

        updated = text[:match.start(1)] + body + text[match.end(1):]
        if updated != text:
            return updated, True

    return text, False


def expand_species_expression(
    expression: str,
    object_macros: dict[str, str],
    function_macros: dict[str, FunctionMacro],
) -> str:
    """Expand both outer and embedded SpeciesInfo data macros.

    Several expansion species use a literal brace initializer that contains a
    second macro holding the real base stats and National Dex identity, e.g.
    VIVILLON_MISC_INFO(...), FLORGES_MISC_INFO(...), and MINIOR_MISC_INFO(...).
    Expanding only the outer right-hand side therefore loses those species.
    """
    expression = strip_outer_parentheses(expression.strip())

    for _ in range(256):
        changed = False

        # Expand an outer object/function macro first.
        object_match = re.fullmatch(r"([A-Z][A-Z0-9_]*)", expression)
        if object_match:
            body = object_macros.get(object_match.group(1))
            if body is not None and is_species_data_macro(body):
                expression = strip_outer_parentheses(body)
                changed = True

        if not changed:
            call_match = re.fullmatch(
                r"([A-Z][A-Z0-9_]*)\s*\((.*)\)",
                expression,
                flags=re.DOTALL,
            )
            if call_match:
                name, raw_arguments = call_match.groups()
                macro = function_macros.get(name)
                if macro is not None and is_species_data_macro(macro.body):
                    expression = strip_outer_parentheses(
                        substitute_function_macro(
                            macro,
                            split_top_level(raw_arguments),
                        )
                    )
                    changed = True

        # Then expand data-fragment macros nested inside a literal initializer.
        if not changed:
            expression, changed = expand_one_embedded_function_macro(
                expression,
                function_macros,
            )

        if not changed:
            expression, changed = expand_one_embedded_object_macro(
                expression,
                object_macros,
            )

        if not changed:
            break

    return re.sub(r"\s*##\s*", "", expression)

def field_expression(block: str, field: str) -> str | None:
    match = re.search(rf"\.{re.escape(field)}\s*=\s*", block)
    if match is None:
        return None

    start = match.end()
    depth = 0
    in_string = False
    escaped = False

    for index in range(start, len(block)):
        character = block[index]
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue

        if character == '"':
            in_string = True
        elif character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        elif character == "," and depth == 0:
            return block[start:index].strip()

    return block[start:].strip() or None


def iter_species_assignments(text: str) -> Iterable[tuple[str, str]]:
    prepared = strip_comments(
        collapse_continuations(prefer_modern_branches(text))
    )
    pattern = re.compile(r"\[(SPECIES_[A-Z0-9_]+)\]\s*=\s*")

    for match in pattern.finditer(prepared):
        start = match.end()
        depth = 0
        in_string = False
        escaped = False
        end = len(prepared)

        for index in range(start, len(prepared)):
            character = prepared[index]
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue

            if character == '"':
                in_string = True
            elif character in "([{":
                depth += 1
            elif character in ")]}":
                depth -= 1
            elif character == "," and depth == 0:
                end = index
                break

        expression = prepared[start:end].strip()
        if expression:
            yield match.group(1), expression


def parse_learnsets(
    files: list[Path],
    object_macros: dict[str, str],
) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}

    array_pattern = re.compile(
        r"static\s+const\s+struct\s+LevelUpMove\s+"
        r"(s[A-Za-z0-9_]+LevelUpLearnset)\s*\[\]\s*=\s*\{(.*?)\};",
        flags=re.DOTALL,
    )
    move_pattern = re.compile(
        r"LEVEL_UP_MOVE\s*\(\s*([^,]+?)\s*,\s*(MOVE_[A-Z0-9_]+)\s*\)"
    )

    for path in files:
        text = strip_comments(
            collapse_continuations(
                prefer_modern_branches(path.read_text(encoding="utf-8"))
            )
        )
        for array_match in array_pattern.finditer(text):
            name, body = array_match.groups()
            moves: list[str] = []
            seen: set[tuple[int, str]] = set()

            for move_match in move_pattern.finditer(body):
                level_expression, move_token = move_match.groups()
                level = evaluate_number(level_expression, object_macros)
                if level is None:
                    continue

                move_name = pretty_token(move_token, "MOVE_")
                key = (level, move_name)
                if key in seen:
                    continue
                seen.add(key)
                moves.append(f"Lv. {level} — {move_name}")

            if moves:
                result.setdefault(name, moves)

    return result


def extract_outer_braced_items(expression: str) -> list[str]:
    items: list[str] = []
    depth = 0
    start = -1

    for index, character in enumerate(expression):
        if character == "{":
            if depth == 0:
                start = index + 1
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0 and start >= 0:
                items.append(expression[start:index])
                start = -1

    return items


def human_parameter(value: str, macros: dict[str, str]) -> str:
    value = modern_ternary_value(value.strip())

    for prefix in ("ITEM_", "MOVE_", "SPECIES_", "TYPE_", "MAPSEC_"):
        token = resolve_symbol(value, prefix, macros)
        if token:
            return pretty_token(token, prefix)

    numeric = evaluate_number(value, macros)
    if numeric is not None:
        return str(numeric)

    cleaned = re.sub(r"[^A-Z0-9_]", "", value.upper())
    return pretty_token(cleaned)


def format_evolutions(
    expression: str | None,
    macros: dict[str, str],
) -> list[str]:
    if not expression:
        return []

    evolutions: list[str] = []

    for item in extract_outer_braced_items(expression):
        values = split_top_level(item)
        if len(values) < 2:
            continue

        method = resolve_symbol(values[0], "EVO_", macros)
        target = None
        target_index = -1

        for index in range(len(values) - 1, 0, -1):
            candidate = resolve_symbol(values[index], "SPECIES_", macros)
            if candidate:
                target = candidate
                target_index = index
                break

        if method is None or target is None:
            continue

        parameter_values = values[1:target_index]
        parameter = (
            human_parameter(parameter_values[0], macros)
            if parameter_values
            else ""
        )

        template = EVOLUTION_LABELS.get(method)
        if template:
            condition = template.format(param=parameter).strip()
        else:
            condition = pretty_token(method, "EVO_")
            if parameter and parameter != "0":
                condition += f" {parameter}"

        line = f"{condition} → {pretty_token(target, 'SPECIES_')}"
        if line not in evolutions:
            evolutions.append(line)

    return evolutions


def candidate_score(
    species_token: str,
    natdex_token: str,
    stats_known: bool,
    abilities: list[str],
    hidden_ability: str,
    evolutions: list[str],
    moves: list[str],
) -> int:
    score = 0
    if stats_known:
        score += 100
    if abilities:
        score += 20
    if hidden_ability:
        score += 5
    if evolutions:
        score += 10
    if moves:
        score += 20

    species_suffix = species_token.removeprefix("SPECIES_")
    natdex_suffix = natdex_token.removeprefix("NATIONAL_DEX_")
    if species_suffix == natdex_suffix:
        score += 50

    form_markers = (
        "_MEGA", "_GMAX", "_ALOLA", "_GALAR", "_HISUI", "_PALDEA",
        "_TOTEM", "_PRIMAL", "_THERIAN", "_ORIGIN", "_ZEN",
    )
    if not any(marker in species_suffix for marker in form_markers):
        score += 5

    return score


def parse_candidate(
    species_token: str,
    block: str,
    object_macros: dict[str, str],
    move_tables: dict[str, list[str]],
) -> tuple[str, dict[str, object], int] | None:
    natdex_token = resolve_symbol(
        field_expression(block, "natDexNum"),
        "NATIONAL_DEX_",
        object_macros,
    )
    if natdex_token is None:
        return None

    name_match = re.search(
        r'\.speciesName\s*=\s*_\(\s*"([^"]+)"\s*\)',
        block,
    )
    if name_match:
        name = name_match.group(1).replace("\\n", " ").strip()
    else:
        name = pretty_token(natdex_token, "NATIONAL_DEX_")

    stats: dict[str, int] = {}
    unresolved_stats: list[str] = []

    for output_name, source_field in STAT_FIELDS.items():
        value = evaluate_number(
            field_expression(block, source_field),
            object_macros,
        )
        if value is None:
            unresolved_stats.append(source_field)
        else:
            stats[output_name] = value

    stats_known = len(stats) == len(STAT_FIELDS)

    types: list[str] = []
    types_expression = field_expression(block, "types")
    if types_expression:
        macro_match = re.search(
            r"MON_TYPES\s*\((.*)\)",
            types_expression,
            flags=re.DOTALL,
        )
        arguments = (
            split_top_level(macro_match.group(1))
            if macro_match
            else [types_expression]
        )
        for argument in arguments:
            token = resolve_symbol(argument, "TYPE_", object_macros)
            if token and token != "TYPE_MYSTERY":
                type_name = pretty_token(token, "TYPE_")
                if type_name not in types:
                    types.append(type_name)

    abilities: list[str] = []
    hidden_ability = ""
    abilities_expression = field_expression(block, "abilities")

    if abilities_expression:
        values = split_top_level(
            abilities_expression.strip().strip("{}")
        )
        resolved = [
            resolve_symbol(value, "ABILITY_", object_macros)
            for value in values
        ]

        for token in resolved[:2]:
            if token and token != "ABILITY_NONE":
                ability_name = pretty_token(token, "ABILITY_")
                if ability_name not in abilities:
                    abilities.append(ability_name)

        hidden = resolved[2] if len(resolved) > 2 else None
        if hidden and hidden != "ABILITY_NONE":
            candidate = pretty_token(hidden, "ABILITY_")
            if candidate not in abilities:
                hidden_ability = candidate

    evolutions = format_evolutions(
        field_expression(block, "evolutions"),
        object_macros,
    )

    moves: list[str] = []
    learnset_expression = field_expression(block, "levelUpLearnset")
    if learnset_expression:
        reference = re.search(
            r"\b(s[A-Za-z0-9_]+LevelUpLearnset)\b",
            learnset_expression,
        )
        if reference:
            moves = move_tables.get(reference.group(1), [])

    row: dict[str, object] = {
        "id": 0,
        "name": name,
        "types": types,
    }
    if stats_known:
        row["stats"] = stats
    if abilities:
        row["abilities"] = abilities
    if hidden_ability:
        row["hiddenAbility"] = hidden_ability
    if evolutions:
        row["evolutions"] = evolutions
    if moves:
        row["moves"] = moves

    score = candidate_score(
        species_token,
        natdex_token,
        stats_known,
        abilities,
        hidden_ability,
        evolutions,
        moves,
    )

    if unresolved_stats:
        row["_unresolvedStats"] = unresolved_stats

    return natdex_token, row, score


def generate_profile(expansion_root: Path) -> dict[str, object]:
    species_root = (
        expansion_root / "src" / "data" / "pokemon" / "species_info"
    )
    learnset_root = (
        expansion_root / "src" / "data" / "pokemon" / "level_up_learnsets"
    )

    species_files = sorted(species_root.glob("gen_*_families.h"))
    macro_files = sorted(species_root.glob("*.h"))
    learnset_files = sorted(learnset_root.glob("*.h"))

    if not species_files:
        raise FileNotFoundError(
            f"No generation species tables found below {species_root}"
        )
    if not learnset_files:
        raise FileNotFoundError(
            f"No level-up learnset tables found below {learnset_root}"
        )

    macro_texts = [
        path.read_text(encoding="utf-8")
        for path in macro_files + learnset_files
    ]
    object_macros, function_macros = collect_macros(macro_texts)
    move_tables = parse_learnsets(learnset_files, object_macros)

    candidates: dict[str, Candidate] = {}
    assignment_order = 0
    expanded_macro_assignments = 0

    for path in species_files:
        text = path.read_text(encoding="utf-8")

        for species_token, expression in iter_species_assignments(text):
            assignment_order += 1
            if species_token in {"SPECIES_NONE", "SPECIES_EGG"}:
                continue

            original = strip_outer_parentheses(expression)
            expanded = expand_species_expression(
                expression,
                object_macros,
                function_macros,
            )
            if expanded != original:
                expanded_macro_assignments += 1

            if not expanded.lstrip().startswith("{"):
                continue

            parsed = parse_candidate(
                species_token,
                expanded,
                object_macros,
                move_tables,
            )
            if parsed is None:
                continue

            natdex_token, row, score = parsed
            current = candidates.get(natdex_token)

            if current is None:
                candidates[natdex_token] = Candidate(
                    first_order=assignment_order,
                    score=score,
                    species_token=species_token,
                    natdex_token=natdex_token,
                    row=row,
                )
            elif score > current.score:
                current.score = score
                current.species_token = species_token
                current.row = row

    ordered = sorted(candidates.values(), key=lambda item: item.first_order)
    output: list[dict[str, object]] = []
    unresolved: list[str] = []

    for national_id, candidate in enumerate(ordered, start=1):
        candidate.row["id"] = national_id
        unresolved_fields = candidate.row.pop("_unresolvedStats", None)
        if unresolved_fields:
            unresolved.append(candidate.species_token)
        output.append(candidate.row)

    if len(output) < 1025:
        print(
            f"warning: extracted {len(output)} National Pokédex species; "
            "roster validation will report the exact missing names.",
            file=sys.stderr,
        )

    if unresolved:
        preview = ", ".join(unresolved[:12])
        suffix = "" if len(unresolved) <= 12 else f" … and {len(unresolved) - 12} more"
        print(
            f"warning: retained {len(unresolved)} species with unresolved "
            f"base stats: {preview}{suffix}",
            file=sys.stderr,
        )

    return {
        "manifest": {
            "name": "Project Resurrection expansion profile",
            "formatVersion": 2,
            "formatLabel": "Generated expansion profile",
            "sourceRepository": "sterling0x1/pokeemerald-expansion",
            "speciesCount": len(output),
            "macroExpandedAssignments": expanded_macro_assignments,
            "unresolvedStatSpeciesCount": len(unresolved),
        },
        "species": output,
    }


def read_roster(path: Path) -> list[tuple[int, str]]:
    with path.open(encoding="utf-8-sig", newline="") as file:
        reader = csv.reader(file)
        rows: list[tuple[int, str]] = []

        for row in reader:
            if len(row) < 2:
                continue
            try:
                number = int(row[0].strip())
            except ValueError:
                continue
            rows.append((number, row[1].strip()))

        return rows


def repaired_roster_name(number: int, name: str) -> str:
    repairs = {
        29: "Nidoran♀",
        32: "Nidoran♂",
        669: "Flabébé",
    }
    return repairs.get(number, name)


def validate_roster(
    profile: dict[str, object],
    roster_path: Path,
) -> None:
    roster = read_roster(roster_path)
    species = profile["species"]

    generated_by_name: dict[str, dict[str, object]] = {}
    for entry in species:
        key = canonical(str(entry["name"]))
        current = generated_by_name.get(key)
        if current is None or len(entry) > len(current):
            generated_by_name[key] = entry

    ordered: list[dict[str, object]] = []
    missing: list[tuple[int, str]] = []

    for number, raw_name in roster:
        expected_name = repaired_roster_name(number, raw_name)
        entry = generated_by_name.get(canonical(expected_name))
        if entry is None:
            missing.append((number, expected_name))
            continue

        copied = dict(entry)
        copied["id"] = number
        copied["name"] = expected_name
        ordered.append(copied)

    if missing:
        lines = "\n".join(
            f"  - #{number:04d} {name}"
            for number, name in missing
        )
        raise RuntimeError(
            f"Generated profile is missing {len(missing)} roster species:\n"
            f"{lines}"
        )

    if len(ordered) != len(roster):
        raise RuntimeError(
            f"Roster alignment produced {len(ordered)} rows from "
            f"{len(roster)} roster entries."
        )

    profile["species"] = ordered
    profile["manifest"]["speciesCount"] = len(ordered)
    print(
        f"Validated and ordered all {len(roster)} roster entries against "
        f"{roster_path}"
    )

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("expansion_root", type=Path)
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        default=Path("app/src/main/assets/expansion_profile.json"),
    )
    parser.add_argument(
        "--roster",
        type=Path,
        help="Optional Pokédex CSV used to verify count, IDs, and names.",
    )
    args = parser.parse_args()

    try:
        profile = generate_profile(args.expansion_root.resolve())
        if args.roster is not None:
            validate_roster(profile, args.roster.resolve())
        elif profile["manifest"]["speciesCount"] < 1025:
            raise RuntimeError(
                "Generated profile is incomplete. Re-run with --roster to "
                "print the exact missing National Pokédex species."
            )
    except (FileNotFoundError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(profile, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)

    manifest = profile["manifest"]
    print(
        f"Packaged rich data for {manifest['speciesCount']} Pokémon into "
        f"{args.output}"
    )
    print(
        f"Expanded {manifest['macroExpandedAssignments']} macro-backed "
        "species assignments"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
