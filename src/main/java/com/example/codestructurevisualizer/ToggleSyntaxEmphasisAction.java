package com.example.codestructurevisualizer;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the {@link SyntaxEmphasisConfigDialog} where every syntax token type
 * can be individually toggled on/off and given a custom hex colour.
 *
 * <p>Default shortcut: {@code Ctrl+Alt+Shift+E}.</p>
 */
public final class ToggleSyntaxEmphasisAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        new SyntaxEmphasisConfigDialog(project).show();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}

