package com.example.codestructurevisualizer;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class JavaStructureStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        JavaStructureHighlighterService service =
                project.getService(JavaStructureHighlighterService.class);
        CamelCaseSpacingService spacingService =
                project.getService(CamelCaseSpacingService.class);
        SyntaxEmphasisService emphasisService =
                project.getService(SyntaxEmphasisService.class);

        // ── file-open / selection-change listener ────────────────────────────
        project.getMessageBus().connect(service).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(
                            @NotNull com.intellij.openapi.fileEditor.FileEditorManager source,
                            @NotNull VirtualFile file) {
                        service.scheduleRefresh(file);
                        spacingService.scheduleRefresh(file);
                        emphasisService.scheduleRefresh(file);
                    }

                    @Override
                    public void selectionChanged(
                            @NotNull com.intellij.openapi.fileEditor.FileEditorManagerEvent event) {
                        service.scheduleRefresh(event.getNewFile());
                        spacingService.scheduleRefresh(event.getNewFile());
                        emphasisService.scheduleRefresh(event.getNewFile());
                    }
                }
        );

        // ── document-change listener ─────────────────────────────────────────
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void documentChanged(@NotNull DocumentEvent event) {
                        VirtualFile file =
                                FileDocumentManager.getInstance().getFile(event.getDocument());
                        service.scheduleRefresh(file);
                        spacingService.scheduleRefresh(file);
                        emphasisService.scheduleRefresh(file);
                    }
                }, service);

        // ── caret listener – refreshes inspection overlay on cursor move ─────
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(
                new CaretListener() {
                    @Override
                    public void caretPositionChanged(@NotNull CaretEvent e) {
                        if (!service.isInspectionModeEnabled()) return;
                        Editor editor = e.getEditor();
                        VirtualFile file = FileDocumentManager.getInstance()
                                .getFile(editor.getDocument());
                        if (file != null) {
                            service.scheduleInspectionRefreshForEditor(editor, file);
                        }
                    }
                }, service);

        service.scheduleRefreshAll();
        spacingService.scheduleRefreshAll();
        emphasisService.scheduleRefreshAll();
    }
}
