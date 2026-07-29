package com.sterling0x1.resurrectiondex;

import android.graphics.Color;

import java.util.Locale;

final class TypeColors {
    private TypeColors() {}

    static int color(String type) {
        if (type == null) return Color.rgb(96, 96, 96);
        switch (type.toLowerCase(Locale.ROOT)) {
            case "normal": return Color.rgb(145, 145, 125);
            case "fire": return Color.rgb(238, 90, 47);
            case "water": return Color.rgb(65, 130, 221);
            case "electric": return Color.rgb(241, 195, 35);
            case "grass": return Color.rgb(79, 168, 71);
            case "ice": return Color.rgb(80, 190, 195);
            case "fighting": return Color.rgb(190, 55, 45);
            case "poison": return Color.rgb(155, 75, 165);
            case "ground": return Color.rgb(205, 165, 78);
            case "flying": return Color.rgb(125, 145, 215);
            case "psychic": return Color.rgb(225, 75, 125);
            case "bug": return Color.rgb(145, 165, 35);
            case "rock": return Color.rgb(175, 145, 55);
            case "ghost": return Color.rgb(95, 80, 145);
            case "dragon": return Color.rgb(95, 60, 205);
            case "dark": return Color.rgb(85, 70, 65);
            case "steel": return Color.rgb(145, 145, 165);
            case "fairy": return Color.rgb(220, 125, 165);
            default: return Color.rgb(96, 96, 96);
        }
    }
}
