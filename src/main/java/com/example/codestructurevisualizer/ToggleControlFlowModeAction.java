package com.example.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles Control-Flow View mode (Ctrl+Shift+O).
 *
 * While active the normal method / bracket-level colouring is replaced by a
 * single orange overlay (#d17524, 55 % opacity) drawn on every if/else chain
 * and switch statement, making it easy to spot which lines belong to the same
 * conditional.  Field backgrounds remain visible.  Press the shortcut again
 * to return to normal view.
 */
public final class ToggleControlFlowModeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        boolean on = svc.toggleControlFlowMode();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(on ? "Control-flow view enabled" : "Control-flow view disabled",
                        NotificationType.INFORMATION)
                .notify(p);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabled(p != null);
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        e.getPresentation().setText(svc.isControlFlowModeEnabled()
                ? "Disable Control-Flow View"
                : "Enable Control-Flow View");
    }
}

