package com.example.backchathelper.fabric;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MiniMessage subset → vanilla Component (hex, named colors, italic/bold, gradients). */
final class MiniNick {
    private static final Pattern TAG = Pattern.compile("<(/)?([^>]+)>");

    private MiniNick() {}

    static Component parse(String nick, String fallback) {
        if (nick == null || nick.isBlank() || "RESET".equals(nick)) {
            return Component.literal(fallback);
        }
        try {
            Component parsed = parseMini(nick);
            if (parsed.getString().isBlank()) {
                return Component.literal(fallback);
            }
            return parsed;
        } catch (Exception e) {
            String plain = nick.replaceAll("<[^>]+>", "").trim();
            return Component.literal(plain.isEmpty() ? fallback : plain);
        }
    }

    private static Component parseMini(String input) {
        Matcher m = TAG.matcher(input);
        List<Style> stack = new ArrayList<>();
        stack.add(Style.EMPTY);
        MutableComponent out = Component.empty();
        int cursor = 0;
        while (m.find()) {
            if (m.start() > cursor) {
                out.append(Component.literal(input.substring(cursor, m.start())).withStyle(peek(stack)));
            }
            boolean close = m.group(1) != null;
            String raw = m.group(2).trim();
            if (close) {
                if (stack.size() > 1) {
                    stack.remove(stack.size() - 1);
                }
            } else if (raw.toLowerCase(Locale.ROOT).startsWith("gradient:")) {
                int end = findClose(input, m.end(), "gradient");
                String body = end < 0 ? input.substring(m.end()) : input.substring(m.end(), end);
                out.append(gradient(body, raw.substring("gradient:".length()), peek(stack)));
                if (end < 0) {
                    return out;
                }
                m.region(end + "</gradient>".length(), input.length());
                cursor = end + "</gradient>".length();
                continue;
            } else {
                stack.add(applyTag(peek(stack), raw));
            }
            cursor = m.end();
        }
        if (cursor < input.length()) {
            out.append(Component.literal(input.substring(cursor)).withStyle(peek(stack)));
        }
        return out;
    }

    private static int findClose(String input, int from, String name) {
        String close = "</" + name + ">";
        return input.indexOf(close, from);
    }

    private static Style peek(List<Style> stack) {
        return stack.get(stack.size() - 1);
    }

    private static Style applyTag(Style style, String raw) {
        String tag = raw.toLowerCase(Locale.ROOT);
        if (tag.equals("i") || tag.equals("em") || tag.equals("italic")) {
            return style.withItalic(true);
        }
        if (tag.equals("b") || tag.equals("bold")) {
            return style.withBold(true);
        }
        if (tag.equals("u") || tag.equals("underlined")) {
            return style.withUnderlined(true);
        }
        if (tag.startsWith("#") || tag.startsWith("color:#") || tag.startsWith("colour:#")) {
            String hex = tag.substring(tag.indexOf('#'));
            Integer rgb = parseHex(hex);
            return rgb == null ? style : style.withColor(rgb);
        }
        Integer named = namedColor(tag);
        return named == null ? style : style.withColor(named);
    }

    private static Component gradient(String body, String spec, Style base) {
        String plain = body.replaceAll("<[^>]+>", "");
        List<Integer> stops = new ArrayList<>();
        for (String part : spec.split(":")) {
            Integer rgb = parseHex(part);
            if (rgb == null) {
                rgb = namedColor(part.toLowerCase(Locale.ROOT));
            }
            if (rgb != null) {
                stops.add(rgb);
            }
        }
        if (plain.isEmpty()) {
            return Component.empty();
        }
        if (stops.size() < 2) {
            Style style = stops.isEmpty() ? base : base.withColor(stops.get(0));
            return Component.literal(plain).withStyle(style);
        }
        MutableComponent out = Component.empty();
        int n = plain.length();
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0f : (float) i / (n - 1);
            float scaled = t * (stops.size() - 1);
            int idx = Math.min(stops.size() - 2, (int) Math.floor(scaled));
            float local = scaled - idx;
            int rgb = lerp(stops.get(idx), stops.get(idx + 1), local);
            out.append(Component.literal(String.valueOf(plain.charAt(i))).withStyle(base.withColor(rgb)));
        }
        return out;
    }

    private static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static Integer parseHex(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer namedColor(String name) {
        TextColor named = switch (name) {
            case "black" -> TextColor.BLACK;
            case "dark_blue" -> TextColor.DARK_BLUE;
            case "dark_green" -> TextColor.DARK_GREEN;
            case "dark_aqua" -> TextColor.DARK_AQUA;
            case "dark_red" -> TextColor.DARK_RED;
            case "dark_purple" -> TextColor.DARK_PURPLE;
            case "gold" -> TextColor.GOLD;
            case "gray", "grey" -> TextColor.GRAY;
            case "dark_gray", "dark_grey" -> TextColor.DARK_GRAY;
            case "blue" -> TextColor.BLUE;
            case "green" -> TextColor.GREEN;
            case "aqua" -> TextColor.AQUA;
            case "red" -> TextColor.RED;
            case "light_purple", "pink" -> TextColor.LIGHT_PURPLE;
            case "yellow" -> TextColor.YELLOW;
            case "white" -> TextColor.WHITE;
            default -> null;
        };
        return named == null ? null : named.getValue();
    }
}
