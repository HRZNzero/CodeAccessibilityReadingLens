package me.netizendev.codestructurevisualizer;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Toggles "Marker Focus Mode" (Ctrl+Alt+Shift+Z).
 *
 * When active, all lines that do NOT have a marker placed on them are:
 *   • dimmed with a 62 % dark overlay, AND
 *   • folded (runs of ≥ 3 consecutive unmarked lines are collapsed to "  ⋮  ")
 *
 * This lets you see only the sections you are currently reworking and jump
 * between them with Ctrl+Alt+Down / Up.
 */
public final class ToggleMarkerFocusAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null) return;
        MarkerService svc = p.getService(MarkerService.class);
        boolean wasOn = svc.isFocusModeEnabled();
        boolean on    = svc.toggleFocusMode();

        // Detect the "rejected because no markers exist" case: the toggle
        // was off, requested to enable, but the service kept it off.
        String message;
        NotificationType type = NotificationType.INFORMATION;
        if (!wasOn && !on) {
            message = "No markers placed yet — press Ctrl+Alt+M on a line first";
            type    = NotificationType.WARNING;
        } else {
            message = on ? "Marker focus mode enabled"
                         : "Marker focus mode disabled";
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Structure Visualizer")
                .createNotification(message, type)
                .notify(p);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabled(p != null);
        if (p == null) return;
        MarkerService svc = p.getService(MarkerService.class);
        e.getPresentation().setText(svc.isFocusModeEnabled()
                ? "Disable Marker Focus Mode"
                : "Enable Marker Focus Mode");
    }
}

