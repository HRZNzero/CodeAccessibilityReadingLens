package com.example.codestructurevisualizer;

import java.awt.Color;

/**
 * Centralises every background colour and its opacity used by the plugin.
 * All colours are raw AWT Colors; semi-transparency is applied in
 * JavaStructureHighlighterService via AlphaComposite.
 */
final class JavaStructureColors {
    private JavaStructureColors() {}

    // ── field group ────────────────────────────────────────────────────────────
    static final Color FIELD_GROUP_COLOR = new Color(0x45, 0x57, 0x6b);
    static final float FIELD_GROUP_ALPHA = 0.75f;

    // ── method / nested-block levels ───────────────────────────────────────────
    // Colours become slightly more teal-tinted at deeper nesting levels so that
    // each block is visually distinct but still subtle.
    private static final Color[] METHOD_LEVEL_COLORS = {
            new Color(0x30, 0x36, 0x38),  // level 1 – method outer shell
            new Color(0x2a, 0x35, 0x3d),  // level 2 – first nested block (if/for/…)
            new Color(0x2c, 0x3b, 0x44),  // level 3
            new Color(0x2e, 0x40, 0x4b),  // level 4
            new Color(0x36, 0x4e, 0x58),  // level 5+
    };

    // Deeper nesting gets progressively lower alpha so stacked layers don't
    // overwhelm the text; the colour difference makes each level distinct.
    private static final float[] METHOD_LEVEL_ALPHAS = {
            0.75f,  // level 1
            0.55f,  // level 2
            0.50f,  // level 3
            0.45f,  // level 4
            0.40f,  // level 5+
    };

    static Color methodLevelColor(int level) {
        int i = Math.max(1, Math.min(level, METHOD_LEVEL_COLORS.length)) - 1;
        return METHOD_LEVEL_COLORS[i];
    }

    static float methodLevelAlpha(int level) {
        int i = Math.max(1, Math.min(level, METHOD_LEVEL_ALPHAS.length)) - 1;
        return METHOD_LEVEL_ALPHAS[i];
    }

    // ── inspection-mode colours ────────────────────────────────────────────────
    /** Dim overlay that covers every line outside the active block. */
    static final Color INSPECTION_DIM_COLOR    = new Color(0x18, 0x1a, 0x1b);
    static final float INSPECTION_DIM_ALPHA    = 0.90f;

    /** Slightly-brighter overlay for the currently-active code block. */
    static final Color INSPECTION_ACTIVE_COLOR = new Color(0x2e, 0x38, 0x3d);
    static final float INSPECTION_ACTIVE_ALPHA = 0.75f;
}
