package me.netizendev.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles wider field-row spacing (Ctrl+Alt+Shift+L).
 *
 * When on, each field is annotated individually and a small top-inset (4 px)
 * is applied to its background band.  Adjacent field rows therefore show a
 * thin dark separator line between them, making fields easier to tell apart.
 */
public final class ToggleWiderFieldSpacingAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        boolean on = svc.toggleWiderFieldSpacing();
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(on ? "Wider field spacing enabled" : "Wider field spacing disabled",
                        NotificationType.INFORMATION)
                .notify(p);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabled(p != null);
        if (p == null) return;
        JavaStructureHighlighterService svc = p.getService(JavaStructureHighlighterService.class);
        e.getPresentation().setText(svc.isWiderFieldSpacingEnabled()
                ? "Disable Wider Field Spacing"
                : "Enable Wider Field Spacing");
    }
}

