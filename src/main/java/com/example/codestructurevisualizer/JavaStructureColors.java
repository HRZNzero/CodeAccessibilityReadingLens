package com.example.codestructurevisualizer;

import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;

import java.awt.Color;
import java.awt.Font;

final class JavaStructureColors {
    private JavaStructureColors() {
    }

    static final TextAttributes FIELD_GROUP = createBackground(new Color(0x45, 0x57, 0x6b));
    static final TextAttributes METHOD_GROUP = createBackground(new Color(0x34, 0x39, 0x3b));

    private static final TextAttributes[] METHOD_LEVELS = new TextAttributes[]{
            createBackground(new Color(0x34, 0x39, 0x3b)), // level 1: method main level
            createBackground(new Color(0x32, 0x38, 0x3b)), // level 2
            createBackground(new Color(0x36, 0x3f, 0x42)), // level 3
            createBackground(new Color(0x38, 0x43, 0x47)), // level 4
            createBackground(new Color(0x48, 0x5a, 0x61))  // level 5+
    };

    static TextAttributes methodLevel(int level) {
        int index = Math.max(1, Math.min(level, METHOD_LEVELS.length)) - 1;
        return METHOD_LEVELS[index];
    }

    private static TextAttributes createBackground(Color color) {
        return new TextAttributes(
                null,
                new JBColor(color, color),
                null,
                null,
                Font.PLAIN
        );
    }
}
