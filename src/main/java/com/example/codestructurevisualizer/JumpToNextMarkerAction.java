package com.example.codestructurevisualizer;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Jumps to the next marker in the current file, wrapping around (Ctrl+Alt+Down). */
public final class JumpToNextMarkerAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (p == null || editor == null || file == null) return;
        p.getService(MarkerService.class).jumpToNextMarker(editor, file);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(
                e.getProject() != null
                && e.getData(CommonDataKeys.EDITOR) != null
                && e.getData(CommonDataKeys.VIRTUAL_FILE) != null);
    }
}

