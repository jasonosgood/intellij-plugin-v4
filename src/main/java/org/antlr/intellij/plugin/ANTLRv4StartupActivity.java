package org.antlr.intellij.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Installs the controller's listeners once the project has opened. {@link ProjectActivity} runs off the EDT, so the work is posted to the EDT.
 * (Implemented from Java: a suspend function takes a trailing {@link Continuation} and
 * returns its result directly when it doesn't suspend.)
 */
public final class ANTLRv4StartupActivity implements ProjectActivity {
	@Override
	public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
		ApplicationManager.getApplication().invokeLater(() -> {
			ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(project);
			if ( controller!=null ) controller.projectOpened();
		}, project.getDisposed());
		return Unit.INSTANCE;
	}
}
