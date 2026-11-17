package org.antlr.intellij.plugin;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.antlr.intellij.plugin.preview.PreviewService;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "ANTLR Preview" tool window's UI (declared in plugin.xml): the preview panel,
 * with its input editors, owned by the tool window content and disposed with it. The model is
 * a project service: the panel subscribes to {@link PreviewService}'s topic.
 * <p>
 * Prior art: the platform's own tool windows (Run, Terminal) create their editors in the
 * factory and register them as the content's disposer. Light tests run no factories, so they
 * create no editors unless a test builds a panel itself.
 */
public final class ANTLRv4ToolWindowFactory implements ToolWindowFactory, DumbAware {
	public static final String PREVIEW_WINDOW_ID = "ANTLR Preview";

	@Override
	public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
		PreviewPanel panel = new PreviewPanel(project); // subscribes and shows the selected grammar
		Content content = ContentFactory.getInstance().createContent(panel, "", false);
		content.setDisposer(panel);
		content.setCloseable(false);
		toolWindow.getContentManager().addContent(content);
	}
}
