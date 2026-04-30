package me.netizendev.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles inspection mode (Ctrl+Alt+Shift+I).
 * All code outside the bracket / method containing the cursor is dimmed.
 */
public final class ToggleInspectionModeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        JavaStructureHighlighterService service =
                project.getService(JavaStructureHighlighterService.class);
        boolean nowEnabled = service.toggleInspectionMode();
        service.refreshAllOpenJavaEditorsNow();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(
                        nowEnabled ? "Inspection mode enabled" : "Inspection mode disabled",
                        NotificationType.INFORMATION)
                .notify(project);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null);
        if (project == null) return;
        JavaStructureHighlighterService service =
                project.getService(JavaStructureHighlighterService.class);
        event.getPresentation().setText(service.isInspectionModeEnabled()
                ? "Disable Inspection Mode" : "Enable Inspection Mode");
    }
}

