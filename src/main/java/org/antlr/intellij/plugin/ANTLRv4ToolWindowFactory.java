package org.antlr.intellij.plugin;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "ANTLR Preview" and "Tool Output" tool windows' UI (declared in plugin.xml):
 * the preview panel, with its input editors, and the console. Each is owned by its tool
 * window content and disposed with it; the controller only holds the model and is told when
 * a view comes and goes.
 * <p>
 * Prior art: the platform's own tool windows (Run, Terminal) create their consoles and
 * editors in the factory and register them as the content's disposer. Light tests run no
 * factories, so they create no editors unless a test builds a panel itself.
 */
public final class ANTLRv4ToolWindowFactory implements ToolWindowFactory, DumbAware {
	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(project);
		if ( controller==null ) return;
		Content content;
		if ( ANTLRv4PluginController.PREVIEW_WINDOW_ID.equals(toolWindow.getId()) ) {
			PreviewPanel panel = new PreviewPanel(project);
			content = ContentFactory.getInstance().createContent(panel, "", false);
			content.setDisposer(panel);
			controller.attachPreviewPanel(panel);
		}
		else {
			ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
			content = ContentFactory.getInstance().createContent(console.getComponent(), "", false);
			content.setDisposer(console);
			ToolOutput output = controller.getConsole();
			Disposer.register(console, () -> output.detach(console)); // children go first
			output.attach(console);
		}
		content.setCloseable(false);
		toolWindow.getContentManager().addContent(content);
	}
}
