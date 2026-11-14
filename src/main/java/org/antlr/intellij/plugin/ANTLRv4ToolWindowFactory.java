package org.antlr.intellij.plugin;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Fills the "ANTLR Preview" and "Tool Output" tool windows (declared in plugin.xml) with the
 * controller's preview panel and console. The controller owns both and disposes them, so the
 * content has no disposer of its own.
 */
public final class ANTLRv4ToolWindowFactory implements ToolWindowFactory, DumbAware {
	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(project);
		if ( controller==null ) return;
		JComponent component;
		if ( ANTLRv4PluginController.PREVIEW_WINDOW_ID.equals(toolWindow.getId()) ) {
			component = controller.getPreviewPanel(); // creates the UI parts if not yet done
		}
		else {
			controller.getPreviewPanel(); // ensure the console exists too
			ConsoleView console = controller.getConsole();
			if ( console==null ) return;
			component = console.getComponent();
		}
		Content content = ContentFactory.getInstance().createContent(component, "", false);
		content.setCloseable(false);
		toolWindow.getContentManager().addContent(content);
	}
}
