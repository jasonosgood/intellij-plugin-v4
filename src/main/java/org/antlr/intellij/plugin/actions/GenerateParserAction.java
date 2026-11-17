package org.antlr.intellij.plugin.actions;

import com.intellij.build.BuildViewManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.antlr.intellij.plugin.generation.AntlrGenerator;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** Generate parser from ANTLR grammar, for one-offs: builds generate on their own (doc/DECISIONS.md,
 *  "Saving and code generation"). Reports in the Build tool window, with a balloon only when the
 *  run fails and the status bar otherwise, as actions running a Gradle or Maven task do.
 *  Learned how to do from Grammar-Kit by Gregory Shrago.
 */
public class GenerateParserAction extends AnAction implements DumbAware {
	public static final Logger LOG = Logger.getInstance("ANTLR GenerateAction");
	/** Per project: "Don't warn again" on the step-aside notification; Generate then generates. */
	static final String DONT_WARN_BUILD_TOOL = "antlr.generate.dontWarnBuildTool";

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

		if ( !resolveStrayTokensFile(project, grammarFile) ) return;

		String buildTool = ReadAction.computeBlocking(() -> {
			Module module = ModuleUtilCore.findModuleForFile(grammarFile, project);
			return module==null ? null : ExternalBuildOf.module(module);
		});
		if ( buildTool!=null && warnsAboutBuildTool(project) ) {
			stepAside(project, grammarFile, buildTool);
			return;
		}
		generate(project, grammarFile);
	}

	static boolean warnsAboutBuildTool(Project project) {
		return !PropertiesComponent.getInstance(project).getBoolean(DONT_WARN_BUILD_TOOL);
	}

	/**
	 * Asks what to do with a stray {@code .tokens} file the grammar's {@code tokenVocab} would be
	 * read from instead of the generated one (doc/DECISIONS.md). Asked first: it matters to any
	 * generation, here or in the build. The build can't ask, so it warns.
	 * @return false to stop: the user cancelled, or the file couldn't be deleted
	 */
	private static boolean resolveStrayTokensFile(Project project, VirtualFile grammarFile) {
		File stray = RunANTLROnGrammarFile.strayTokensFile(project, grammarFile);
		if ( stray==null ) return true;
		int answer = MessageDialogBuilder.yesNoCancel("Stray Tokens File",
				"<html>"+StringUtil.escapeXmlEntities(stray.getPath())+" is next to its lexer grammar, "+
				"left there by an earlier run. ANTLR reads it instead of the one it generates, so "+
				grammarFile.getName()+" would get its token types, which may be out of date.</html>")
			.yesText("Delete Stray File")
			.noText("Continue Anyway")
			.cancelText("Cancel")
			.asWarning()
			.show(project);
		if ( answer==Messages.CANCEL ) return false;
		if ( answer==Messages.NO ) return true;
		VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(stray);
		if ( file==null ) return true; // gone already
		try {
			WriteAction.run(() -> file.delete(GenerateParserAction.class));
			return true;
		}
		catch (IOException ex) {
			Notifications.Bus.notify(new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
				"Couldn't delete "+stray.getName(), StringUtil.escapeXmlEntities(ex.toString()),
				NotificationType.ERROR), project);
			return false;
		}
	}

	/** The module's own build generates: say so instead of writing a second copy. Prior art: the
	 *  JPS builder steps aside for the same modules. "Don't warn again" remembers "Generate anyway"
	 *  for the project, as a dialog's "Don't ask again" remembers its answer; stepping aside
	 *  silently would make Generate a no-op that says nothing. Stored like OutputRootOffer's
	 *  "Don't offer again". */
	private static void stepAside(Project project, VirtualFile grammarFile, String buildTool) {
		Notification notification = new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
			grammarFile.getName()+" not generated",
			"Its module is built by "+buildTool+", whose build generates recognizers. "+
			"Generating here too would mean duplicate classes.",
			NotificationType.INFORMATION);
		notification.addAction(NotificationAction.createSimpleExpiring("Generate anyway",
			() -> generate(project, grammarFile)));
		notification.addAction(NotificationAction.createSimpleExpiring("Don't warn again", () -> {
			PropertiesComponent.getInstance(project).setValue(DONT_WARN_BUILD_TOOL, true);
			generate(project, grammarFile);
		}));
		Notifications.Bus.notify(notification, project);
	}

	private static void generate(Project project, VirtualFile grammarFile) {
		MyActionUtils.saveAllBeforeRunning(project);

		long startTime = System.currentTimeMillis();
		RunANTLROnGrammarFile gen = new RunANTLROnGrammarFile(grammarFile, project, "ANTLR Code Generation", true);
		ProgressManager.getInstance().run(gen); // modal: it has finished here
		AntlrGenerator.Result result = gen.getResult();
		if ( result==null ) return;

		String outputDir = gen.getOutputDirName();
		LocalFileSystem.getInstance().refreshIoFiles(Collections.singleton(new File(outputDir)), true, true, null);

		GenerationReport report = GenerationReport.of(grammarFile.getName(), outputDir, result.messages, result.failure);
		GenerationBuildOutput.report(project.getService(BuildViewManager.class),
			StringUtil.notNullize(project.getBasePath()), grammarFile.getName(), outputDir, startTime,
			result.messages, result.failure, report);
		if ( report.severity!=GenerationReport.Severity.ERROR ) {
			StatusBar.Info.set(report.title, project);
			return;
		}
		Notification notification = new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
			report.title, report.content, NotificationType.ERROR);
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
}
