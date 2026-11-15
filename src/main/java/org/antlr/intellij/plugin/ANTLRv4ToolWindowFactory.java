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
import org.antlr.intellij.plugin.preview.PreviewService;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "ANTLR Preview" and "Tool Output" tool windows' UI (declared in plugin.xml):
 * the preview panel, with its input editors, and the console. Each is owned by its tool
 * window content and disposed with it. The models are project services: the panel subscribes
 * to {@link PreviewService}'s topic, the console attaches to {@link ToolOutput}.
 * <p>
 * Prior art: the platform's own tool windows (Run, Terminal) create their consoles and
 * editors in the factory and register them as the content's disposer. Light tests run no
 * factories, so they create no editors unless a test builds a panel itself.
 */
public final class ANTLRv4ToolWindowFactory implements ToolWindowFactory, DumbAware {
	public static final String PREVIEW_WINDOW_ID = "ANTLR Preview";

	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		Content content;
		if ( PREVIEW_WINDOW_ID.equals(toolWindow.getId()) ) {
			PreviewPanel panel = new PreviewPanel(project); // subscribes and shows the selected grammar
			content = ContentFactory.getInstance().createContent(panel, "", false);
			content.setDisposer(panel);
		}
		else {
			ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
			content = ContentFactory.getInstance().createContent(console.getComponent(), "", false);
			content.setDisposer(console);
			ToolOutput output = ToolOutput.getInstance(project);
			Disposer.register(console, () -> output.detach(console)); // children go first
			output.attach(console);
		}
		content.setCloseable(false);
		toolWindow.getContentManager().addContent(content);
	}
}
