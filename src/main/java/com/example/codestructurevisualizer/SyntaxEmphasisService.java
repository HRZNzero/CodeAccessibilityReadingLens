package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiManager;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies bold (and optionally re-coloured) {@link RangeHighlighter}s to Java
 * syntax tokens as configured in {@link SyntaxEmphasisSettings}.
 *
 * <p>Highlights are placed at {@code HighlighterLayer.ADDITIONAL_SYNTAX + 100},
 * which is above IntelliJ's own syntax-colouring layer but below selections and
 * inspection markers. {@code TextAttributes.merge()} OR-combines font-type flags,
 * so a null foreground here means the syntax colour flows through unchanged while
 * bold is added on top.</p>
 */
@Service(Service.Level.PROJECT)
public final class SyntaxEmphasisService implements Disposable {

    /** Above ADDITIONAL_SYNTAX but well below SELECTION / inspections. */
    private static final int LAYER = HighlighterLayer.ADDITIONAL_SYNTAX + 100;

    private static final Key<List<RangeHighlighter>> KEY =
            Key.create("code.structure.syntax.emphasis");

    private final Project project;
    private final Alarm alarm;

    public SyntaxEmphasisService(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    // ── public API ─────────────────────────────────────────────────────────────

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
            if (project.isDisposed()) return;
            FileEditorManager em = FileEditorManager.getInstance(project);
            for (VirtualFile f : em.getOpenFiles()) refreshFile(f);
        };
        if (ApplicationManager.getApplication().isReadAccessAllowed()) r.run();
        else ApplicationManager.getApplication().runReadAction(r);
    }

    public static @NotNull SyntaxEmphasisService getInstance(@NotNull Project project) {
        return project.getService(SyntaxEmphasisService.class);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void refreshFile(@NotNull VirtualFile file) {
        FileEditorManager em = FileEditorManager.getInstance(project);
        if (file.getFileType() != JavaFileType.INSTANCE) {
            for (FileEditor fe : em.getEditors(file))
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
            return;
        }
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (!(psi instanceof PsiJavaFile jf)) {
            for (FileEditor fe : em.getEditors(file))
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
            return;
        }
        for (FileEditor fe : em.getEditors(file))
            if (fe instanceof TextEditor te) refreshEditor(te.getEditor(), jf);
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);
        SyntaxEmphasisSettings settings = SyntaxEmphasisSettings.getInstance(project);
        List<RangeHighlighter> created = new ArrayList<>();

        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                if (!(element instanceof PsiJavaToken t)) return;
                handleToken(editor, t, t.getTokenType(), settings, created);
            }
        });

        editor.putUserData(KEY, created);
        editor.getContentComponent().repaint();
    }

    // ── token dispatch ─────────────────────────────────────────────────────────

    private static void handleToken(
            @NotNull Editor editor,
            @NotNull PsiJavaToken token,
            @NotNull IElementType type,
            @NotNull SyntaxEmphasisSettings settings,
            @NotNull List<RangeHighlighter> created) {

        if      (type == JavaTokenType.IF_KEYWORD)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.IF_KEYWORD, settings, created);
        else if (type == JavaTokenType.LPARENTH || type == JavaTokenType.RPARENTH)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.PARENTHESES, settings, created);
        else if (type == JavaTokenType.QUEST)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.QUEST, settings, created);
        else if (type == JavaTokenType.COLON)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.COLON, settings, created);
        else if (type == JavaTokenType.SEMICOLON)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.SEMICOLON, settings, created);
        else if (type == JavaTokenType.STRING_LITERAL)
            applyStringQuotes(editor, token, settings, created);
        else if (type == JavaTokenType.DOT)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.DOT, settings, created);
        else if (type == JavaTokenType.LBRACE || type == JavaTokenType.RBRACE)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.BRACES, settings, created);
        else if (type == JavaTokenType.LBRACKET || type == JavaTokenType.RBRACKET)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.BRACKETS, settings, created);
        else if (type == JavaTokenType.LT || type == JavaTokenType.GT)
            apply(editor, token, SyntaxEmphasisSettings.TokenKind.ANGLE_BRACKETS, settings, created);
    }

    /** Highlights just the opening and closing {@code "} of a string literal. */
    private static void applyStringQuotes(
            @NotNull Editor editor, @NotNull PsiJavaToken token,
            @NotNull SyntaxEmphasisSettings settings,
            @NotNull List<RangeHighlighter> created) {
        SyntaxEmphasisSettings.TokenKind kind = SyntaxEmphasisSettings.TokenKind.STRING_QUOTES;
        if (!settings.isEnabled(kind)) return;
        TextAttributes attrs = makeAttrs(settings.color(kind));
        int s = token.getTextRange().getStartOffset();
        int e = token.getTextRange().getEndOffset();
        if (e - s >= 2) {
            addHighlighter(editor, s,     s + 1, attrs, created); // opening "
            addHighlighter(editor, e - 1, e,     attrs, created); // closing "
        }
    }

    /** Highlights the full range of {@code token} with bold + optional colour. */
    private static void apply(
            @NotNull Editor editor, @NotNull PsiJavaToken token,
            @NotNull SyntaxEmphasisSettings.TokenKind kind,
            @NotNull SyntaxEmphasisSettings settings,
            @NotNull List<RangeHighlighter> created) {
        if (!settings.isEnabled(kind)) return;
        TextAttributes attrs = makeAttrs(settings.color(kind));
        int s = token.getTextRange().getStartOffset();
        int e = token.getTextRange().getEndOffset();
        addHighlighter(editor, s, e, attrs, created);
    }

    // ── low-level helpers ──────────────────────────────────────────────────────

    /**
     * Creates {@link TextAttributes} with {@link Font#BOLD} and an optional
     * foreground colour.  Passing {@code null} for colour leaves the foreground
     * as {@code null}, which causes IntelliJ's attribute-merge step to retain
     * the underlying syntax colour while OR-ing in the bold flag.
     */
    private static @NotNull TextAttributes makeAttrs(@Nullable Color color) {
        return new TextAttributes(color, null, null, null, Font.BOLD);
    }

    private static void addHighlighter(
            @NotNull Editor editor,
            int start, int end,
            @NotNull TextAttributes attrs,
            @NotNull List<RangeHighlighter> created) {
        if (start >= end) return;
        MarkupModel mm = editor.getMarkupModel();
        RangeHighlighter h = mm.addRangeHighlighter(
                start, end, LAYER, attrs, HighlighterTargetArea.EXACT_RANGE);
        created.add(h);
    }

    private static void clearEditor(@NotNull Editor editor) {
        List<RangeHighlighter> ex = editor.getUserData(KEY);
        if (ex != null) {
            MarkupModel mm = editor.getMarkupModel();
            for (RangeHighlighter h : ex) if (h.isValid()) mm.removeHighlighter(h);
            ex.clear();
            editor.putUserData(KEY, null);
        }
    }

    @Override
    public void dispose() {
        if (project.isDisposed()) return;
        FileEditorManager em = FileEditorManager.getInstance(project);
        for (VirtualFile f : em.getOpenFiles())
            for (FileEditor fe : em.getEditors(f))
                if (fe instanceof TextEditor te) clearEditor(te.getEditor());
    }
}

