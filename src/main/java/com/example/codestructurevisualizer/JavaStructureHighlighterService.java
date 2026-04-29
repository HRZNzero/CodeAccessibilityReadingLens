package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
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
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class JavaStructureHighlighterService implements Disposable {
    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS_KEY =
            Key.create("code.structure.visualizer.highlighters");
    private static final Key<List<RangeHighlighter>> INSPECTION_HIGHLIGHTERS_KEY =
            Key.create("code.structure.visualizer.inspection.highlighters");

    private final Project project;
    private final Alarm refreshAlarm;
    private boolean enabled = true;
    private boolean nestedSubsectionsEnabled = false;
    private boolean inspectionModeEnabled = false;

    public JavaStructureHighlighterService(@NotNull Project project) {
        this.project = project;
        this.refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    // ── normal overlay ────────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }

    public boolean toggleEnabled() {
        enabled = !enabled;
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return enabled;
    }

    // ── nested subsections ────────────────────────────────────────────────────

    public boolean isNestedSubsectionsEnabled() { return nestedSubsectionsEnabled; }

    public boolean toggleNestedSubsectionsEnabled() {
        nestedSubsectionsEnabled = !nestedSubsectionsEnabled;
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return nestedSubsectionsEnabled;
    }

    // ── inspection mode ───────────────────────────────────────────────────────

    public boolean isInspectionModeEnabled() { return inspectionModeEnabled; }

    public boolean toggleInspectionMode() {
        inspectionModeEnabled = !inspectionModeEnabled;
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return inspectionModeEnabled;
    }

    /**
     * Called by the caret listener whenever the caret moves while inspection
     * mode is active.  Runs on the EDT inside a read action.
     */
    public void scheduleInspectionRefreshForEditor(@NotNull Editor editor, @NotNull VirtualFile file) {
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(() -> {
            if (project.isDisposed()) return;
            Runnable r = () -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile javaFile) {
                    refreshInspectionForEditor(editor, javaFile);
                }
            };
            if (ApplicationManager.getApplication().isReadAccessAllowed()) r.run();
            else ApplicationManager.getApplication().runReadAction(r);
        }, 80);
    }

    // ── scheduling / refresh ──────────────────────────────────────────────────

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
        Runnable refresh = () -> {
            FileEditorManager em = FileEditorManager.getInstance(project);
            for (VirtualFile file : em.getOpenFiles()) refreshFile(file);
        };
        if (ApplicationManager.getApplication().isReadAccessAllowed()) refresh.run();
        else ApplicationManager.getApplication().runReadAction(refresh);
    }

    private void clearAllOpenEditors() {
        FileEditorManager em = FileEditorManager.getInstance(project);
        for (VirtualFile file : em.getOpenFiles()) {
            for (FileEditor fe : em.getEditors(file)) {
                if (fe instanceof TextEditor te) {
                    clearEditor(te.getEditor());
                    clearInspectionHighlights(te.getEditor());
                }
            }
        }
    }

    private void refreshFile(@NotNull VirtualFile file) {
        FileEditorManager em = FileEditorManager.getInstance(project);

        if (file.getFileType() != JavaFileType.INSTANCE) {
            for (FileEditor fe : em.getEditors(file)) {
                if (fe instanceof TextEditor te) {
                    clearEditor(te.getEditor());
                    clearInspectionHighlights(te.getEditor());
                }
            }
            return;
        }

        if (!enabled) {
            for (FileEditor fe : em.getEditors(file)) {
                if (fe instanceof TextEditor te) {
                    clearEditor(te.getEditor());
                    clearInspectionHighlights(te.getEditor());
                }
            }
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile)) return;

        for (FileEditor fe : em.getEditors(file)) {
            if (fe instanceof TextEditor te) {
                refreshEditor(te.getEditor(), javaFile);
            }
        }
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);

        if (!enabled) { repaint(editor); return; }

        List<RangeHighlighter> created = new ArrayList<>();
        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                annotateFieldGroups(aClass, editor, created);
                annotateMethods(aClass, editor, created, nestedSubsectionsEnabled);
            }
        });

        editor.putUserData(HIGHLIGHTERS_KEY, created);

        // also refresh inspection overlay (uses current caret position)
        refreshInspectionForEditor(editor, javaFile);

        repaint(editor);
    }

    // ── inspection-mode painting ──────────────────────────────────────────────

    private void refreshInspectionForEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearInspectionHighlights(editor);
        if (!inspectionModeEnabled) return;

        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement element = javaFile.findElementAt(caretOffset);
        TextRange activeRange = findActiveRange(element);

        List<RangeHighlighter> hl = new ArrayList<>();
        Document document = editor.getDocument();
        int docLen = document.getTextLength();

        if (docLen > 0) {
            // Dim overlay over the whole file
            addLineBlockAtLayer(editor, new TextRange(0, docLen),
                    JavaStructureColors.INSPECTION_DIM_COLOR,
                    JavaStructureColors.INSPECTION_DIM_ALPHA,
                    hl, HighlighterLayer.LAST);

            // Bright overlay over active range, drawn above the dim
            if (activeRange != null) {
                addLineBlockAtLayer(editor, activeRange,
                        JavaStructureColors.INSPECTION_ACTIVE_COLOR,
                        JavaStructureColors.INSPECTION_ACTIVE_ALPHA,
                        hl, HighlighterLayer.LAST + 100);
            }
        }

        editor.putUserData(INSPECTION_HIGHLIGHTERS_KEY, hl);
        repaint(editor);
    }

    /** Walk up the PSI tree to find the tightest enclosing code snippet. */
    @Nullable
    private static TextRange findActiveRange(@Nullable PsiElement element) {
        if (element == null) return null;
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiCodeBlock codeBlock) {
                PsiElement parent = codeBlock.getParent();
                if (parent != null) return parent.getTextRange(); // method, if, for, while …
                return codeBlock.getTextRange();
            }
            if (current instanceof PsiMethod) return current.getTextRange();
            current = current.getParent();
        }
        return null;
    }

    private static void clearInspectionHighlights(@NotNull Editor editor) {
        List<RangeHighlighter> existing = editor.getUserData(INSPECTION_HIGHLIGHTERS_KEY);
        if (existing != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : existing) {
                if (h.isValid()) mm.removeHighlighter(h);
            }
            existing.clear();
            editor.putUserData(INSPECTION_HIGHLIGHTERS_KEY, null);
        }
    }

    // ── method / field annotation helpers ─────────────────────────────────────

    private static void annotateMethods(
            @NotNull PsiClass psiClass, @NotNull Editor editor,
            @NotNull List<RangeHighlighter> created, boolean nested) {
        for (PsiMethod method : psiClass.getMethods()) {
            TextRange range = method.getTextRange();
            if (range == null || range.isEmpty()) continue;
            addLineBlock(editor, range,
                    JavaStructureColors.methodLevelColor(1),
                    JavaStructureColors.methodLevelAlpha(1),
                    created, 1);
            if (nested) annotateNestedCodeBlocks(method, editor, created);
        }
    }

    private static void annotateNestedCodeBlocks(
            @NotNull PsiMethod method, @NotNull Editor editor,
            @NotNull List<RangeHighlighter> created) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return;
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitCodeBlock(@NotNull PsiCodeBlock block) {
                if (block != body) {
                    TextRange r = block.getTextRange();
                    if (r != null && !r.isEmpty()) {
                        int level = calculateMethodBlockLevel(block, body);
                        addLineBlock(editor, r,
                                JavaStructureColors.methodLevelColor(level),
                                JavaStructureColors.methodLevelAlpha(level),
                                created, level);
                    }
                }
                super.visitCodeBlock(block);
            }
        });
    }

    private static int calculateMethodBlockLevel(@NotNull PsiCodeBlock block, @NotNull PsiCodeBlock methodBody) {
        int level = 2;
        PsiElement parent = block.getParent();
        while (parent != null && parent != methodBody) {
            if (parent instanceof PsiCodeBlock) level++;
            parent = parent.getParent();
        }
        return Math.min(level, 5);
    }

    private static void annotateFieldGroups(
            @NotNull PsiClass psiClass, @NotNull Editor editor,
            @NotNull List<RangeHighlighter> created) {
        TextRange currentGroup = null;
        for (PsiElement child : psiClass.getChildren()) {
            if (child instanceof PsiField) {
                TextRange r = child.getTextRange();
                if (r == null || r.isEmpty()) continue;
                currentGroup = currentGroup == null ? r : currentGroup.union(r);
            } else if (child instanceof PsiWhiteSpace || child instanceof PsiComment || child instanceof PsiModifierList) {
                // keep group open
            } else if (isStructuralClassMember(child)) {
                flushFieldGroup(currentGroup, editor, created);
                currentGroup = null;
            }
        }
        flushFieldGroup(currentGroup, editor, created);
    }

    private static boolean isStructuralClassMember(@NotNull PsiElement child) {
        return child instanceof PsiMethod || child instanceof PsiClass || child instanceof PsiClassInitializer;
    }

    private static void flushFieldGroup(@Nullable TextRange range, @NotNull Editor editor,
                                        @NotNull List<RangeHighlighter> created) {
        if (range != null && !range.isEmpty())
            addLineBlock(editor, range,
                    JavaStructureColors.FIELD_GROUP_COLOR,
                    JavaStructureColors.FIELD_GROUP_ALPHA,
                    created, 0);
    }

    // ── low-level markup helpers ──────────────────────────────────────────────

    private static void addLineBlock(
            @NotNull Editor editor, @Nullable TextRange range,
            @NotNull Color color, float alpha,
            @NotNull List<RangeHighlighter> created, int nestingLevel) {
        addLineBlockAtLayer(editor, range, color, alpha, created,
                HighlighterLayer.ADDITIONAL_SYNTAX + Math.max(0, nestingLevel));
    }

    private static void addLineBlockAtLayer(
            @NotNull Editor editor, @Nullable TextRange range,
            @NotNull Color color, float alpha,
            @NotNull List<RangeHighlighter> created, int layer) {
        if (range == null || range.isEmpty()) return;

        Document document = editor.getDocument();
        int textLength = document.getTextLength();
        int start = Math.max(0, Math.min(range.getStartOffset(), textLength));
        int end   = Math.max(start, Math.min(range.getEndOffset(), textLength));
        if (start >= textLength && textLength > 0) start = textLength - 1;

        int startLine   = document.getLineNumber(start);
        int endLine     = document.getLineNumber(Math.max(end - 1, start));
        int startOffset = document.getLineStartOffset(startLine);
        int endOffset   = document.getLineEndOffset(endLine);
        if (endOffset <= startOffset) return;

        MarkupModel mm = editor.getMarkupModel();
        RangeHighlighter h = mm.addRangeHighlighter(
                startOffset, endOffset, layer, null, HighlighterTargetArea.LINES_IN_RANGE);
        h.setGreedyToLeft(true);
        h.setGreedyToRight(true);
        h.setCustomRenderer(new AlphaBackgroundRenderer(color, alpha));
        created.add(h);
    }

    // ── alpha-aware background renderer ──────────────────────────────────────

    /**
     * Paints a solid colour at the given opacity across every line covered by
     * its parent {@link RangeHighlighter}.  Using AlphaComposite means the
     * actual editor background and any lower-layer highlights show through,
     * giving the "tinted glass" look instead of a fully opaque box.
     */
    private static final class AlphaBackgroundRenderer implements CustomHighlighterRenderer {
        private final Color color;
        private final float alpha;

        AlphaBackgroundRenderer(@NotNull Color color, float alpha) {
            this.color = color;
            this.alpha = alpha;
        }

        @Override
        public void paint(@NotNull Editor editor,
                          @NotNull RangeHighlighter highlighter,
                          @NotNull Graphics g) {
            Document doc = editor.getDocument();
            int docLen = doc.getTextLength();
            if (docLen == 0) return;

            int startOffset = highlighter.getStartOffset();
            int endOffset   = highlighter.getEndOffset();
            if (startOffset >= endOffset) return;

            int startLine = doc.getLineNumber(startOffset);
            int endLine   = doc.getLineNumber(Math.min(endOffset, docLen - 1));
            int lineHeight = editor.getLineHeight();
            int width = editor.getContentComponent().getWidth();

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(color);
                for (int line = startLine; line <= endLine; line++) {
                    int y = editor.logicalPositionToXY(new LogicalPosition(line, 0)).y;
                    g2.fillRect(0, y, width, lineHeight);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    // ── editor helpers ────────────────────────────────────────────────────────

    private static void clearEditor(@NotNull Editor editor) {
        List<RangeHighlighter> existing = editor.getUserData(HIGHLIGHTERS_KEY);
        if (existing != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : existing) {
                if (h.isValid()) mm.removeHighlighter(h);
            }
            existing.clear();
            editor.putUserData(HIGHLIGHTERS_KEY, null);
        }
        repaint(editor);
    }

    private static void repaint(@NotNull Editor editor) {
        editor.getContentComponent().revalidate();
        editor.getContentComponent().repaint();
    }

    @Override
    public void dispose() { clearAllOpenEditors(); }
}
