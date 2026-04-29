package com.example.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles "indented boxes" mode (Ctrl+Alt+Shift+X).
 *
 * When on, every background band starts at the indentation column of its
 * block's opening line rather than the left margin, creating a visual
 * "nested box" look that mirrors the code's bracket depth.
 */
public final class ToggleIndentedBoxesAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        boolean on = svc.toggleIndentedBoxes();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(on ? "Indented boxes enabled" : "Indented boxes disabled",
                        NotificationType.INFORMATION)
                .notify(p);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabled(p != null);
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        e.getPresentation().setText(svc.isIndentedBoxesEnabled()
                ? "Disable Indented Boxes"
                : "Enable Indented Boxes");
    }
}

