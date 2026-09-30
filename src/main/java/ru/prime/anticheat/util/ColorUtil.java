package ru.prime.anticheat.util;

import net.md_5.bungee.api.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colors for configs: HEX (&#RRGGBB, {#RRGGBB}) + classic &-codes.
 * On servers below 1.16 (no RGB) HEX tokens are silently stripped.
 */
public final class ColorUtil {

    public static final Pattern HEX_AMP = Pattern.compile("&#([A-Fa-f0-9]{6})");
    public static final Pattern HEX_BRACE = Pattern.compile("\\{#([A-Fa-f0-9]{6})\\}");

    public static final boolean HEX_SUPPORTED = detectHex();

    private ColorUtil() {
    }

    public static String color(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String out = applyHex(s, HEX_AMP);
        out = applyHex(out, HEX_BRACE);
        return ChatColor.translateAlternateColorCodes('&', out);
    }

    public static List<String> color(List<String> list) {
        if (list == null) return new ArrayList<>(0);
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) out.add(color(s));
        return out;
    }

    /** Template + substitutions, coloring AT THE END (colors in values are colored too). */
    public static String render(String raw, String prefix, String... replacements) {
        String s = raw == null ? "" : raw.replace("%prefix%", prefix == null ? "" : prefix);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            s = s.replace(replacements[i], replacements[i + 1]);
        }
        return color(s);
    }

    /** Strip all colors (both HEX and &-codes) - for logs/console. */
    public static String strip(String s) {
        if (s == null) return "";
        String out = HEX_AMP.matcher(s).replaceAll("");
        out = HEX_BRACE.matcher(out).replaceAll("");
        out = ChatColor.translateAlternateColorCodes('&', out);
        return ChatColor.stripColor(out);
    }

    private static String applyHex(String s, Pattern pattern) {
        if (!HEX_SUPPORTED) return pattern.matcher(s).replaceAll("");
        Matcher m = pattern.matcher(s);
        StringBuffer buf = new StringBuffer(s.length() + 32);
        while (m.find()) {
            m.appendReplacement(buf, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(buf);
        return buf.toString();
    }

    private static boolean detectHex() {
        try {
            ChatColor.class.getMethod("of", String.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
