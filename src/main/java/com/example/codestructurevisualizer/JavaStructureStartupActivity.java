package com.example.codestructurevisualizer;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public final class JavaStructureStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        JavaStructureHighlighterService service =
                project.getService(JavaStructureHighlighterService.class);
        CamelCaseSpacingService spacingService =
                project.getService(CamelCaseSpacingService.class);
        SyntaxEmphasisService emphasisService =
                project.getService(SyntaxEmphasisService.class);
        MarkerService markerService =
                project.getService(MarkerService.class);

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
                        markerService.scheduleRefresh(file);
                    }

                    @Override
                    public void selectionChanged(
                            @NotNull com.intellij.openapi.fileEditor.FileEditorManagerEvent event) {
                        service.scheduleRefresh(event.getNewFile());
                        spacingService.scheduleRefresh(event.getNewFile());
                        emphasisService.scheduleRefresh(event.getNewFile());
                        markerService.scheduleRefresh(event.getNewFile());
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

        // ── caret listener – refreshes inspection overlay AND cf-focus on cursor move ─────
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(
                new CaretListener() {
                    @Override
                    public void caretPositionChanged(@NotNull CaretEvent e) {
                        Editor editor = e.getEditor();
                        VirtualFile file = FileDocumentManager.getInstance()
                                .getFile(editor.getDocument());
                        if (file == null) return;
                        if (service.isInspectionModeEnabled()) {
                            service.scheduleInspectionRefreshForEditor(editor, file);
                        }
                        if (service.isControlFlowModeEnabled()) {
                            service.scheduleControlFlowFocusRefreshForEditor(editor, file);
                        }
                    }
                }, service);

        service.scheduleRefreshAll();
        spacingService.scheduleRefreshAll();
        emphasisService.scheduleRefreshAll();
        markerService.scheduleRefreshAll();

        // ── strip Ctrl+Alt+M from ExtractMethod so our PlaceMarker wins ──────
        // plugin.xml shortcut registration alone cannot beat a built-in binding;
        // we must programmatically remove it from the active (mutable) keymap.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.intellij.openapi.actionSystem.KeyboardShortcut ctrlAltM =
                        new com.intellij.openapi.actionSystem.KeyboardShortcut(
                                KeyStroke.getKeyStroke(KeyEvent.VK_M,
                                        InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                                null);
                Keymap active = KeymapManager.getInstance().getActiveKeymap();
                if (active.canModify()) {
                    active.removeShortcut("ExtractMethod", ctrlAltM);
                    active.addShortcut("CodeStructureVisualizer.PlaceMarker", ctrlAltM);
                }
            } catch (Exception ignored) {
                // best-effort: works for mutable (user) keymaps
            }
        });
    }
}
