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
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts invisible inline inlays at camelCase / snake_case word boundaries inside
 * method names, field names, and class names so the editor renders a small extra gap
 * at each word transition.
 *
 * <p>Additionally, the first character of each new word (right after the gap) is made
 * slightly <b>bold</b> via a {@link RangeHighlighter} with {@link Font#BOLD}, so the
 * word boundary is also subtly emphasised in weight without changing any colours.</p>
 */
@Service(Service.Level.PROJECT)
public final class CamelCaseSpacingService implements Disposable {

    /** Extra horizontal space in pixels inserted at each word boundary. */
    private static final int GAP_PX = 4;

    /**
     * Layer for the bold highlighters.  Must be above
     * {@code HighlighterLayer.ADDITIONAL_SYNTAX} so the bold flag is OR-ed in
     * after IntelliJ's own syntax attributes are applied.
     */
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
            for (FileEditor fe : em.getEditors(file)) {
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
            }
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile)) return;

        for (FileEditor fe : em.getEditors(file)) {
            if (fe instanceof TextEditor te) refreshEditor(te.getEditor(), javaFile);
        }
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);
        if (!enabled) return;

        InlayModel         inlayModel = editor.getInlayModel();
        MarkupModel        markupModel = editor.getMarkupModel();
        List<Inlay<?>>         createdInlays = new ArrayList<>();
        List<RangeHighlighter> createdBolds  = new ArrayList<>();

        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                PsiIdentifier id = method.getNameIdentifier();
                if (id != null) {
                    addBoundaryInlays(inlayModel, id, createdInlays);
                    addBoldBoundaryHighlighters(markupModel, id, createdBolds);
                }
            }
            @Override public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                PsiIdentifier id = field.getNameIdentifier();
                addBoundaryInlays(inlayModel, id, createdInlays);
                addBoldBoundaryHighlighters(markupModel, id, createdBolds);
            }
            @Override public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                PsiIdentifier id = aClass.getNameIdentifier();
                if (id != null) {
                    addBoundaryInlays(inlayModel, id, createdInlays);
                    addBoldBoundaryHighlighters(markupModel, id, createdBolds);
                }
            }
        });

        editor.putUserData(INLAYS_KEY, createdInlays);
        editor.putUserData(BOLD_KEY,   createdBolds);
    }

    // ── boundary detection: inlay gaps ────────────────────────────────────────

    private static void addBoundaryInlays(
            @NotNull InlayModel inlayModel,
            @NotNull PsiIdentifier id,
            @NotNull List<Inlay<?>> collected) {

        String name = id.getText();
        if (name == null || name.length() < 2) return;
        int startOffset = id.getTextRange().getStartOffset();

        for (int i = 1; i < name.length(); i++) {
            char prev = name.charAt(i - 1);
            char curr = name.charAt(i);
            boolean camelBoundary = Character.isLowerCase(prev) && Character.isUpperCase(curr);
            boolean snakeBoundary = prev == '_' && curr != '_';
            if (camelBoundary || snakeBoundary) {
                int offset = startOffset + i;
                Inlay<SpaceRenderer> inlay = inlayModel.addInlineElement(
                        offset, false, new SpaceRenderer());
                if (inlay != null) collected.add(inlay);
            }
        }
    }

    // ── boundary detection: bold markers ─────────────────────────────────────

    /**
     * At every word boundary found in {@code id}, places a one-character wide
     * {@link RangeHighlighter} with {@code FontType = Font.BOLD} on the
     * <em>first character of the new word</em>.  The null foreground means
     * IntelliJ will inherit the existing syntax colour while OR-ing in bold.
     */
    private static void addBoldBoundaryHighlighters(
            @NotNull MarkupModel markupModel,
            @Nullable PsiIdentifier id,
            @NotNull List<RangeHighlighter> collected) {

        if (id == null) return;
        String name = id.getText();
        if (name == null || name.length() < 2) return;
        int startOffset = id.getTextRange().getStartOffset();

        // Bold attrs: null foreground → inherit syntax colour; BOLD OR-ed in.
        TextAttributes boldAttrs = new TextAttributes(null, null, null, null, Font.BOLD);

        for (int i = 1; i < name.length(); i++) {
            char prev = name.charAt(i - 1);
            char curr = name.charAt(i);
            boolean camelBoundary = Character.isLowerCase(prev) && Character.isUpperCase(curr);
            boolean snakeBoundary = prev == '_' && curr != '_';
            if (camelBoundary || snakeBoundary) {
                int charOffset = startOffset + i;
                RangeHighlighter h = markupModel.addRangeHighlighter(
                        charOffset, charOffset + 1,
                        BOLD_LAYER, boldAttrs,
                        HighlighterTargetArea.EXACT_RANGE);
                collected.add(h);
            }
        }
    }

    // ── editor cleanup ────────────────────────────────────────────────────────

    private static void clearEditor(@NotNull Editor editor) {
        // remove gap inlays
        List<Inlay<?>> inlays = editor.getUserData(INLAYS_KEY);
        if (inlays != null) {
            for (Inlay<?> inlay : inlays) if (inlay.isValid()) Disposer.dispose(inlay);
            inlays.clear();
            editor.putUserData(INLAYS_KEY, null);
        }
        // remove bold highlighters
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
        for (VirtualFile file : em.getOpenFiles()) {
            for (FileEditor fe : em.getEditors(file)) {
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
            }
        }
    }

    // ── inlay renderer ────────────────────────────────────────────────────────

    /** {@value GAP_PX}-pixel-wide transparent renderer: only the width matters. */
    private static final class SpaceRenderer implements EditorCustomElementRenderer {
        @Override public int calcWidthInPixels(@NotNull Inlay inlay) { return GAP_PX; }
        @Override public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                                    @NotNull Rectangle targetRegion,
                                    @NotNull TextAttributes textAttributes) { /* no-op */ }
    }
}
