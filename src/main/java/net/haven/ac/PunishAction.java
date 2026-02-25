package net.haven.ac;

public enum PunishAction {
    NONE,
    SETBACK,
    KICK;

    public static PunishAction fromString(String s) {
        if (s == null) return NONE;
        try {
            return PunishAction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
