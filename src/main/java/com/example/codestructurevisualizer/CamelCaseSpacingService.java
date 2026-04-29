package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
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

import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts invisible inline inlays at camelCase / snake_case word boundaries
 * inside method names, field names, and class names so that the editor
 * renders a small extra gap at each word transition.
 *
 * <p>camelCase: extra gap placed just before every uppercase letter that
 * follows a lowercase letter  →  {@code generatePlayerData} looks like
 * {@code generate · Player · Data}.</p>
 *
 * <p>snake_case: extra gap placed just after every {@code _} separator
 * →  {@code generate_player_data} looks like
 * {@code generate_ · player_ · data}.</p>
 */
@Service(Service.Level.PROJECT)
public final class CamelCaseSpacingService implements Disposable {

    /** Extra horizontal space in pixels inserted at each word boundary. */
    private static final int GAP_PX = 4;

    private static final Key<List<Inlay<?>>> INLAYS_KEY =
            Key.create("code.structure.camelcase.inlays");

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

        InlayModel inlayModel = editor.getInlayModel();
        List<Inlay<?>> created = new ArrayList<>();

        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                PsiIdentifier id = method.getNameIdentifier();
                if (id != null) addBoundaryInlays(inlayModel, id, created);
            }
            @Override public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                addBoundaryInlays(inlayModel, field.getNameIdentifier(), created);
            }
            @Override public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                PsiIdentifier id = aClass.getNameIdentifier();
                if (id != null) addBoundaryInlays(inlayModel, id, created);
            }
        });

        editor.putUserData(INLAYS_KEY, created);
    }

    /**
     * Scans the name {@code id} for camelCase and snake_case boundaries and
     * inserts a narrow invisible inlay at each one.
     */
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
            // snake_case: insert gap right after the '_' (position i) when prev=='_'
            boolean snakeBoundary = prev == '_' && curr != '_';

            if (camelBoundary || snakeBoundary) {
                int offset = startOffset + i;
                Inlay<SpaceRenderer> inlay = inlayModel.addInlineElement(
                        offset, /*relatesToPrecedingText=*/ false, new SpaceRenderer());
                if (inlay != null) collected.add(inlay);
            }
        }
    }

    private static void clearEditor(@NotNull Editor editor) {
        List<Inlay<?>> existing = editor.getUserData(INLAYS_KEY);
        if (existing != null) {
            for (Inlay<?> inlay : existing) {
                if (inlay.isValid()) Disposer.dispose(inlay);
            }
            existing.clear();
            editor.putUserData(INLAYS_KEY, null);
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

    /**
     * Zero-height, {@value GAP_PX}-pixel-wide transparent renderer that just
     * pushes characters apart at word boundaries.
     */
    private static final class SpaceRenderer implements EditorCustomElementRenderer {

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return GAP_PX;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                          @NotNull Rectangle targetRegion,
                          @NotNull TextAttributes textAttributes) {
            // Intentionally empty – only the width matters.
        }
    }
}

