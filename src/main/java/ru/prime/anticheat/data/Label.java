package ru.prime.anticheat.data;

/** Data collection session label for model training. */
public enum Label {
    CHEAT,
    LEGIT,
    UNLABELED;

    public static Label fromString(String value) {
        if (value == null) return null;
        try {
            return Label.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
