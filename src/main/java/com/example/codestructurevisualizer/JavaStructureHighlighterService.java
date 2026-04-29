package com.example.codestructurevisualizer;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
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

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class JavaStructureHighlighterService implements Disposable {
    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS_KEY = Key.create("code.structure.visualizer.highlighters");

    private final Project project;
    private final Alarm refreshAlarm;
    private boolean enabled = true;
    private boolean nestedSubsectionsEnabled = false;

    public JavaStructureHighlighterService(@NotNull Project project) {
        this.project = project;
        this.refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggleEnabled() {
        enabled = !enabled;
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return enabled;
    }

    public boolean isNestedSubsectionsEnabled() {
        return nestedSubsectionsEnabled;
    }

    public boolean toggleNestedSubsectionsEnabled() {
        nestedSubsectionsEnabled = !nestedSubsectionsEnabled;
        refreshAlarm.cancelAllRequests();
        refreshAllOpenJavaEditorsNow();
        return nestedSubsectionsEnabled;
    }

    public void scheduleRefreshAll() {
        scheduleRefresh(null);
    }

    public void scheduleRefresh(@Nullable VirtualFile file) {
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(() -> {
            if (project.isDisposed()) {
                return;
            }

            if (file == null) {
                refreshAllOpenJavaEditorsNow();
            } else {
                refreshFile(file);
            }
        }, 100);
    }

    public void refreshAllOpenJavaEditorsNow() {
        Runnable refresh = () -> {
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            for (VirtualFile file : editorManager.getOpenFiles()) {
                refreshFile(file);
            }
        };

        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            refresh.run();
        } else {
            ApplicationManager.getApplication().runReadAction(refresh);
        }
    }

    private void clearAllOpenEditors() {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        for (VirtualFile file : editorManager.getOpenFiles()) {
            for (FileEditor fileEditor : editorManager.getEditors(file)) {
                if (fileEditor instanceof TextEditor textEditor) {
                    clearEditor(textEditor.getEditor());
                }
            }
        }
    }

    private void refreshFile(@NotNull VirtualFile file) {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);

        if (file.getFileType() != JavaFileType.INSTANCE) {
            for (FileEditor fileEditor : editorManager.getEditors(file)) {
                if (fileEditor instanceof TextEditor textEditor) {
                    clearEditor(textEditor.getEditor());
                }
            }
            return;
        }

        if (!enabled) {
            for (FileEditor fileEditor : editorManager.getEditors(file)) {
                if (fileEditor instanceof TextEditor textEditor) {
                    clearEditor(textEditor.getEditor());
                }
            }
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile)) {
            return;
        }

        for (FileEditor fileEditor : editorManager.getEditors(file)) {
            if (fileEditor instanceof TextEditor textEditor) {
                refreshEditor(textEditor.getEditor(), javaFile);
            }
        }
    }

    private void refreshEditor(@NotNull Editor editor, @NotNull PsiJavaFile javaFile) {
        clearEditor(editor);

        if (!enabled) {
            repaint(editor);
            return;
        }

        List<RangeHighlighter> createdHighlighters = new ArrayList<>();
        javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);
                annotateFieldGroups(aClass, editor, createdHighlighters);
                annotateMethods(aClass, editor, createdHighlighters, nestedSubsectionsEnabled);
            }
        });

        editor.putUserData(HIGHLIGHTERS_KEY, createdHighlighters);
        repaint(editor);
    }

    private static void annotateMethods(
            @NotNull PsiClass psiClass,
            @NotNull Editor editor,
            @NotNull List<RangeHighlighter> createdHighlighters,
            boolean nestedSubsectionsEnabled
    ) {
        for (PsiMethod method : psiClass.getMethods()) {
            TextRange methodRange = method.getTextRange();
            if (methodRange == null || methodRange.isEmpty()) {
                continue;
            }

            addLineBlock(editor, methodRange, JavaStructureColors.methodLevel(1), createdHighlighters, 1);
            if (nestedSubsectionsEnabled) {
                annotateNestedCodeBlocks(method, editor, createdHighlighters);
            }
        }
    }

    private static void annotateNestedCodeBlocks(
            @NotNull PsiMethod method,
            @NotNull Editor editor,
            @NotNull List<RangeHighlighter> createdHighlighters
    ) {
        PsiCodeBlock methodBody = method.getBody();
        if (methodBody == null) {
            return;
        }

        methodBody.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitCodeBlock(@NotNull PsiCodeBlock block) {
                if (block != methodBody) {
                    TextRange blockRange = block.getTextRange();
                    if (blockRange != null && !blockRange.isEmpty()) {
                        int level = calculateMethodBlockLevel(block, methodBody);
                        addLineBlock(editor, blockRange, JavaStructureColors.methodLevel(level), createdHighlighters, level);
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
            if (parent instanceof PsiCodeBlock) {
                level++;
            }
            parent = parent.getParent();
        }
        return Math.min(level, 5);
    }

    private static void annotateFieldGroups(
            @NotNull PsiClass psiClass,
            @NotNull Editor editor,
            @NotNull List<RangeHighlighter> createdHighlighters
    ) {
        TextRange currentGroup = null;

        for (PsiElement child : psiClass.getChildren()) {
            if (child instanceof PsiField) {
                TextRange fieldRange = child.getTextRange();
                if (fieldRange == null || fieldRange.isEmpty()) {
                    continue;
                }
                currentGroup = currentGroup == null ? fieldRange : currentGroup.union(fieldRange);
            } else if (child instanceof PsiWhiteSpace || child instanceof PsiComment || child instanceof PsiModifierList) {
                // Keep the current group open across whitespace/comments/modifiers.
            } else if (isStructuralClassMember(child)) {
                flushFieldGroup(currentGroup, editor, createdHighlighters);
                currentGroup = null;
            }
        }

        flushFieldGroup(currentGroup, editor, createdHighlighters);
    }

    private static boolean isStructuralClassMember(@NotNull PsiElement child) {
        return child instanceof PsiMethod || child instanceof PsiClass || child instanceof PsiClassInitializer;
    }

    private static void flushFieldGroup(
            @Nullable TextRange range,
            @NotNull Editor editor,
            @NotNull List<RangeHighlighter> createdHighlighters
    ) {
        if (range != null && !range.isEmpty()) {
            addLineBlock(editor, range, JavaStructureColors.FIELD_GROUP, createdHighlighters, 0);
        }
    }

    private static void addLineBlock(
            @NotNull Editor editor,
            @Nullable TextRange range,
            @NotNull com.intellij.openapi.editor.markup.TextAttributes textAttributes,
            @NotNull List<RangeHighlighter> createdHighlighters,
            int nestingLevel
    ) {
        if (range == null || range.isEmpty()) {
            return;
        }

        Document document = editor.getDocument();
        int textLength = document.getTextLength();
        int start = Math.max(0, Math.min(range.getStartOffset(), textLength));
        int end = Math.max(start, Math.min(range.getEndOffset(), textLength));
        if (start >= textLength && textLength > 0) {
            start = textLength - 1;
        }

        int startLine = document.getLineNumber(start);
        int endLine = document.getLineNumber(Math.max(end - 1, start));
        int startOffset = document.getLineStartOffset(startLine);
        int endOffset = document.getLineEndOffset(endLine);

        if (endOffset <= startOffset) {
            return;
        }

        MarkupModel markupModel = editor.getMarkupModel();
        RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX + Math.max(0, nestingLevel),
                textAttributes,
                HighlighterTargetArea.LINES_IN_RANGE
        );
        highlighter.setGreedyToLeft(true);
        highlighter.setGreedyToRight(true);
        createdHighlighters.add(highlighter);
    }

    private static void clearEditor(@NotNull Editor editor) {
        List<RangeHighlighter> existingHighlighters = editor.getUserData(HIGHLIGHTERS_KEY);
        if (existingHighlighters != null) {
            MarkupModel markupModel = editor.getMarkupModel();
            for (RangeHighlighter highlighter : existingHighlighters) {
                if (highlighter.isValid()) {
                    markupModel.removeHighlighter(highlighter);
                }
            }
            existingHighlighters.clear();
            editor.putUserData(HIGHLIGHTERS_KEY, null);
        }
        repaint(editor);
    }

    private static void repaint(@NotNull Editor editor) {
        editor.getContentComponent().revalidate();
        editor.getContentComponent().repaint();
    }

    @Override
    public void dispose() {
        clearAllOpenEditors();
    }
}
