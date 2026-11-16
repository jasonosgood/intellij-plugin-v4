package org.antlr.intellij.plugin.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.antlr.intellij.plugin.generation.AntlrGenerator;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collections;

/** Generate parser from ANTLR grammar, for one-offs: builds generate on their own (doc/DECISIONS.md,
 *  "Saving and code generation"). Reports in one notification.
 *  Learned how to do from Grammar-Kit by Gregory Shrago.
 */
public class GenerateParserAction extends AnAction implements DumbAware {
	public static final Logger LOG = Logger.getInstance("ANTLR GenerateAction");

	@Override
	public void update(AnActionEvent e) {
		MyActionUtils.selectedFileIsGrammar(e);
	}

	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	@Override
	public void actionPerformed(final AnActionEvent e) {
		Project project = e.getData(PlatformDataKeys.PROJECT);
		if ( project==null ) {
			LOG.error("actionPerformed no project for "+e);
			return; // whoa!
		}
		VirtualFile grammarFile = MyActionUtils.getGrammarFileFromEvent(e);
		LOG.debug("actionPerformed "+(grammarFile==null ? "NONE" : grammarFile));
		if ( grammarFile==null ) return;

		String buildTool = ReadAction.computeBlocking(() -> {
			Module module = ModuleUtilCore.findModuleForFile(grammarFile, project);
			return module==null ? null : ExternalBuildOf.module(module);
		});
		if ( buildTool!=null ) {
			stepAside(project, grammarFile, buildTool);
			return;
		}
		generate(project, grammarFile);
	}

	/** The module's own build generates: say so instead of writing a second copy. Prior art: the
	 *  JPS builder steps aside for the same modules. */
	private static void stepAside(Project project, VirtualFile grammarFile, String buildTool) {
		Notification notification = new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
			grammarFile.getName()+" not generated",
			"Its module is built by "+buildTool+", whose build generates recognizers. "+
			"Generating here too would mean duplicate classes.",
			NotificationType.INFORMATION);
		notification.addAction(NotificationAction.createSimpleExpiring("Generate anyway",
			() -> generate(project, grammarFile)));
		Notifications.Bus.notify(notification, project);
	}

	private static void generate(Project project, VirtualFile grammarFile) {
		MyActionUtils.saveAllBeforeRunning(project);

		RunANTLROnGrammarFile gen = new RunANTLROnGrammarFile(grammarFile, project, "ANTLR Code Generation", true);
		ProgressManager.getInstance().run(gen); // modal: it has finished here
		AntlrGenerator.Result result = gen.getResult();
		if ( result==null ) return;

		String outputDir = gen.getOutputDirName();
		LocalFileSystem.getInstance().refreshIoFiles(Collections.singleton(new File(outputDir)), true, true, null);

		GenerationReport report = GenerationReport.of(grammarFile.getName(), outputDir, result.messages, result.failure);
		Notification notification = new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
			report.title, report.content, notificationType(report.severity));
		ToolMessage firstError = report.firstError;
		if ( firstError!=null ) {
			notification.addAction(NotificationAction.createSimple("Go to error", () -> {
				VirtualFile file = LocalFileSystem.getInstance().findFileByPath(firstError.file);
				if ( file==null ) return;
				new OpenFileDescriptor(project, file, firstError.line-1, Math.max(firstError.column, 0))
					.navigate(true);
			}));
		}
		Notifications.Bus.notify(notification, project);
	}

	private static NotificationType notificationType(GenerationReport.Severity severity) {
		return switch ( severity ) {
			case INFO -> NotificationType.INFORMATION;
			case WARNING -> NotificationType.WARNING;
			case ERROR -> NotificationType.ERROR;
		};
	}
}
