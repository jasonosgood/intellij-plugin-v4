package org.antlr.intellij.plugin.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.antlr.intellij.plugin.format.TextDiffs;
import org.jetbrains.annotations.NotNull;
import xl.SortRules;

import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/**
 * Sorts parser rules alphabetically with antlr4-xl, keeping the start rule first. Lexer rules
 * move after the parser rules, in their original order (their order sets token priority).
 * Comments and blank lines move with their rules.
 * <p>
 * The grammar is parsed and sorted in a non-blocking read action, off the EDT; the result is
 * applied as a line diff ({@link TextDiffs#apply}) so the caret, folding and scroll position
 * outside moved rules survive.
 */
public class SortRulesAction extends AnAction {
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
		ReadAction.nonBlocking(() -> sort(doc.getText()))
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

	private static Result sort(String text) {
		try {
			return new Result(SortRules.sort(text, null), null);
		}
		catch (IllegalStateException ex) { // syntax errors
			return new Result(null, ex.getMessage());
		}
	}
}
