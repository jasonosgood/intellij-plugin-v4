package org.antlr.intellij.plugin.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.refactoring.IntroduceTargetChooser;
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.intellij.plugin.ANTLRv4TokenTypes;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.psi.LexerRuleRefNode;
import org.antlr.intellij.plugin.psi.ParserRuleRefNode;
import org.antlr.intellij.plugin.refactor.ExtractRulePlan;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/**
 * Extracts an expression to a new rule, optionally replacing every other
 * occurrence of the same expression in the grammar.
 */
public class ExtractRuleAction extends AnAction {

	/**
	 * Enables the action if the caret is in a lexer or parser rule.
	 */
	@Override
	public void update(@NotNull AnActionEvent e) {
		Presentation presentation = e.getPresentation();

		VirtualFile grammarFile = MyActionUtils.getGrammarFileFromEvent(e);
		if ( grammarFile==null ) {
			presentation.setEnabled(false);
			return;
		}

		Editor editor = e.getData(PlatformDataKeys.EDITOR);
		if ( editor==null ) {
			presentation.setEnabled(false);
			return;
		}

		ParserRuleRefNode parserRule = MyActionUtils.getParserRuleSurroundingRef(e);
		LexerRuleRefNode lexerRule = MyActionUtils.getLexerRuleSurroundingRef(e);
		if ( parserRule==null && lexerRule==null ) {
			presentation.setEnabled(false);
			return;
		}

		SelectionModel selectionModel = editor.getSelectionModel();
		if ( !selectionModel.hasSelection() ) {
			PsiElement el = MyActionUtils.getSelectedPsiElement(e);
			if ( el==null || findExtractableRules(el).isEmpty() ) {
				presentation.setEnabled(false);
				return;
			}
		}

		// The selection's shape (whole elements/alternatives of one rule) needs a parse of the
		// grammar, too slow for update(); extractSelection explains a rejected selection instead.
		presentation.setEnabled(true);
	}

	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		PsiElement el = MyActionUtils.getSelectedPsiElement(e);
		if ( el==null ) return;

		final PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
		if ( psiFile==null ) return;

		Editor editor = e.getData(PlatformDataKeys.EDITOR);
		if ( editor==null ) return;
		SelectionModel selectionModel = editor.getSelectionModel();

		if ( !selectionModel.hasSelection() ) {
			List<PsiElement> expressions = findExtractableRules(el);

			IntroduceTargetChooser.showChooser(editor, expressions, new Pass<PsiElement>() {
				@Override
				public void pass(PsiElement element) {
					selectionModel.setSelection(element.getTextOffset(), element.getTextRange().getEndOffset());
					extractSelection(psiFile, editor, selectionModel);
				}
			}, PsiElement::getText);
		}
		else {
			extractSelection(psiFile, editor, selectionModel);
		}
	}

	@NotNull
	private List<PsiElement> findExtractableRules(PsiElement context) {
		List<PsiElement> expressions = new ArrayList<>();

		Set<IElementType> candidateTypes = Stream.of(ANTLRv4Parser.RULE_element, ANTLRv4Parser.RULE_alternative)
				.map(ANTLRv4TokenTypes::getRuleElementType)
				.collect(Collectors.toSet());

		@Nullable PsiElement parent = context;
		while ( parent!=null ) {
			if ( parent.getNode()!=null && candidateTypes.contains(parent.getNode().getElementType()) ) {
				expressions.add(parent);
			}

			parent = parent.getParent();
		}
		return expressions;
	}

	/**
	 * Plans the extraction in a non-blocking read action (parsing a large grammar can take a
	 * while, see commit 50f99b2), then asks for the occurrences and name on the EDT.
	 * Prior art: IntelliJ's Extract Method rejects a bad selection with a "Cannot perform
	 * refactoring" message naming the reason, rather than disabling the action.
	 */
	private void extractSelection(@NotNull PsiFile psiFile, Editor editor, SelectionModel selectionModel) {
		Project project = psiFile.getProject();
		Document doc = editor.getDocument();
		int selStart = selectionModel.getSelectionStart();
		int selEnd = selectionModel.getSelectionEnd();
		ReadAction.nonBlocking(() -> planOrReason(doc.getText(), selStart, selEnd))
			.expireWhen(() -> project.isDisposed() || editor.isDisposed())
			.finishOnUiThread(ModalityState.current(), result -> {
				if ( result instanceof String reason ) {
					Messages.showErrorDialog(project, reason, "Cannot Extract Rule");
				}
				else {
					// no write action can come between the read action and this callback
					chooseOccurrences(psiFile, editor, (ExtractRulePlan) result, doc.getModificationStamp());
				}
			})
			.submit(AppExecutorUtil.getAppExecutorService());
	}

	/** The plan, or the reason it can't be made. */
	private static Object planOrReason(String text, int selStart, int selEnd) {
		try {
			return ExtractRulePlan.plan(text, selStart, selEnd);
		}
		catch (ExtractRulePlan.Rejected r) {
			return r.getMessage();
		}
	}

	private void chooseOccurrences(PsiFile psiFile, Editor editor, ExtractRulePlan plan, long planStamp) {
		editor.getSelectionModel().setSelection(plan.selection.getStartOffset(), plan.selection.getEndOffset());
		if ( plan.occurrences.size()<=1 ) {
			askNameAndExtract(psiFile, editor.getDocument(), plan, planStamp, false);
			return;
		}

		// Same popup as Introduce Variable: "Replace this occurrence only" / "Replace all N occurrences"
		Map<OccurrencesChooser.ReplaceChoice, List<TextRange>> choices = new LinkedHashMap<>();
		choices.put(OccurrencesChooser.ReplaceChoice.NO, Collections.singletonList(plan.selection));
		choices.put(OccurrencesChooser.ReplaceChoice.ALL, plan.occurrences);
		new OccurrencesChooser<TextRange>(editor) {
			@Override
			protected TextRange getOccurrenceRange(TextRange occurrence) {
				return occurrence;
			}
		}.showChooser(choices,
			choice -> askNameAndExtract(psiFile, editor.getDocument(), plan, planStamp, choice.isAll()));
	}

	private void askNameAndExtract(PsiFile psiFile, Document doc, ExtractRulePlan plan, long planStamp,
	                               boolean replaceAll) {
		Project project = psiFile.getProject();
		ChooseExtractedRuleName nameChooser =
			new ChooseExtractedRuleName(project, plan.suggestedName, plan.lexerRule, plan.existingNames);
		nameChooser.show();
		if ( nameChooser.ruleName==null ) return;
		if ( doc.getModificationStamp()!=planStamp ) { // edited while the popup or dialog was open
			Messages.showErrorDialog(project, "The grammar changed while extracting; please try again.",
			                         "Cannot Extract Rule");
			return;
		}

		List<ExtractRulePlan.Edit> edits = plan.edits(nameChooser.ruleName, replaceAll);
		runWriteCommandAction(project, "Extract Rule", null, () -> {
			for (ExtractRulePlan.Edit edit : edits) { // last offset first
				doc.replaceString(edit.range.getStartOffset(), edit.range.getEndOffset(), edit.text);
			}
		}, psiFile);
	}

}
