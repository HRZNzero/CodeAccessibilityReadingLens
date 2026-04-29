package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
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
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiSwitchExpression;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class JavaStructureHighlighterService implements Disposable {

    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS_KEY =
            Key.create("code.structure.visualizer.highlighters");
    private static final Key<List<RangeHighlighter>> INSPECTION_HIGHLIGHTERS_KEY =
            Key.create("code.structure.visualizer.inspection.highlighters");
    private static final Key<List<RangeHighlighter>> CF_FOCUS_KEY =
            Key.create("code.structure.visualizer.cf.focus.highlighters");

    private final Project project;
    private final Alarm refreshAlarm;
    private final Alarm cfFocusAlarm;

    // ── toggle flags ──────────────────────────────────────────────────────────
    private boolean enabled                 = true;
    private boolean nestedSubsectionsEnabled = false;
    private boolean inspectionModeEnabled   = false;
    /** Blocks start at their indentation column instead of the left margin. */
    private boolean indentedBoxesEnabled    = false;
    /** Each field row is annotated individually with a top-inset gap. */
    private boolean widerFieldSpacingEnabled = false;
    /** Replace level colouring with if/else/switch highlighting. */
    private boolean controlFlowModeEnabled  = false;

    public JavaStructureHighlighterService(@NotNull Project project) {
        this.project = project;
        this.refreshAlarm  = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        this.cfFocusAlarm  = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    // ── public toggle API ─────────────────────────────────────────────────────

    public boolean isEnabled()                  { return enabled; }
    public boolean isNestedSubsectionsEnabled() { return nestedSubsectionsEnabled; }
    public boolean isInspectionModeEnabled()    { return inspectionModeEnabled; }
    public boolean isIndentedBoxesEnabled()     { return indentedBoxesEnabled; }
    public boolean isWiderFieldSpacingEnabled() { return widerFieldSpacingEnabled; }
    public boolean isControlFlowModeEnabled()   { return controlFlowModeEnabled; }

    public boolean toggleEnabled() {
        enabled = !enabled;
        scheduleFullRefresh(); return enabled;
    }
    public boolean toggleNestedSubsectionsEnabled() {
        nestedSubsectionsEnabled = !nestedSubsectionsEnabled;
        scheduleFullRefresh(); return nestedSubsectionsEnabled;
    }
    public boolean toggleInspectionMode() {
        inspectionModeEnabled = !inspectionModeEnabled;
        scheduleFullRefresh(); return inspectionModeEnabled;
    }
    public boolean toggleIndentedBoxes() {
        indentedBoxesEnabled = !indentedBoxesEnabled;
        scheduleFullRefresh(); return indentedBoxesEnabled;
    }
    public boolean toggleWiderFieldSpacing() {
        widerFieldSpacingEnabled = !widerFieldSpacingEnabled;
        scheduleFullRefresh(); return widerFieldSpacingEnabled;
    }
    public boolean toggleControlFlowMode() {
        controlFlowModeEnabled = !controlFlowModeEnabled;
        scheduleFullRefresh(); return controlFlowModeEnabled;
    }

    private void scheduleFullRefresh() {
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
    }

    // ── inspection caret refresh ──────────────────────────────────────────────

    public void scheduleInspectionRefreshForEditor(@NotNull Editor editor, @NotNull VirtualFile file) {
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(() -> {
            if (project.isDisposed()) return;
            Runnable r = () -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile jf) refreshInspectionForEditor(editor, jf);
            };
            if (ApplicationManager.getApplication().isReadAccessAllowed()) r.run();
            else ApplicationManager.getApplication().runReadAction(r);
        }, 80);
    }

    /** Called from the caret listener when control-flow mode is active. */
    public void scheduleControlFlowFocusRefreshForEditor(@NotNull Editor editor, @NotNull VirtualFile file) {
        cfFocusAlarm.cancelAllRequests();
        cfFocusAlarm.addRequest(() -> {
            if (project.isDisposed()) return;
            Runnable r = () -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile jf) refreshCFFocusForEditor(editor, jf);
            };
            if (ApplicationManager.getApplication().isReadAccessAllowed()) r.run();
            else ApplicationManager.getApplication().runReadAction(r);
        }, 80);
    }

    // ── scheduling ────────────────────────────────────────────────────────────

    public void scheduleRefreshAll() { scheduleRefresh(null); }

    public void scheduleRefresh(@Nullable VirtualFile file) {
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(() -> {
            if (project.isDisposed()) return;
            if (file == null) refreshAllOpenJavaEditorsNow();
            else             refreshFile(file);
        }, 100);
    }

    public void refreshAllOpenJavaEditorsNow() {
        Runnable r = () -> {
            FileEditorManager em = FileEditorManager.getInstance(project);
            for (VirtualFile f : em.getOpenFiles()) refreshFile(f);
        };
        if (ApplicationManager.getApplication().isReadAccessAllowed()) r.run();
        else ApplicationManager.getApplication().runReadAction(r);
    }

    // ── file / editor refresh ─────────────────────────────────────────────────

    private void clearAllOpenEditors() {
        FileEditorManager em = FileEditorManager.getInstance(project);
        for (VirtualFile f : em.getOpenFiles())
            for (FileEditor fe : em.getEditors(f))
                if (fe instanceof TextEditor te) {
                    clearEditor(te.getEditor());
                    clearInspectionHighlights(te.getEditor());
                }
    }

    private void refreshFile(@NotNull VirtualFile file) {
        FileEditorManager em = FileEditorManager.getInstance(project);
        if (file.getFileType() != JavaFileType.INSTANCE || !enabled) {
            for (FileEditor fe : em.getEditors(file))
                if (fe instanceof TextEditor te) {
                    clearEditor(te.getEditor());
                    clearInspectionHighlights(te.getEditor());
                }
            return;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile jf)) return;
        for (FileEditor fe : em.getEditors(file))
            if (fe instanceof TextEditor te) refreshEditor(te.getEditor(), jf);
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);
        if (!enabled) { repaint(editor); return; }

        // Capture flags into locals – used by lambdas / static helpers below
        boolean indented    = indentedBoxesEnabled;
        boolean wideFields  = widerFieldSpacingEnabled;
        boolean cfMode      = controlFlowModeEnabled;
        boolean nested      = nestedSubsectionsEnabled;

        List<RangeHighlighter> created = new ArrayList<>();

        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                annotateFieldGroups(aClass, editor, created, wideFields, indented);
                if (!cfMode) annotateMethods(aClass, editor, created, nested, indented);
            }
        });

        if (cfMode) annotateControlFlow(javaFile, editor, created, indented);

        editor.putUserData(HIGHLIGHTERS_KEY, created);
        refreshInspectionForEditor(editor, javaFile);
        repaint(editor);
    }

    // ── inspection overlay ────────────────────────────────────────────────────

    private void refreshInspectionForEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearInspectionHighlights(editor);
        if (!inspectionModeEnabled) return;

        int caretOffset = editor.getCaretModel().getOffset();
        TextRange activeRange = findActiveRange(javaFile.findElementAt(caretOffset));

        List<RangeHighlighter> hl = new ArrayList<>();
        Document doc = editor.getDocument();
        int docLen = doc.getTextLength();
        if (docLen > 0) {
            addLineBlockAtLayer(editor, new TextRange(0, docLen),
                    JavaStructureColors.INSPECTION_DIM_COLOR,
                    JavaStructureColors.INSPECTION_DIM_ALPHA,
                    hl, HighlighterLayer.LAST, false, 0);
            if (activeRange != null)
                addLineBlockAtLayer(editor, activeRange,
                        JavaStructureColors.INSPECTION_ACTIVE_COLOR,
                        JavaStructureColors.INSPECTION_ACTIVE_ALPHA,
                        hl, HighlighterLayer.LAST + 100, false, 0);
        }
        editor.putUserData(INSPECTION_HIGHLIGHTERS_KEY, hl);
        repaint(editor);
    }

    @Nullable
    private static TextRange findActiveRange(@Nullable PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (cur instanceof PsiCodeBlock cb) {
                PsiElement p = cb.getParent();
                return p != null ? p.getTextRange() : cb.getTextRange();
            }
            if (cur instanceof PsiMethod) return cur.getTextRange();
            cur = cur.getParent();
        }
        return null;
    }

    private static void clearInspectionHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(INSPECTION_HIGHLIGHTERS_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            ex.clear();
            editor.putUserData(INSPECTION_HIGHLIGHTERS_KEY, null);
        }
    }

    // ── control-flow cursor focus ─────────────────────────────────────────────

    /**
     * When the caret moves inside a CF block while CF mode is active,
     * dims every OTHER top-level CF block with a dark overlay so only the
     * active one retains its original colour.
     */
    private void refreshCFFocusForEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearCFFocusHighlights(editor);
        if (!controlFlowModeEnabled) return;

        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement atCaret = javaFile.findElementAt(caretOffset);
        PsiElement activeCF = findParentControlFlow(atCaret);
        if (activeCF == null) { repaint(editor); return; }   // not inside any CF block

        TextRange activeRange = activeCF.getTextRange();
        boolean indented = indentedBoxesEnabled;
        List<RangeHighlighter> hl = new ArrayList<>();

        // Collect all CF-block ranges in the file (mirrors annotateControlFlow)
        List<TextRange> allRanges = new ArrayList<>();
        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitIfStatement(@NotNull PsiIfStatement s) {
                TextRange r = s.getTextRange();
                if (r != null && !r.isEmpty()) allRanges.add(r);
                super.visitIfStatement(s);
            }
            @Override public void visitSwitchStatement(@NotNull PsiSwitchStatement s) {
                TextRange r = s.getTextRange();
                if (r != null && !r.isEmpty()) allRanges.add(r);
                super.visitSwitchStatement(s);
            }
            @Override public void visitSwitchExpression(@NotNull PsiSwitchExpression s) {
                TextRange r = s.getTextRange();
                if (r != null && !r.isEmpty()) allRanges.add(r);
                super.visitSwitchExpression(s);
            }
        });

        // Dim blocks that are NOT the active block and NOT contained inside it
        for (TextRange r : allRanges) {
            if (!r.equals(activeRange) && !activeRange.contains(r)) {
                addLineBlockAtLayer(editor, r, new Color(0x10, 0x10, 0x10), 0.22f, hl,
                        HighlighterLayer.ADDITIONAL_SYNTAX + 200, indented, 0);
            }
        }

        editor.putUserData(CF_FOCUS_KEY, hl);
        repaint(editor);
    }

    @Nullable
    private static PsiElement findParentControlFlow(@Nullable PsiElement element) {
        PsiElement cur = element;
        while (cur != null) {
            if (cur instanceof PsiIfStatement) return cur;
            if (cur instanceof PsiSwitchStatement) return cur;
            if (cur instanceof PsiSwitchExpression) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static void clearCFFocusHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(CF_FOCUS_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            ex.clear();
            editor.putUserData(CF_FOCUS_KEY, null);
        }
    }

    // ── annotation helpers ────────────────────────────────────────────────────

    private static void annotateMethods(@NotNull PsiClass cls, @NotNull Editor editor,
                                        @NotNull List<RangeHighlighter> out,
                                        boolean nested, boolean indented) {
        for (PsiMethod m : cls.getMethods()) {
            TextRange r = m.getTextRange();
            if (r == null || r.isEmpty()) continue;
            addLineBlock(editor, r, JavaStructureColors.methodLevelColor(1),
                    JavaStructureColors.methodLevelAlpha(1), out, 1, indented);
            if (nested) annotateNestedCodeBlocks(m, editor, out, indented);
        }
    }

    private static void annotateNestedCodeBlocks(@NotNull PsiMethod method,
                                                 @NotNull Editor editor,
                                                 @NotNull List<RangeHighlighter> out,
                                                 boolean indented) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return;
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitCodeBlock(@NotNull PsiCodeBlock block) {
                if (block != body) {
                    TextRange r = block.getTextRange();
                    if (r != null && !r.isEmpty()) {
                        int level = blockLevel(block, body);
                        addLineBlock(editor, r, JavaStructureColors.methodLevelColor(level),
                                JavaStructureColors.methodLevelAlpha(level), out, level, indented);
                    }
                }
                super.visitCodeBlock(block);
            }
        });
    }

    private static int blockLevel(@NotNull PsiCodeBlock block, @NotNull PsiCodeBlock root) {
        int level = 2;
        PsiElement p = block.getParent();
        while (p != null && p != root) {
            if (p instanceof PsiCodeBlock) level++;
            p = p.getParent();
        }
        return Math.min(level, 5);
    }

    private static void annotateFieldGroups(@NotNull PsiClass cls, @NotNull Editor editor,
                                            @NotNull List<RangeHighlighter> out,
                                            boolean wideSpacing, boolean indented) {
        if (wideSpacing) {
            // ── one continuous group block + thin divider lines between fields ─
            TextRange group = null;
            List<TextRange> fieldRanges = new ArrayList<>();
            for (PsiElement child : cls.getChildren()) {
                if (child instanceof PsiField) {
                    TextRange r = child.getTextRange();
                    if (r == null || r.isEmpty()) continue;
                    fieldRanges.add(r);
                    group = group == null ? r : group.union(r);
                } else if (child instanceof PsiWhiteSpace || child instanceof PsiComment
                        || child instanceof PsiModifierList) {
                    // keep group open
                } else if (child instanceof PsiMethod || child instanceof PsiClass
                        || child instanceof PsiClassInitializer) {
                    flushWideFieldGroup(group, fieldRanges, editor, out, indented);
                    group = null;
                    fieldRanges = new ArrayList<>();
                }
            }
            flushWideFieldGroup(group, fieldRanges, editor, out, indented);
        } else {
            // ── original group mode ───────────────────────────────────────────
            TextRange group = null;
            for (PsiElement child : cls.getChildren()) {
                if (child instanceof PsiField) {
                    TextRange r = child.getTextRange();
                    if (r == null || r.isEmpty()) continue;
                    group = group == null ? r : group.union(r);
                } else if (child instanceof PsiWhiteSpace || child instanceof PsiComment
                        || child instanceof PsiModifierList) {
                    // keep group open
                } else if (child instanceof PsiMethod || child instanceof PsiClass
                        || child instanceof PsiClassInitializer) {
                    flushFieldGroup(group, editor, out, indented);
                    group = null;
                }
            }
            flushFieldGroup(group, editor, out, indented);
        }
    }

    /**
     * Renders the full-group background as one solid block, then overlays a
     * 2 px divider line at the top of every field boundary after the first.
     * Result: one continuous coloured band with subtle internal separators.
     */
    private static void flushWideFieldGroup(@Nullable TextRange group,
                                            @NotNull List<TextRange> fieldRanges,
                                            @NotNull Editor editor,
                                            @NotNull List<RangeHighlighter> out,
                                            boolean indented) {
        if (group == null || group.isEmpty() || fieldRanges.isEmpty()) return;
        // 1 – solid background for the entire group
        addLineBlock(editor, group, JavaStructureColors.FIELD_GROUP_COLOR,
                JavaStructureColors.FIELD_GROUP_ALPHA, out, 0, indented);
        // 2 – thin divider on the first line of each field except the first
        Document doc = editor.getDocument();
        MarkupModel mm = editor.getMarkupModel();
        for (int i = 1; i < fieldRanges.size(); i++) {
            int lineNo  = doc.getLineNumber(fieldRanges.get(i).getStartOffset());
            int ls      = doc.getLineStartOffset(lineNo);
            int le      = Math.max(ls + 1, doc.getLineEndOffset(lineNo));
            RangeHighlighter h = mm.addRangeHighlighter(
                    ls, le,
                    HighlighterLayer.ADDITIONAL_SYNTAX + 1,
                    null, HighlighterTargetArea.LINES_IN_RANGE);
            h.setCustomRenderer(new FieldDividerRenderer(indented));
            out.add(h);
        }
    }

    private static void flushFieldGroup(@Nullable TextRange r, @NotNull Editor editor,
                                        @NotNull List<RangeHighlighter> out, boolean indented) {
        if (r != null && !r.isEmpty())
            addLineBlock(editor, r, JavaStructureColors.FIELD_GROUP_COLOR,
                    JavaStructureColors.FIELD_GROUP_ALPHA, out, 0, indented);
    }

    /** Highlights every if/else chain and switch statement with the control-flow colour. */
    private static void annotateControlFlow(@NotNull PsiJavaFile file, @NotNull Editor editor,
                                            @NotNull List<RangeHighlighter> out, boolean indented) {
        file.accept(new JavaRecursiveElementWalkingVisitor() {
            int depth = 0;

            private void paintCF(@Nullable TextRange r) {
                if (r == null || r.isEmpty()) return;
                int level = Math.min(depth + 1, 5);
                addLineBlock(editor, r, JavaStructureColors.CONTROL_FLOW_COLOR,
                        JavaStructureColors.controlFlowAlpha(level), out, level, indented);
            }

            @Override public void visitIfStatement(@NotNull PsiIfStatement stmt) {
                paintCF(stmt.getTextRange());
                depth++;
                try { super.visitIfStatement(stmt); } finally { depth--; }
            }
            @Override public void visitSwitchStatement(@NotNull PsiSwitchStatement stmt) {
                paintCF(stmt.getTextRange());
                depth++;
                try { super.visitSwitchStatement(stmt); } finally { depth--; }
            }
            @Override public void visitSwitchExpression(@NotNull PsiSwitchExpression expr) {
                paintCF(expr.getTextRange());
                depth++;
                try { super.visitSwitchExpression(expr); } finally { depth--; }
            }
        });
    }

    // ── low-level markup helpers ──────────────────────────────────────────────

    private static void addLineBlock(@NotNull Editor editor, @Nullable TextRange range,
                                     @NotNull Color color, float alpha,
                                     @NotNull List<RangeHighlighter> out,
                                     int nestingLevel, boolean indented) {
        addLineBlockAtLayer(editor, range, color, alpha, out,
                HighlighterLayer.ADDITIONAL_SYNTAX + Math.max(0, nestingLevel),
                indented, 0);
    }

    private static void addLineBlockAtLayer(@NotNull Editor editor, @Nullable TextRange range,
                                            @NotNull Color color, float alpha,
                                            @NotNull List<RangeHighlighter> out,
                                            int layer, boolean indented, int lineInset) {
        if (range == null || range.isEmpty()) return;
        Document doc = editor.getDocument();
        int len = doc.getTextLength();
        int s = Math.max(0, Math.min(range.getStartOffset(), len));
        int e = Math.max(s, Math.min(range.getEndOffset(), len));
        if (s >= len && len > 0) s = len - 1;
        int sl = doc.getLineNumber(s);
        int el = doc.getLineNumber(Math.max(e - 1, s));
        int so = doc.getLineStartOffset(sl);
        int eo = doc.getLineEndOffset(el);
        if (eo <= so) return;
        MarkupModel mm = editor.getMarkupModel();
        RangeHighlighter h = mm.addRangeHighlighter(
                so, eo, layer, null, HighlighterTargetArea.LINES_IN_RANGE);
        h.setGreedyToLeft(true);
        h.setGreedyToRight(true);
        h.setCustomRenderer(new AlphaBackgroundRenderer(color, alpha, indented, lineInset));
        out.add(h);
    }

    // ── renderers ─────────────────────────────────────────────────────────────

    /**
     * Draws a 2 px horizontal divider line at the very top of one line.
     * Used by wider-field-spacing mode to separate fields within a continuous block.
     */
    private static final class FieldDividerRenderer implements CustomHighlighterRenderer {
        private final boolean indented;

        FieldDividerRenderer(boolean indented) { this.indented = indented; }

        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int offset = hl.getStartOffset();
            if (offset >= doc.getTextLength()) return;
            int line  = doc.getLineNumber(offset);
            int y     = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            int width = editor.getContentComponent().getWidth();

            int xStart = 0;
            if (indented) {
                CharSequence chars = doc.getCharsSequence();
                int lineStart = doc.getLineStartOffset(line);
                int lineEnd   = doc.getLineEndOffset(line);
                int col = 0;
                for (int ci = lineStart; ci < lineEnd; ci++) {
                    char c = chars.charAt(ci);
                    if (c != ' ' && c != '\t') break;
                    col++;
                }
                xStart = editor.logicalPositionToXY(new LogicalPosition(line, col)).x;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // slightly-darkened field colour at medium opacity → subtle rule
                Color divColor = JavaStructureColors.FIELD_GROUP_COLOR.darker();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                g2.setColor(divColor);
                g2.fillRect(xStart, y, width - xStart, 2);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Paints a semi-transparent colour band.
     *
     * <ul>
     *   <li>{@code indented} – when {@code true} the band starts at the first
     *       non-whitespace column of the block's opening line, creating a
     *       "nested box" look instead of a full-width stripe.</li>
     *   <li>{@code lineInset} – pixels skipped from the top of each line
     *       (used by the wider-field-spacing mode to create thin dark gaps
     *       between adjacent field rows).</li>
     * </ul>
     */
    private static final class AlphaBackgroundRenderer implements CustomHighlighterRenderer {
        private final Color color;
        private final float alpha;
        private final boolean indented;
        private final int lineInset;

        AlphaBackgroundRenderer(@NotNull Color color, float alpha, boolean indented, int lineInset) {
            this.color     = color;
            this.alpha     = alpha;
            this.indented  = indented;
            this.lineInset = lineInset;
        }

        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter hl, @NotNull Graphics g) {
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

            // ── perf: clip the line range to what's actually visible ─────────
            Rectangle clip = g.getClipBounds();
            int from = startLine, to = endLine;
            if (clip != null) {
                int cTop = clip.y;
                int cBot = clip.y + clip.height;
                int firstVis = editor.xyToLogicalPosition(new Point(0, cTop)).line;
                int lastVis  = editor.xyToLogicalPosition(new Point(0, cBot)).line;
                if (firstVis > from) from = firstVis;
                if (lastVis  < to)   to   = lastVis;
                if (from > to) return;
            }

            // Selection – skip lines that have selected text so the native
            // selection highlight remains clearly visible.
            SelectionModel sel    = editor.getSelectionModel();
            boolean hasSelection  = sel.hasSelection();
            int selStart          = hasSelection ? sel.getSelectionStart() : -1;
            int selEnd            = hasSelection ? sel.getSelectionEnd()   : -1;

            // Determine the x origin for indented-box mode.
            int xStart = 0;
            if (indented) {
                CharSequence chars = doc.getCharsSequence();
                int lineStart = doc.getLineStartOffset(startLine);
                int lineEnd   = doc.getLineEndOffset(startLine);
                int col = 0;
                for (int ci = lineStart; ci < lineEnd; ci++) {
                    char c = chars.charAt(ci);
                    if (c != ' ' && c != '\t') break;
                    col++;
                }
                xStart = editor.logicalPositionToXY(new LogicalPosition(startLine, col)).x;
            }

            int paintH  = Math.max(1, lh - lineInset);
            int rectW   = width - xStart;
            // Anchor y once and increment; correct because LINES_IN_RANGE
            // highlighters are not painted across folded regions (the editor
            // dispatches separate paint() calls per visible chunk via clip).
            int yAnchor = editor.logicalPositionToXY(new LogicalPosition(from, 0)).y;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(color);
                int y = yAnchor;
                for (int line = from; line <= to; line++, y += lh) {
                    if (hasSelection) {
                        int ls = doc.getLineStartOffset(line);
                        int le = doc.getLineEndOffset(line);
                        if (selStart < le && selEnd > ls) continue;
                    }
                    g2.fillRect(xStart, y + lineInset, rectW, paintH);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    // ── editor helpers ────────────────────────────────────────────────────────

    private static void clearEditor(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(HIGHLIGHTERS_KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            ex.clear();
            editor.putUserData(HIGHLIGHTERS_KEY, null);
        }
        clearCFFocusHighlights(editor);
        repaint(editor);
    }

    private static void repaint(@NotNull Editor editor) {
        editor.getContentComponent().revalidate();
        editor.getContentComponent().repaint();
    }

    @Override public void dispose() { clearAllOpenEditors(); }
}
