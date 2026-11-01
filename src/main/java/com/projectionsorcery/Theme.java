package com.projectionsorcery;

/** Color themes. Add new entries here; the settings GUI cycles through all of them. */
public enum Theme {
    // label, hud, highlight, ghost rgb, ghost alpha (0-255), wireframe?, screen overlay rgb, overlay alpha,
    // replay overlay rgb, replay overlay alpha
    NAOYA("Naoya", 0x4FC3FF, 0xCFF4FF, 0x50C8FF, 65, false, 0x1E5AFF, 115, 0xFFFFFF, 28),
    NAOBITO("Naobito", 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0, true, 0x000000, 38, 0x000000, 48);

    public final String label;
    public final int primary;      // HUD text
    public final int highlight;
    public final int ghostRgb;     // afterimage color
    public final int ghostAlpha;   // afterimage opacity (ignored when wireframe)
    public final boolean wireframe; // fully transparent body with solid outlines
    public final int overlayRgb;   // tint over the planner's whole screen
    public final int overlayAlpha;
    public final int replayRgb;    // tint over the screen while the plan replays
    public final int replayAlpha;

    Theme(String label, int primary, int highlight, int ghostRgb, int ghostAlpha, boolean wireframe,
          int overlayRgb, int overlayAlpha, int replayRgb, int replayAlpha) {
        this.label = label;
        this.primary = primary;
        this.highlight = highlight;
        this.ghostRgb = ghostRgb;
        this.ghostAlpha = ghostAlpha;
        this.wireframe = wireframe;
        this.overlayRgb = overlayRgb;
        this.overlayAlpha = overlayAlpha;
        this.replayRgb = replayRgb;
        this.replayAlpha = replayAlpha;
    }

    public Theme next() {
        Theme[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
