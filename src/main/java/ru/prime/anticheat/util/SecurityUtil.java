package ru.prime.anticheat.util;

import java.util.regex.Pattern;

/** Minimum for safe string handling: file names, sanitize. */
public final class SecurityUtil {

    public static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[^a-zA-Z0-9_.-]");
    public static final int MAX_FILE_NAME_FRAGMENT = 32;

    private SecurityUtil() {
    }

    /** Model probability is only meaningful in [0,1]; anything else is a broken API response. */
    public static boolean isValidProbability(double probability) {
        return !Double.isNaN(probability) && probability >= 0.0 && probability <= 1.0;
    }

    /** Nickname is safe to substitute into a console command (otherwise - kick via API). */
    public static boolean isSafeCommandName(String name) {
        if (name == null || name.length() < 2 || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }

    /** Collapses an external string into a safe file name fragment (no separators or ..). */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        String cleaned = UNSAFE_FILE_CHARS.matcher(name).replaceAll("_");
        while (cleaned.contains("..")) cleaned = cleaned.replace("..", "_");
        if (cleaned.length() > MAX_FILE_NAME_FRAGMENT) cleaned = cleaned.substring(0, MAX_FILE_NAME_FRAGMENT);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
