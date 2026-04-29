package com.example.codestructurevisualizer;

import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class JavaStructureStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        JavaStructureHighlighterService service = project.getService(JavaStructureHighlighterService.class);

        project.getMessageBus().connect(service).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(@NotNull com.intellij.openapi.fileEditor.FileEditorManager source, @NotNull VirtualFile file) {
                        service.scheduleRefresh(file);
                    }

                    @Override
                    public void selectionChanged(@NotNull com.intellij.openapi.fileEditor.FileEditorManagerEvent event) {
                        service.scheduleRefresh(event.getNewFile());
                    }
                }
        );

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                service.scheduleRefresh(file);
            }
        }, service);

        service.scheduleRefreshAll();
    }
}
