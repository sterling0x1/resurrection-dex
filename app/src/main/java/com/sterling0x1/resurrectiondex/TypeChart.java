package com.sterling0x1.resurrectiondex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TypeChart {
    static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(
            "Normal", "Fire", "Water", "Electric", "Grass", "Ice", "Fighting", "Poison",
            "Ground", "Flying", "Psychic", "Bug", "Rock", "Ghost", "Dragon", "Dark", "Steel", "Fairy"
    ));

    static final class Matchups {
        final List<String> weak;
        final List<String> resist;
        final List<String> immune;

        Matchups(List<String> weak, List<String> resist, List<String> immune) {
            this.weak = weak;
            this.resist = resist;
            this.immune = immune;
        }
    }

    private final Map<String, Map<String, Double>> chart = new LinkedHashMap<>();

    TypeChart() {
        for (String attack : TYPES) chart.put(key(attack), new LinkedHashMap<>());
        put("normal", "rock", .5); put("normal", "ghost", 0); put("normal", "steel", .5);
        put("fire", "fire", .5); put("fire", "water", .5); put("fire", "grass", 2); put("fire", "ice", 2); put("fire", "bug", 2); put("fire", "rock", .5); put("fire", "dragon", .5); put("fire", "steel", 2);
        put("water", "fire", 2); put("water", "water", .5); put("water", "grass", .5); put("water", "ground", 2); put("water", "rock", 2); put("water", "dragon", .5);
        put("electric", "water", 2); put("electric", "electric", .5); put("electric", "grass", .5); put("electric", "ground", 0); put("electric", "flying", 2); put("electric", "dragon", .5);
        put("grass", "fire", .5); put("grass", "water", 2); put("grass", "grass", .5); put("grass", "poison", .5); put("grass", "ground", 2); put("grass", "flying", .5); put("grass", "bug", .5); put("grass", "rock", 2); put("grass", "dragon", .5); put("grass", "steel", .5);
        put("ice", "fire", .5); put("ice", "water", .5); put("ice", "grass", 2); put("ice", "ice", .5); put("ice", "ground", 2); put("ice", "flying", 2); put("ice", "dragon", 2); put("ice", "steel", .5);
        put("fighting", "normal", 2); put("fighting", "ice", 2); put("fighting", "poison", .5); put("fighting", "flying", .5); put("fighting", "psychic", .5); put("fighting", "bug", .5); put("fighting", "rock", 2); put("fighting", "ghost", 0); put("fighting", "dark", 2); put("fighting", "steel", 2); put("fighting", "fairy", .5);
        put("poison", "grass", 2); put("poison", "poison", .5); put("poison", "ground", .5); put("poison", "rock", .5); put("poison", "ghost", .5); put("poison", "steel", 0); put("poison", "fairy", 2);
        put("ground", "fire", 2); put("ground", "electric", 2); put("ground", "grass", .5); put("ground", "poison", 2); put("ground", "flying", 0); put("ground", "bug", .5); put("ground", "rock", 2); put("ground", "steel", 2);
        put("flying", "electric", .5); put("flying", "grass", 2); put("flying", "fighting", 2); put("flying", "bug", 2); put("flying", "rock", .5); put("flying", "steel", .5);
        put("psychic", "fighting", 2); put("psychic", "poison", 2); put("psychic", "psychic", .5); put("psychic", "dark", 0); put("psychic", "steel", .5);
        put("bug", "fire", .5); put("bug", "grass", 2); put("bug", "fighting", .5); put("bug", "poison", .5); put("bug", "flying", .5); put("bug", "psychic", 2); put("bug", "ghost", .5); put("bug", "dark", 2); put("bug", "steel", .5); put("bug", "fairy", .5);
        put("rock", "fire", 2); put("rock", "ice", 2); put("rock", "fighting", .5); put("rock", "ground", .5); put("rock", "flying", 2); put("rock", "bug", 2); put("rock", "steel", .5);
        put("ghost", "normal", 0); put("ghost", "psychic", 2); put("ghost", "ghost", 2); put("ghost", "dark", .5);
        put("dragon", "dragon", 2); put("dragon", "steel", .5); put("dragon", "fairy", 0);
        put("dark", "fighting", .5); put("dark", "psychic", 2); put("dark", "ghost", 2); put("dark", "dark", .5); put("dark", "fairy", .5);
        put("steel", "fire", .5); put("steel", "water", .5); put("steel", "electric", .5); put("steel", "ice", 2); put("steel", "rock", 2); put("steel", "steel", .5); put("steel", "fairy", 2);
        put("fairy", "fire", .5); put("fairy", "fighting", 2); put("fairy", "poison", .5); put("fairy", "dragon", 2); put("fairy", "dark", 2); put("fairy", "steel", .5);
    }

    Matchups defend(List<String> defenseTypes) {
        List<String> weak = new ArrayList<>();
        List<String> resist = new ArrayList<>();
        List<String> immune = new ArrayList<>();
        for (String attack : TYPES) {
            double value = 1.0;
            for (String defense : defenseTypes) value *= multiplier(attack, defense);
            if (value == 0) immune.add(attack + " ×0");
            else if (value > 1) weak.add(attack + format(value));
            else if (value < 1) resist.add(attack + format(value));
        }
        return new Matchups(weak, resist, immune);
    }

    private double multiplier(String attack, String defense) {
        Map<String, Double> row = chart.get(key(attack));
        if (row == null) return 1.0;
        return row.getOrDefault(key(defense), 1.0);
    }

    private void put(String attack, String defense, double value) {
        chart.get(key(attack)).put(key(defense), value);
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String format(double value) {
        if (value == .25) return " ×¼";
        if (value == .5) return " ×½";
        if (value == 2) return " ×2";
        if (value == 4) return " ×4";
        return " ×" + value;
    }
}
