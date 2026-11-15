package org.antlr.intellij.plugin.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.antlr.intellij.plugin.format.TextDiffs;
import org.antlr.intellij.plugin.preview.PreviewState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xl.GrammarSyntaxException;
import xl.SortRules;

import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/**
 * Sorts parser rules alphabetically with antlr4-xl, keeping the start rule first: the one chosen
 * in the ANTLR Preview, else the first parser rule. Lexer rules
 * move after the parser rules, in their original order (their order sets token priority).
 * Comments and blank lines move with their rules.
 * <p>
 * The grammar is parsed and sorted in a non-blocking read action, off the EDT; the result is
 * applied as a line diff ({@link TextDiffs#apply}) so the caret, folding and scroll position
 * outside moved rules survive.
 */
public class SortRulesAction extends AnAction {
	private static final Logger LOG = Logger.getInstance(SortRulesAction.class);

	@Override
	public void update(@NotNull AnActionEvent e) {
		e.getPresentation().setEnabledAndVisible(
			e.getData(CommonDataKeys.PSI_FILE) instanceof ANTLRv4FileRoot && e.getData(CommonDataKeys.EDITOR)!=null);
	}

	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		Project project = e.getProject();
		Editor editor = e.getData(CommonDataKeys.EDITOR);
		PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
		if ( project==null || editor==null || file==null ) return;

		Document doc = editor.getDocument();
		// Keep the preview's start rule first, if one is chosen; else the first parser rule
		PreviewState previewState = file.getVirtualFile()==null ? null
			: ANTLRv4PluginController.getInstance(project).getPreviewState(file.getVirtualFile());
		String startRule = previewState==null ? null : previewState.startRuleName;
		ReadAction.nonBlocking(() -> sort(doc.getText(), startRule))
			.expireWhen(() -> project.isDisposed() || editor.isDisposed())
			.finishOnUiThread(ModalityState.current(), result -> {
				if ( result.error!=null ) {
					NotificationGroupManager.getInstance().getNotificationGroup(GrammarFormattingService.NOTIFICATION_GROUP)
						.createNotification("Cannot sort rules", result.error, NotificationType.WARNING)
						.notify(project);
					return;
				}
				// no write action can come between the read action and this callback, so the
				// document still holds the text that was sorted
				if ( result.sorted.equals(doc.getText()) ) return;
				runWriteCommandAction(project, "Sort Rules", null, () -> TextDiffs.apply(doc, result.sorted), file);
			})
			.submit(AppExecutorUtil.getAppExecutorService());
	}

	private record Result(String sorted, String error) {
	}

	private static Result sort(String text, @Nullable String startRule) {
		try {
			try {
				return new Result(SortRules.sort(text, startRule), null);
			}
			catch (IllegalArgumentException ex) { // start rule renamed or removed since chosen
				return new Result(SortRules.sort(text, null), null);
			}
		}
		catch (GrammarSyntaxException ex) {
			return new Result(null, "The grammar has syntax errors (" + ex.count + ").");
		}
		catch (ProcessCanceledException ex) {
			throw ex;
		}
		catch (RuntimeException ex) { // a bug in xl.SortRules
			LOG.warn("antlr4-xl SortRules failed", ex);
			return new Result(null, "Internal error: " + ex);
		}
	}
}
