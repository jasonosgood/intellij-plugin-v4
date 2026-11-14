package org.antlr.intellij.plugin;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.actions.AnnotationIntentActionsFactory;
import org.antlr.intellij.plugin.validation.GrammarIssue;
import org.antlr.intellij.plugin.validation.GrammarIssuesCollector;
import org.antlr.runtime.ANTLRFileStream;
import org.antlr.runtime.CharStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.antlr.v4.tool.ErrorSeverity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class ANTLRv4ExternalAnnotator extends ExternalAnnotator<GrammarIssuesCollector.Input, List<GrammarIssue>> {

	/** Called first, under a read lock: snapshot what the analysis needs from PSI */
	@Override
	@Nullable
	public GrammarIssuesCollector.Input collectInformation(@NotNull PsiFile file) {
		return GrammarIssuesCollector.snapshot(file);
	}

	/** Called 2nd, in the background and deliberately without a read lock: run antlr on the
	 *  snapshot. Holding a read lock here would block typing for as long as ANTLR's analysis runs.
	 */
	@Nullable
	@Override
	public List<GrammarIssue> doAnnotate(GrammarIssuesCollector.Input input) {
		return GrammarIssuesCollector.collectGrammarIssues(input);
	}

    /** Called 3rd */
	@Override
	public void apply(@NotNull PsiFile file,
					  List<GrammarIssue> issues,
					  @NotNull AnnotationHolder holder)
	{
		for ( GrammarIssue issue : issues ) {
			if ( issue.getOffendingTokens().isEmpty() ) {
				annotateFileIssue(file, holder, issue);
			}
			else {
				annotateIssue(file, holder, issue);
			}
		}

		final ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(file.getProject());
		if ( controller!=null && !ApplicationManager.getApplication().isUnitTestMode() ) {
			controller.getPreviewPanel().autoRefreshPreview(file.getVirtualFile());
		}
	}

	private void annotateFileIssue(@NotNull PsiFile file, @NotNull AnnotationHolder holder, GrammarIssue issue) {
		holder.newAnnotation(HighlightSeverity.WARNING, issue.getAnnotation())
			.fileLevel()
			.create();
	}

	private void annotateIssue(@NotNull PsiFile file, @NotNull AnnotationHolder holder, GrammarIssue issue) {
		for ( Token t : issue.getOffendingTokens() ) {
			if ( t instanceof CommonToken && tokenBelongsToFile(t, file) ) {
				TextRange range = getTokenRange((CommonToken) t, file);
				ErrorSeverity severity = getIssueSeverity(issue);
				annotate(holder, issue, range, severity, file);
			}
		}
	}

	private ErrorSeverity getIssueSeverity(GrammarIssue issue) {
		if ( issue.getMsg().getErrorType()!=null ) {
			return issue.getMsg().getErrorType().severity;
		}

		return ErrorSeverity.INFO;
	}

	@NotNull
	private TextRange getTokenRange(CommonToken ct, @NotNull PsiFile file) {
		int startIndex = ct.getStartIndex();
		int stopIndex = ct.getStopIndex();

		if ( startIndex >= file.getTextLength() ) {
			// can happen in case of a 'mismatched input EOF' error
			startIndex = stopIndex = file.getTextLength() - 1;
		}

		if ( startIndex<0 ) {
			// can happen on empty files, in that case we won't be able to show any error :/
			startIndex = 0;
		}

		return new TextRange(startIndex, stopIndex + 1);
	}

	private boolean tokenBelongsToFile(Token t, @NotNull PsiFile file) {
		CharStream inputStream = t.getInputStream();
		if ( inputStream instanceof ANTLRFileStream ) {
			// Not equal if the token belongs to an imported grammar
			return inputStream.getSourceName().equals(file.getVirtualFile().getCanonicalPath());
		}

		return true;
	}

	private void annotate(@NotNull AnnotationHolder holder, GrammarIssue issue, TextRange range,
						  ErrorSeverity severity, PsiFile file) {
		HighlightSeverity highlightSeverity = toHighlightSeverity(severity);
		if ( highlightSeverity==null ) {
			return;
		}
		AnnotationBuilder builder = holder.newAnnotation(highlightSeverity, issue.getAnnotation()).range(range);
		Optional<IntentionAction> fix = getFixForIssue(range, issue, file);
		if ( fix.isPresent() ) {
			builder = builder.withFix(fix.get());
		}
		builder.create();
	}

	@Nullable
	private static HighlightSeverity toHighlightSeverity(ErrorSeverity severity) {
		switch ( severity ) {
		case ERROR:
		case ERROR_ONE_OFF:
		case FATAL:
			return HighlightSeverity.ERROR;

		case WARNING:
			return HighlightSeverity.WARNING;

		case WARNING_ONE_OFF:
		case INFO:
			return HighlightSeverity.WEAK_WARNING;

		default:
			return null;
		}
	}

	static Optional<IntentionAction> getFixForIssue(TextRange range, GrammarIssue issue, PsiFile file) {
		return AnnotationIntentActionsFactory.getFix(range, issue.getMsg().getErrorType(), file);
	}

}
