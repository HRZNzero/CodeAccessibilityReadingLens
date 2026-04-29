package com.example.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles camelCase / snake_case visual word-spacing (Ctrl+Alt+Shift+W).
 *
 * When enabled, a small invisible gap is inserted between every word in
 * method names, field names, and class names so that camelCase and
 * snake_case identifiers are easier to read at a glance.
 */
public final class ToggleCamelCaseSpacingAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        CamelCaseSpacingService service =
                project.getService(CamelCaseSpacingService.class);
        boolean nowEnabled = service.toggleEnabled();
        service.refreshAllOpenJavaEditorsNow();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(
                        nowEnabled ? "CamelCase / snake_case word-spacing enabled"
                                   : "CamelCase / snake_case word-spacing disabled",
                        NotificationType.INFORMATION)
                .notify(project);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null);
        if (project == null) return;
        CamelCaseSpacingService service =
                project.getService(CamelCaseSpacingService.class);
        event.getPresentation().setText(service.isEnabled()
                ? "Disable Identifier Word-Spacing"
                : "Enable Identifier Word-Spacing");
    }
}

