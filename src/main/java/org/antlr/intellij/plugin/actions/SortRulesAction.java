package org.antlr.intellij.plugin.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.jetbrains.annotations.NotNull;
import xl.SortRules;

import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/**
 * Sorts parser rules alphabetically with antlr4-xl, keeping the start rule first and lexer
 * rules in place (their order sets token priority). Comments and blank lines move with
 * their rules.
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
		String sorted;
		try {
			sorted = SortRules.sort(doc.getText(), null);
		}
		catch (IllegalStateException ex) { // syntax errors
			NotificationGroupManager.getInstance().getNotificationGroup(GrammarFormattingService.NOTIFICATION_GROUP)
				.createNotification("Cannot sort rules", ex.getMessage(), NotificationType.WARNING)
				.notify(project);
			return;
		}
		if ( sorted.equals(doc.getText()) ) return;

		runWriteCommandAction(project, "Sort Rules", null,
			() -> doc.replaceString(0, doc.getTextLength(), sorted), file);
	}
}
