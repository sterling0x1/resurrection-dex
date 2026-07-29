package com.sterling0x1.resurrectiondex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PokemonEntry {
    final int id;
    final String name;
    final List<String> types;
    final Stats stats;
    final List<String> abilities;
    final String hiddenAbility;
    final List<String> evolutions;
    final List<String> moves;
    final String description;

    PokemonEntry(
            int id,
            String name,
            List<String> types,
            Stats stats,
            List<String> abilities,
            String hiddenAbility,
            List<String> evolutions,
            List<String> moves,
            String description
    ) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "Unknown" : name.trim();
        this.types = immutable(types);
        this.stats = stats == null ? Stats.unknown() : stats;
        this.abilities = immutable(abilities);
        this.hiddenAbility = hiddenAbility == null ? "" : hiddenAbility.trim();
        this.evolutions = immutable(evolutions);
        this.moves = immutable(moves);
        this.description = description == null ? "" : description.trim();
    }

    String displayId() {
        return String.format("#%03d", id);
    }

    String typeLine() {
        if (types.isEmpty()) return "UNKNOWN";
        return String.join(" / ", types).toUpperCase();
    }

    boolean hasRichData() {
        return stats.isKnown() || !abilities.isEmpty() || !evolutions.isEmpty() || !moves.isEmpty();
    }

    private static List<String> immutable(List<String> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    static final class Stats {
        final int hp;
        final int attack;
        final int defense;
        final int spAttack;
        final int spDefense;
        final int speed;

        Stats(int hp, int attack, int defense, int spAttack, int spDefense, int speed) {
            this.hp = hp;
            this.attack = attack;
            this.defense = defense;
            this.spAttack = spAttack;
            this.spDefense = spDefense;
            this.speed = speed;
        }

        static Stats unknown() {
            return new Stats(-1, -1, -1, -1, -1, -1);
        }

        boolean isKnown() {
            return hp >= 0 && attack >= 0 && defense >= 0 && spAttack >= 0 && spDefense >= 0 && speed >= 0;
        }
    }
}
