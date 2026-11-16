package dev.skycore.world;

public enum Dim {
    OVERWORLD("overworld", "Overworld"),
    NETHER("nether", "Nether"),
    END("end", "End");

    public final String id;
    public final String display;

    Dim(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public static Dim parse(String s) {
        if (s == null) return null;
        for (Dim d : values()) if (d.id.equalsIgnoreCase(s) || d.name().equalsIgnoreCase(s)) return d;
        throw new IllegalArgumentException("Unknown dimension: " + s);
    }
}
