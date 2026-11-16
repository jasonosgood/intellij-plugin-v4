package org.antlr.intellij.plugin;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.preview.PreviewService;
import org.jetbrains.annotations.NotNull;

/**
 * Turns this project's editor tab events into preview events ({@link PreviewService}).
 * Declared under {@code <projectListeners>} in plugin.xml, so the platform subscribes one
 * per project before any editor opens and unsubscribes it when the project closes.
 * <p>
 * Switching tabs never saves: the platform decides when files are saved (⌘S, Autosave
 * settings), and the preview follows saves. Prior art: no platform editor saves on a tab
 * switch.
 */
public final class GrammarEditorTabListener implements FileEditorManagerListener {
	private final Project project;

	public GrammarEditorTabListener(Project project) {
		this.project = project;
	}

	@Override
	public void selectionChanged(@NotNull FileEditorManagerEvent event) {
		if ( project.isDisposed() ) return;
		VirtualFile newFile = event.getNewFile();
		if ( newFile==null ) return; // all files closed
		String ext = newFile.getExtension();
		if ( "g".equals(ext) ) { // ANTLR 3: the preview can't show it
			PreviewService.getInstance(project).grammarDeselected();
		}
		else if ( "g4".equals(ext) ) {
			PreviewService.getInstance(project).grammarSelected(newFile);
		}
	}

	@Override
	public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
		if ( project.isDisposed() ) return;
		FileEditor selected = source.getSelectedEditor();
		if ( selected==null || !file.equals(selected.getFile()) ) return; // only the selected file's tab
		if ( "g4".equals(file.getExtension()) ) {
			PreviewService.getInstance(project).grammarClosed(file);
		}
		else {
			PreviewService.getInstance(project).grammarDeselected();
		}
	}
}
