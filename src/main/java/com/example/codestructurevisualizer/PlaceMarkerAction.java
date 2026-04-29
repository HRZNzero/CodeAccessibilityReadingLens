package com.example.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles a marker on the current caret line (Ctrl+Alt+M).
 *
 * Markers are shown as a 3 px orange stripe on the left gutter and as an
 * orange stripe in the scrollbar.  Use Ctrl+Alt+Down / Up to navigate
 * between them, and Ctrl+Alt+Shift+H to enable "Marker Focus Mode" which
 * dims and folds all non-marked sections.
 */
public final class PlaceMarkerAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        Editor  editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (p == null || editor == null || file == null) return;

        MarkerService svc = p.getService(MarkerService.class);
        svc.toggleMarker(editor, file);

        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification("Marker toggled on line "
                        + (editor.getCaretModel().getLogicalPosition().line + 1),
                        NotificationType.INFORMATION)
                .notify(p);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(
                e.getProject() != null
                && e.getData(CommonDataKeys.EDITOR) != null
                && e.getData(CommonDataKeys.VIRTUAL_FILE) != null);
    }
}

