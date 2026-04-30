package me.netizendev.codestructurevisualizer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

/**
 * Swing dialog for toggling and colouring individual syntax-emphasis tokens.
 *
 * <p>Each row has:
 * <ul>
 *   <li>a checkbox to enable/disable the token type</li>
 *   <li>a hex-colour text field (empty = bold only, no colour override)</li>
 *   <li>a live preview swatch that updates as the user types</li>
 * </ul>
 * Pressing <b>OK</b> commits the changes and immediately refreshes all open Java
 * editors.  <b>Cancel</b> discards them without touching the settings.</p>
 */
public final class SyntaxEmphasisConfigDialog extends DialogWrapper {

    private static final Color INVALID_HEX_BG = new Color(0x8B, 0x00, 0x00, 80);

    private final Project project;
    private final Map<SyntaxEmphasisSettings.TokenKind, JCheckBox>  checks = new EnumMap<>(SyntaxEmphasisSettings.TokenKind.class);
    private final Map<SyntaxEmphasisSettings.TokenKind, JTextField>  fields = new EnumMap<>(SyntaxEmphasisSettings.TokenKind.class);
    private final Map<SyntaxEmphasisSettings.TokenKind, JPanel>     swatches = new EnumMap<>(SyntaxEmphasisSettings.TokenKind.class);

    public SyntaxEmphasisConfigDialog(@NotNull Project project) {
        super(project, /* canBeParent */ true);
        this.project = project;
        setTitle("Syntax Emphasis Configuration");
        setOKButtonText("Apply & Close");
        init();
    }

    // ── Panel construction ─────────────────────────────────────────────────────

    @Override
    protected @Nullable JComponent createCenterPanel() {
        SyntaxEmphasisSettings settings = SyntaxEmphasisSettings.getInstance(project);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(8, 8, 8, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets  = new Insets(3, 6, 3, 6);
        gbc.anchor  = GridBagConstraints.WEST;
        gbc.fill    = GridBagConstraints.NONE;

        // ── header row ─────────────────────────────────────────────────────────
        addHeader(grid, gbc, 0, "Token");
        addHeader(grid, gbc, 1, "Bold");
        addHeader(grid, gbc, 2, "Hex colour  (empty = bold only)");
        addHeader(grid, gbc, 3, "");   // swatch column

        // ── one row per TokenKind ──────────────────────────────────────────────
        int row = 1;
        for (SyntaxEmphasisSettings.TokenKind kind : SyntaxEmphasisSettings.TokenKind.values()) {
            gbc.gridy = row;

            // label
            gbc.gridx = 0;
            grid.add(new JLabel(kind.label), gbc);

            // checkbox
            JCheckBox cb = new JCheckBox();
            cb.setSelected(settings.isEnabled(kind));
            checks.put(kind, cb);
            gbc.gridx = 1;
            grid.add(cb, gbc);

            // hex text field
            JTextField tf = new JTextField(settings.colorHex(kind), 12);
            tf.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            fields.put(kind, tf);
            gbc.gridx = 2;
            gbc.fill  = GridBagConstraints.HORIZONTAL;
            grid.add(tf, gbc);
            gbc.fill  = GridBagConstraints.NONE;

            // live colour swatch
            JPanel swatch = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Color c = parseHex(tf.getText());
                    if (c != null) {
                        g.setColor(c);
                        g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 4, 4);
                    } else if (!tf.getText().isBlank()) {
                        // invalid hex – show red tint
                        g.setColor(INVALID_HEX_BG);
                        g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 4, 4);
                    }
                }
            };
            swatch.setPreferredSize(new Dimension(22, 18));
            swatch.setOpaque(false);
            swatches.put(kind, swatch);
            // repaint swatch when text changes
            tf.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e)  { swatch.repaint(); }
                @Override public void removeUpdate(DocumentEvent e)  { swatch.repaint(); }
                @Override public void changedUpdate(DocumentEvent e) { swatch.repaint(); }
            });
            gbc.gridx = 3;
            grid.add(swatch, gbc);

            row++;
        }

        // hint label at the bottom
        JLabel hint = new JLabel("<html><i>Tip: leave Hex colour blank to keep the normal"
                + " syntax colour and only add bold weight.</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(new EmptyBorder(10, 6, 2, 6));

        JPanel outer = new JPanel(new BorderLayout());
        outer.add(grid, BorderLayout.NORTH);
        outer.add(hint, BorderLayout.SOUTH);

        JScrollPane scroll = new JScrollPane(outer,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(520, 400));
        return scroll;
    }

    // ── Commit ─────────────────────────────────────────────────────────────────

    @Override
    protected void doOKAction() {
        SyntaxEmphasisSettings settings = SyntaxEmphasisSettings.getInstance(project);
        for (SyntaxEmphasisSettings.TokenKind kind : SyntaxEmphasisSettings.TokenKind.values()) {
            settings.setEnabled(kind, checks.get(kind).isSelected());
            settings.setColorHex(kind, fields.get(kind).getText().trim());
        }
        SyntaxEmphasisService.getInstance(project).refreshAllOpenJavaEditorsNow();
        super.doOKAction();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void addHeader(JPanel grid, GridBagConstraints gbc, int col, String text) {
        gbc.gridx = col;
        gbc.gridy = 0;
        JLabel lbl = new JLabel("<html><b>" + text + "</b></html>");
        grid.add(lbl, gbc);
    }

    private static @Nullable Color parseHex(@Nullable String hex) {
        if (hex == null || hex.isBlank()) return null;
        try {
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

