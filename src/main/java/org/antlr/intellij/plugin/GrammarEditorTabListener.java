package org.antlr.intellij.plugin;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Forwards this project's editor tab events to its {@link ANTLRv4PluginController}.
 * Declared under {@code <projectListeners>} in plugin.xml, so the platform subscribes one
 * per project before any editor opens and unsubscribes it when the project closes.
 */
public final class GrammarEditorTabListener implements FileEditorManagerListener {
	private final Project project;

	public GrammarEditorTabListener(Project project) {
		this.project = project;
	}

	@Override
	public void selectionChanged(@NotNull FileEditorManagerEvent event) {
		ANTLRv4PluginController controller = controller();
		if ( controller!=null ) controller.editorSelectionChanged(event);
	}

	@Override
	public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
		ANTLRv4PluginController controller = controller();
		if ( controller!=null ) controller.editorFileClosed(source, file);
	}

	private ANTLRv4PluginController controller() {
		return project.isDisposed() ? null : ANTLRv4PluginController.getInstance(project);
	}
}
