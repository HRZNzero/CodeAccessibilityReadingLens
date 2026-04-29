package com.example.codestructurevisualizer;

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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Toggles "marker focus mode": dims + folds all non-marked regions. */
    public boolean toggleFocusMode() {
        focusModeEnabled = !focusModeEnabled;
        refreshAllOpenEditors();
        return focusModeEnabled;
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
        unfoldMarkerFolds(editor);

        TreeSet<Integer> markedLines = fileMarkers.get(file.getUrl());
        List<RangeHighlighter> markerHl = new ArrayList<>();

        if (markedLines != null && !markedLines.isEmpty()) {
            Document doc = editor.getDocument();
            MarkupModel mm = editor.getMarkupModel();
            for (int line : markedLines) {
                if (line >= doc.getLineCount()) continue;
                int lineStart = doc.getLineStartOffset(line);
                int lineEnd   = doc.getLineEndOffset(line);
                int end       = Math.max(lineStart + 1, lineEnd);
                RangeHighlighter h = mm.addRangeHighlighter(
                        lineStart, end,
                        HighlighterLayer.ADDITIONAL_SYNTAX + 300,
                        null, HighlighterTargetArea.LINES_IN_RANGE);
                h.setCustomRenderer(new MarkerStripeRenderer());
                h.setErrorStripeMarkColor(new Color(0xFF, 0xA0, 0x00));
                h.setErrorStripeTooltip("CSV Marker");
                markerHl.add(h);
            }
        }
        editor.putUserData(MARKER_HL_KEY, markerHl);

        if (focusModeEnabled && markedLines != null && !markedLines.isEmpty()) {
            applyFocusMode(editor, markedLines);
        }

        repaint(editor);
    }

    // ── focus mode ────────────────────────────────────────────────────────────

    private void applyFocusMode(@NotNull Editor editor, @NotNull TreeSet<Integer> markedLines) {
        Document doc = editor.getDocument();
        int totalLines = doc.getLineCount();
        List<RangeHighlighter> focusHl = new ArrayList<>();
        List<int[]> toFold = new ArrayList<>();               // [startLine, endLine] inclusive

        int runStart = -1;
        for (int line = 0; line < totalLines; line++) {
            boolean marked = markedLines.contains(line);
            if (!marked) {
                if (runStart < 0) runStart = line;
            } else {
                if (runStart >= 0) {
                    addDimRegion(editor, doc, runStart, line - 1, focusHl);
                    if ((line - 1 - runStart) >= 2)           // fold runs of ≥ 3 lines
                        toFold.add(new int[]{runStart, line - 1});
                    runStart = -1;
                }
            }
        }
        if (runStart >= 0) {
            addDimRegion(editor, doc, runStart, totalLines - 1, focusHl);
            if ((totalLines - 1 - runStart) >= 2)
                toFold.add(new int[]{runStart, totalLines - 1});
        }
        editor.putUserData(FOCUS_HL_KEY, focusHl);

        // Apply fold regions on EDT inside a batch operation
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
                    }
                }
            });
            editorFolds.put(editor, created);
        }
    }

    private static void addDimRegion(@NotNull Editor editor, @NotNull Document doc,
                                     int startLine, int endLine,
                                     @NotNull List<RangeHighlighter> out) {
        if (startLine > endLine) return;
        int s = doc.getLineStartOffset(startLine);
        int e = doc.getLineEndOffset(endLine);
        if (e <= s) return;
        MarkupModel mm = editor.getMarkupModel();
        RangeHighlighter h = mm.addRangeHighlighter(
                s, e, HighlighterLayer.ADDITIONAL_SYNTAX + 250,
                null, HighlighterTargetArea.LINES_IN_RANGE);
        h.setCustomRenderer(new DimRenderer());
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
    private static final class DimRenderer implements CustomHighlighterRenderer {
        private static final Color DIM = new Color(0x18, 0x1a, 0x1b);

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

            // Skip lines that overlap the active selection so the native
            // selection highlight remains clearly visible (same guard used by
            // AlphaBackgroundRenderer in JavaStructureHighlighterService).
            SelectionModel sel   = editor.getSelectionModel();
            boolean hasSel       = sel.hasSelection();
            int selStart         = hasSel ? sel.getSelectionStart() : -1;
            int selEnd           = hasSel ? sel.getSelectionEnd()   : -1;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.62f));
                g2.setColor(DIM);
                for (int line = startLine; line <= endLine; line++) {
                    if (hasSel) {
                        int ls = doc.getLineStartOffset(line);
                        int le = doc.getLineEndOffset(line);
                        if (selStart < le && selEnd > ls) continue;
                    }
                    int y = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
                    g2.fillRect(0, y, width, lh);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

