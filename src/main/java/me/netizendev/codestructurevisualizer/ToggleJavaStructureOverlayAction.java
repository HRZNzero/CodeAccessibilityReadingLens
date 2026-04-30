package me.netizendev.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ToggleJavaStructureOverlayAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        JavaStructureHighlighterService service = project.getService(JavaStructureHighlighterService.class);
        boolean nowEnabled = service.toggleEnabled();
        service.refreshAllOpenJavaEditorsNow();

        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(
                        nowEnabled ? "Code structure overlay enabled" : "Code structure overlay disabled",
                        NotificationType.INFORMATION
                )
                .notify(project);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null);

        if (project == null) {
            return;
        }

        JavaStructureHighlighterService service = project.getService(JavaStructureHighlighterService.class);
        event.getPresentation().setText(service.isEnabled()
                ? "Disable Code Structure Overlay"
                : "Enable Code Structure Overlay");
    }
}
