package me.netizendev.codestructurevisualizer;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDoWhileStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiSynchronizedStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiWhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Manages bookmarks ("markers") on individual lines.
 *
 * <ul>
 *   <li>Toggle a marker on the caret line – {@link #toggleMarker}</li>
 *   <li>Jump to the next / previous marker – {@link #jumpToNextMarker} / {@link #jumpToPrevMarker}</li>
 *   <li>Focus mode – grays out and folds all non-marked regions –
 *       {@link #toggleFocusMode}</li>
 * </ul>
 *
 * Markers are retained for the lifetime of the project session (not persisted
 * across IDE restarts – use the regular Bookmarks feature for that).
 */
@Service(Service.Level.PROJECT)
public final class MarkerService implements Disposable {

    // ── user-data keys ────────────────────────────────────────────────────────
    private static final Key<List<RangeHighlighter>> MARKER_HL_KEY =
            Key.create("csv.marker.highlights");
    private static final Key<List<RangeHighlighter>> FOCUS_HL_KEY =
            Key.create("csv.marker.focus.highlights");
    private static final Key<List<RangeHighlighter>> BOUNDARY_HL_KEY =
            Key.create("csv.marker.boundary.highlights");

    // ── state ─────────────────────────────────────────────────────────────────
    /** file-URL → sorted set of marked line numbers (0-based) */
    private final Map<String, TreeSet<Integer>> fileMarkers = new HashMap<>();
    /** editor → fold regions we created so we can remove them later */
    private final Map<Editor, List<FoldRegion>> editorFolds = new WeakHashMap<>();
    private boolean focusModeEnabled = false;

    private final Project project;

    public MarkerService(@NotNull Project project) {
        this.project = project;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /** Adds or removes a marker on the current caret line. */
    public void toggleMarker(@NotNull Editor editor, @NotNull VirtualFile file) {
        int line = editor.getCaretModel().getLogicalPosition().line;
        TreeSet<Integer> lines =
                fileMarkers.computeIfAbsent(file.getUrl(), k -> new TreeSet<>());
        if (!lines.remove(line)) lines.add(line);
        ApplicationManager.getApplication().invokeLater(
                () -> refreshForEditor(editor, file));
    }

    /** Moves the caret to the next marker in the file, wrapping around. */
    public void jumpToNextMarker(@NotNull Editor editor, @NotNull VirtualFile file) {
        TreeSet<Integer> lines = fileMarkers.get(file.getUrl());
        if (lines == null || lines.isEmpty()) return;
        int cur = editor.getCaretModel().getLogicalPosition().line;
        Integer next = lines.higher(cur);
        if (next == null) next = lines.first();   // wrap
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(next, 0));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    /** Moves the caret to the previous marker in the file, wrapping around. */
    public void jumpToPrevMarker(@NotNull Editor editor, @NotNull VirtualFile file) {
        TreeSet<Integer> lines = fileMarkers.get(file.getUrl());
        if (lines == null || lines.isEmpty()) return;
        int cur = editor.getCaretModel().getLogicalPosition().line;
        Integer prev = lines.lower(cur);
        if (prev == null) prev = lines.last();    // wrap
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(prev, 0));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    /**
     * Toggles "marker focus mode": dims + folds all non-marked regions.
     *
     * @return new state of focus mode (true = enabled), or {@code false} if
     *         the toggle was rejected because no markers were placed yet
     *         (so the menu / notification can correctly inform the user).
     */
    public boolean toggleFocusMode() {
        // Refuse to enable focus mode when there are no markers anywhere –
        // that combination silently dims/folds nothing and looks broken.
        if (!focusModeEnabled && !hasAnyMarkers()) {
            return false;
        }
        focusModeEnabled = !focusModeEnabled;
        refreshAllOpenEditors();
        return focusModeEnabled;
    }

    /** True if at least one marker exists in any file. */
    public boolean hasAnyMarkers() {
        for (TreeSet<Integer> s : fileMarkers.values())
            if (s != null && !s.isEmpty()) return true;
        return false;
    }

    public boolean isFocusModeEnabled() { return focusModeEnabled; }

    // ── full-editor refresh ───────────────────────────────────────────────────

    public void scheduleRefreshAll() {
        ApplicationManager.getApplication().invokeLater(this::refreshAllOpenEditors);
    }

    public void scheduleRefresh(@Nullable VirtualFile file) {
        if (file == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            FileEditorManager em = FileEditorManager.getInstance(project);
            for (FileEditor fe : em.getEditors(file))
                if (fe instanceof TextEditor te) refreshForEditor(te.getEditor(), file);
        });
    }

    // ── per-editor refresh ────────────────────────────────────────────────────

    public void refreshForEditor(@NotNull Editor editor, @NotNull VirtualFile file) {
        clearMarkerHighlights(editor);
        clearFocusHighlights(editor);
        clearBoundaryHighlights(editor);
        unfoldMarkerFolds(editor);

        TreeSet<Integer> markedLines = fileMarkers.get(file.getUrl());
        List<RangeHighlighter> markerHl = new ArrayList<>();

        // Compute hierarchical scopes once – needed for both gutter stripes
        // (so the BoundaryStripeRenderer knows which lines are scope edges)
        // and for the focus-mode dim/fold logic.
        List<int[]> scopes = focusModeEnabled && markedLines != null && !markedLines.isEmpty()
                ? computeMarkerScopes(editor, file, markedLines)
                : List.of();

        if (markedLines != null && !markedLines.isEmpty()) {
            Document doc = editor.getDocument();
            MarkupModel mm = editor.getMarkupModel();
            // In focus mode, the marker stripe itself is brighter+wider so the
            // user can pinpoint the line they originally chose inside the scope.
            CustomHighlighterRenderer renderer = focusModeEnabled
                    ? new VividMarkerStripeRenderer()
                    : new MarkerStripeRenderer();
            for (int line : markedLines) {
                if (line >= doc.getLineCount()) continue;
                int lineStart = doc.getLineStartOffset(line);
                int lineEnd   = doc.getLineEndOffset(line);
                int end       = Math.max(lineStart + 1, lineEnd);
                RangeHighlighter h = mm.addRangeHighlighter(
                        lineStart, end,
                        HighlighterLayer.LAST,
                        null, HighlighterTargetArea.LINES_IN_RANGE);
                h.setCustomRenderer(renderer);
                h.setErrorStripeMarkColor(new Color(0xFF, 0xA0, 0x00));
                h.setErrorStripeTooltip("CSV Marker");
                markerHl.add(h);
            }
        }
        editor.putUserData(MARKER_HL_KEY, markerHl);

        if (focusModeEnabled && !scopes.isEmpty()) {
            applyFocusMode(editor, scopes);
        }

        repaint(editor);
    }

    // ── focus mode ────────────────────────────────────────────────────────────

    /**
     * For each marker line, compute the smallest enclosing PSI scope
     * (PsiCodeBlock / PsiMethod / PsiIfStatement / etc.) and return its
     * inclusive line range. Overlapping scopes are merged into single ranges.
     */
    private @NotNull List<int[]> computeMarkerScopes(@NotNull Editor editor,
                                                     @NotNull VirtualFile file,
                                                     @NotNull TreeSet<Integer> markedLines) {
        Document doc = editor.getDocument();
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);

        // Compute raw [startLine, endLine] for each marker.
        List<int[]> raw = new ArrayList<>(markedLines.size());
        for (int line : markedLines) {
            if (line >= doc.getLineCount()) continue;
            int[] r = (psiFile != null)
                    ? scopeForLine(psiFile, doc, line)
                    : new int[]{line, line};
            raw.add(r);
        }
        if (raw.isEmpty()) return List.of();

        // Merge overlapping / adjacent ranges.
        raw.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = raw.get(0).clone();
        for (int i = 1; i < raw.size(); i++) {
            int[] nxt = raw.get(i);
            if (nxt[0] <= cur[1] + 1) {
                if (nxt[1] > cur[1]) cur[1] = nxt[1];
            } else {
                merged.add(cur);
                cur = nxt.clone();
            }
        }
        merged.add(cur);
        return merged;
    }

    private static int[] scopeForLine(@NotNull PsiFile psi, @NotNull Document doc, int line) {
        int lineStart = doc.getLineStartOffset(line);
        int lineEnd   = doc.getLineEndOffset(line);
        CharSequence text = doc.getCharsSequence();
        int offset = lineStart;
        while (offset < lineEnd && Character.isWhitespace(text.charAt(offset))) offset++;
        if (offset >= lineEnd) offset = lineStart;

        PsiElement el = psi.findElementAt(offset);
        if (el == null) return new int[]{line, line};

        // Walk up; the first ancestor matching one of these "scope-defining"
        // node types becomes the marker's range.  Traversal order = innermost
        // first, so deeper marks naturally restrict to the nearest brace pair.
        PsiElement cur = el;
        while (cur != null && !(cur instanceof PsiFile)) {
            if (isScopeNode(cur)) {
                int s = cur.getTextRange().getStartOffset();
                int e = Math.max(s, cur.getTextRange().getEndOffset() - 1);
                int sLine = doc.getLineNumber(Math.min(s, doc.getTextLength()));
                int eLine = doc.getLineNumber(Math.min(e, Math.max(0, doc.getTextLength() - 1)));
                if (eLine < sLine) eLine = sLine;
                return new int[]{sLine, eLine};
            }
            cur = cur.getParent();
        }
        return new int[]{line, line};
    }

    private static boolean isScopeNode(@NotNull PsiElement el) {
        return el instanceof PsiCodeBlock
                || el instanceof PsiMethod
                || el instanceof PsiClassInitializer
                || el instanceof PsiLambdaExpression
                || el instanceof PsiIfStatement
                || el instanceof PsiForStatement
                || el instanceof PsiForeachStatement
                || el instanceof PsiWhileStatement
                || el instanceof PsiDoWhileStatement
                || el instanceof PsiSwitchStatement
                || el instanceof PsiSwitchExpression
                || el instanceof PsiTryStatement
                || el instanceof PsiSynchronizedStatement
                || el instanceof PsiClass;
    }

    private void applyFocusMode(@NotNull Editor editor, @NotNull List<int[]> scopes) {
        Document doc = editor.getDocument();
        int totalLines = doc.getLineCount();

        // Build set of in-focus lines from scope ranges.
        Set<Integer> inFocus = new HashSet<>();
        for (int[] s : scopes) {
            for (int l = s[0]; l <= s[1] && l < totalLines; l++) inFocus.add(l);
        }

        // Render bright boundary stripes on the first + last line of every scope.
        List<RangeHighlighter> boundaryHl = new ArrayList<>();
        MarkupModel mm = editor.getMarkupModel();
        for (int[] s : scopes) {
            addBoundaryStripe(editor, doc, mm, s[0], boundaryHl);
            if (s[1] != s[0]) addBoundaryStripe(editor, doc, mm, s[1], boundaryHl);
        }
        editor.putUserData(BOUNDARY_HL_KEY, boundaryHl);

        // Walk all lines, building runs of out-of-focus lines.
        // Each run gets a solid background overlay (drawn at HighlighterLayer.LAST
        // so it overpaints text/syntax colours) and is folded if ≥ 2 lines long.
        List<RangeHighlighter> focusHl = new ArrayList<>();
        List<int[]> toFold = new ArrayList<>();
        int runStart = -1;
        for (int line = 0; line < totalLines; line++) {
            boolean focused = inFocus.contains(line);
            if (!focused) {
                if (runStart < 0) runStart = line;
            } else {
                if (runStart >= 0) {
                    addOpaqueOverlay(editor, doc, runStart, line - 1, focusHl);
                    if ((line - 1 - runStart) >= 1)
                        toFold.add(new int[]{runStart, line - 1});
                    runStart = -1;
                }
            }
        }
        if (runStart >= 0) {
            addOpaqueOverlay(editor, doc, runStart, totalLines - 1, focusHl);
            if ((totalLines - 1 - runStart) >= 1)
                toFold.add(new int[]{runStart, totalLines - 1});
        }
        editor.putUserData(FOCUS_HL_KEY, focusHl);

        if (!toFold.isEmpty()) {
            List<FoldRegion> created = new ArrayList<>();
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                for (int[] range : toFold) {
                    if (range[0] >= doc.getLineCount()) continue;
                    int s = doc.getLineStartOffset(range[0]);
                    int e = Math.min(doc.getLineEndOffset(range[1]), doc.getTextLength());
                    if (e <= s) continue;
                    FoldRegion r = editor.getFoldingModel().addFoldRegion(s, e, "  ⋮  ");
                    if (r != null) {
                        r.setExpanded(false);
                        created.add(r);
                    } else {
                        // Range overlaps an IDE-managed fold (imports, javadoc,
                        // method body) – collapse those instead so the area
                        // still disappears visually.
                        for (FoldRegion existing : editor.getFoldingModel().getAllFoldRegions()) {
                            if (!existing.isValid()) continue;
                            if (existing.getStartOffset() < e && existing.getEndOffset() > s) {
                                existing.setExpanded(false);
                            }
                        }
                    }
                }
            });
            editorFolds.put(editor, created);
        }
    }

    private static void addBoundaryStripe(@NotNull Editor editor, @NotNull Document doc,
                                          @NotNull MarkupModel mm, int line,
                                          @NotNull List<RangeHighlighter> out) {
        if (line < 0 || line >= doc.getLineCount()) return;
        int s = doc.getLineStartOffset(line);
        int e = Math.max(s + 1, doc.getLineEndOffset(line));
        RangeHighlighter h = mm.addRangeHighlighter(
                s, e, HighlighterLayer.LAST + 1,
                null, HighlighterTargetArea.LINES_IN_RANGE);
        h.setCustomRenderer(new BoundaryStripeRenderer());
        h.setErrorStripeMarkColor(new Color(0xFF, 0xC8, 0x40));
        out.add(h);
    }

    private static void addOpaqueOverlay(@NotNull Editor editor, @NotNull Document doc,
                                         int startLine, int endLine,
                                         @NotNull List<RangeHighlighter> out) {
        if (startLine > endLine) return;
        int s = doc.getLineStartOffset(startLine);
        int e = doc.getLineEndOffset(endLine);
        if (e <= s) return;
        MarkupModel mm = editor.getMarkupModel();
        // Layer = LAST so the renderer paints OVER syntax highlighting / text.
        RangeHighlighter h = mm.addRangeHighlighter(
                s, e, HighlighterLayer.LAST,
                null, HighlighterTargetArea.LINES_IN_RANGE);
        h.setCustomRenderer(new OpaqueBackgroundRenderer());
        out.add(h);
    }

    // ── clear helpers ─────────────────────────────────────────────────────────

    private static void clearMarkerHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(MARKER_HL_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            editor.putUserData(MARKER_HL_KEY, null);
        }
    }

    private static void clearFocusHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(FOCUS_HL_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            editor.putUserData(FOCUS_HL_KEY, null);
        }
    }

    private static void clearBoundaryHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(BOUNDARY_HL_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            editor.putUserData(BOUNDARY_HL_KEY, null);
        }
    }

    private void unfoldMarkerFolds(@NotNull Editor editor) {
        List<FoldRegion> folds = editorFolds.remove(editor);
        if (folds != null && !folds.isEmpty()) {
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                for (FoldRegion r : folds) {
                    if (r.isValid()) editor.getFoldingModel().removeFoldRegion(r);
                }
            });
        }
    }

    private void refreshAllOpenEditors() {
        FileEditorManager em = FileEditorManager.getInstance(project);
        for (VirtualFile f : em.getOpenFiles())
            for (FileEditor fe : em.getEditors(f))
                if (fe instanceof TextEditor te) refreshForEditor(te.getEditor(), f);
    }

    private static void repaint(@NotNull Editor editor) {
        editor.getContentComponent().revalidate();
        editor.getContentComponent().repaint();
    }

    @Override public void dispose() {}

    // ── renderers ─────────────────────────────────────────────────────────────

    /** Draws a 3 px orange stripe on the left edge of each marked line. */
    private static final class MarkerStripeRenderer implements CustomHighlighterRenderer {
        private static final Color ORANGE = new Color(0xFF, 0xA0, 0x00);

        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int offset = hl.getStartOffset();
            if (offset >= doc.getTextLength()) return;
            int line = doc.getLineNumber(offset);
            int y    = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            int lh   = editor.getLineHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.90f));
                g2.setColor(ORANGE);
                g2.fillRect(0, y, 3, lh);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Semi-transparent dark overlay for non-marked regions in focus mode. */
    /**
     * Wider, fully-opaque stripe used for the regular marker line WHEN focus
     * mode is enabled (so the user can locate their original mark inside a
     * scope at a glance).
     */
    private static final class VividMarkerStripeRenderer implements CustomHighlighterRenderer {
        private static final Color BRIGHT = new Color(0xFF, 0xC8, 0x40);

        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int offset = hl.getStartOffset();
            if (offset >= doc.getTextLength()) return;
            int line = doc.getLineNumber(offset);
            int y    = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            int lh   = editor.getLineHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(BRIGHT);
                g2.fillRect(0, y, 6, lh);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Bright wide stripe drawn at the first/last line of every scope. */
    private static final class BoundaryStripeRenderer implements CustomHighlighterRenderer {
        private static final Color VIVID = new Color(0xFF, 0xD0, 0x40);

        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int offset = hl.getStartOffset();
            if (offset >= doc.getTextLength()) return;
            int line = doc.getLineNumber(offset);
            int y    = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            int lh   = editor.getLineHeight();
            int w    = editor.getContentComponent().getWidth();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // Solid 5 px stripe on the left
                g2.setColor(VIVID);
                g2.fillRect(0, y, 5, lh);
                // Plus a faint full-width tint to make boundaries pop
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
                g2.fillRect(5, y, w - 5, lh);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Paints a fully-opaque rectangle in the editor's own background colour,
     * overpainting all syntax-highlight foreground/background of the lines it
     * covers.  Drawn at HighlighterLayer.LAST so out-of-focus code becomes
     * literally invisible (matches the requirement of "same color as the
     * background, overwriting all color markings from styling and themes").
     */
    private static final class OpaqueBackgroundRenderer implements CustomHighlighterRenderer {
        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int docLen = doc.getTextLength();
            if (docLen == 0) return;
            int start = hl.getStartOffset();
            int end   = hl.getEndOffset();
            if (start >= end) return;
            int startLine = doc.getLineNumber(start);
            int endLine   = doc.getLineNumber(Math.min(end, docLen - 1));
            int lh        = editor.getLineHeight();
            int width     = editor.getContentComponent().getWidth();

            // ── perf: clip line range to what's visible ───────────────────
            Rectangle clip = g.getClipBounds();
            int from = startLine, to = endLine;
            if (clip != null) {
                int firstVis = editor.xyToLogicalPosition(new Point(0, clip.y)).line;
                int lastVis  = editor.xyToLogicalPosition(new Point(0, clip.y + clip.height)).line;
                if (firstVis > from) from = firstVis;
                if (lastVis  < to)   to   = lastVis;
                if (from > to) return;
            }

            // Skip lines that overlap the active selection so the native
            // selection highlight remains clearly visible (same guard used by
            // AlphaBackgroundRenderer in JavaStructureHighlighterService).
            SelectionModel sel   = editor.getSelectionModel();
            boolean hasSel       = sel.hasSelection();
            int selStart         = hasSel ? sel.getSelectionStart() : -1;
            int selEnd           = hasSel ? sel.getSelectionEnd()   : -1;

            int yAnchor = editor.logicalPositionToXY(new LogicalPosition(from, 0)).y;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // Full-opacity editor background → overwrites all theme colours.
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(editor.getColorsScheme().getDefaultBackground());
                int y = yAnchor;
                for (int line = from; line <= to; line++, y += lh) {
                    if (hasSel) {
                        int ls = doc.getLineStartOffset(line);
                        int le = doc.getLineEndOffset(line);
                        if (selStart < le && selEnd > ls) continue;
                    }
                    g2.fillRect(0, y, width, lh);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

