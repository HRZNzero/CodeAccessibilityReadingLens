package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts invisible inline inlays (2 px gap) at camelCase / snake_case word
 * boundaries inside method names, field names, class names, local variables,
 * and parameters.  The first character of each new word is also made slightly
 * bold via a {@link RangeHighlighter}.
 */
@Service(Service.Level.PROJECT)
public final class CamelCaseSpacingService implements Disposable {

    /** Tiny extra horizontal space (pixels) at each word boundary – intentionally
     *  smaller than a full space so it reads as "slightly wider" not "two words". */
    private static final int GAP_PX = 2;

    private static final int BOLD_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX + 100;

    private static final Key<List<Inlay<?>>>          INLAYS_KEY = Key.create("code.structure.camelcase.inlays");
    private static final Key<List<RangeHighlighter>>  BOLD_KEY   = Key.create("code.structure.camelcase.bold");

    private final Project project;
    private final Alarm alarm;
    private boolean enabled = false;

    public CamelCaseSpacingService(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    // ── public API ────────────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }

    public boolean toggleEnabled() {
        enabled = !enabled;
        alarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return enabled;
    }

    public void scheduleRefreshAll() { scheduleRefresh(null); }

    public void scheduleRefresh(@Nullable VirtualFile file) {
        alarm.cancelAllRequests();
        alarm.addRequest(() -> {
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

    // ── internal refresh ──────────────────────────────────────────────────────

    private void refreshFile(@NotNull VirtualFile file) {
        FileEditorManager em = FileEditorManager.getInstance(project);
        if (file.getFileType() != JavaFileType.INSTANCE || !enabled) {
            for (FileEditor fe : em.getEditors(file))
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
            return;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile)) return;
        for (FileEditor fe : em.getEditors(file))
            if (fe instanceof TextEditor te) refreshEditor(te.getEditor(), javaFile);
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);
        if (!enabled) return;

        InlayModel  inlayModel  = editor.getInlayModel();
        MarkupModel markupModel = editor.getMarkupModel();
        List<Inlay<?>>         createdInlays = new ArrayList<>();
        List<RangeHighlighter> createdBolds  = new ArrayList<>();

        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            // ── named declarations ────────────────────────────────────────────
            @Override public void visitMethod(@NotNull PsiMethod m) {
                super.visitMethod(m);
                process(m.getNameIdentifier());
            }
            @Override public void visitField(@NotNull PsiField f) {
                super.visitField(f);
                process(f.getNameIdentifier());
            }
            @Override public void visitClass(@NotNull PsiClass c) {
                super.visitClass(c);
                process(c.getNameIdentifier());
            }
            // ── variables & parameters ────────────────────────────────────────
            @Override public void visitLocalVariable(@NotNull PsiLocalVariable v) {
                super.visitLocalVariable(v);
                process(v.getNameIdentifier());
            }
            @Override public void visitParameter(@NotNull PsiParameter p) {
                super.visitParameter(p);
                process(p.getNameIdentifier());
            }
            // ── shared helper ─────────────────────────────────────────────────
            private void process(@Nullable PsiIdentifier id) {
                if (id == null) return;
                addBoundaryInlays(inlayModel, id, createdInlays);
                addBoldBoundaryHighlighters(markupModel, id, createdBolds);
            }
        });

        editor.putUserData(INLAYS_KEY, createdInlays);
        editor.putUserData(BOLD_KEY,   createdBolds);
    }

    // ── boundary detection: inlay gaps ────────────────────────────────────────

    private static void addBoundaryInlays(
            @NotNull InlayModel inlayModel, @NotNull PsiIdentifier id,
            @NotNull List<Inlay<?>> collected) {
        String name = id.getText();
        if (name == null || name.length() < 2) return;
        int base = id.getTextRange().getStartOffset();
        for (int i = 1; i < name.length(); i++) {
            if (isBoundary(name, i)) {
                Inlay<SpaceRenderer> inlay =
                        inlayModel.addInlineElement(base + i, false, new SpaceRenderer());
                if (inlay != null) collected.add(inlay);
            }
        }
    }

    // ── boundary detection: bold markers ─────────────────────────────────────

    private static void addBoldBoundaryHighlighters(
            @NotNull MarkupModel mm, @Nullable PsiIdentifier id,
            @NotNull List<RangeHighlighter> collected) {
        if (id == null) return;
        String name = id.getText();
        if (name == null || name.length() < 2) return;
        int base = id.getTextRange().getStartOffset();
        TextAttributes boldAttrs = new TextAttributes(null, null, null, null, Font.BOLD);
        for (int i = 1; i < name.length(); i++) {
            if (isBoundary(name, i)) {
                RangeHighlighter h = mm.addRangeHighlighter(
                        base + i, base + i + 1, BOLD_LAYER, boldAttrs,
                        HighlighterTargetArea.EXACT_RANGE);
                collected.add(h);
            }
        }
    }

    private static boolean isBoundary(String name, int i) {
        char prev = name.charAt(i - 1);
        char curr = name.charAt(i);
        return (Character.isLowerCase(prev) && Character.isUpperCase(curr)) // camelCase
                || (prev == '_' && curr != '_');                              // snake_case
    }

    // ── editor cleanup ────────────────────────────────────────────────────────

    private static void clearEditor(@NotNull Editor editor) {
        List<Inlay<?>> inlays = editor.getUserData(INLAYS_KEY);
        if (inlays != null) {
            for (Inlay<?> inlay : inlays) if (inlay.isValid()) Disposer.dispose(inlay);
            inlays.clear();
            editor.putUserData(INLAYS_KEY, null);
        }
        List<RangeHighlighter> bolds = editor.getUserData(BOLD_KEY);
        if (bolds != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : bolds) if (h.isValid()) mm.removeHighlighter(h);
            bolds.clear();
            editor.putUserData(BOLD_KEY, null);
        }
    }

    @Override
    public void dispose() {
        FileEditorManager em = FileEditorManager.getInstance(project);
        for (VirtualFile f : em.getOpenFiles())
            for (FileEditor fe : em.getEditors(f))
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
    }

    // ── inlay renderer ────────────────────────────────────────────────────────

    private static final class SpaceRenderer implements EditorCustomElementRenderer {
        @Override public int calcWidthInPixels(@NotNull Inlay inlay) { return GAP_PX; }
        @Override public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                                    @NotNull Rectangle r, @NotNull TextAttributes ta) { }
    }
}
