package org.antlr.intellij.plugin.format;

import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.ANTLRv4Language;
import org.jetbrains.annotations.NotNull;
import xl.Formatter;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Formats .g4 files with the antlr4-xl formatter, so Code | Reformat Code, reformat on save and
 * reformatting a directory all use it.
 * <p>
 * Prior art: the google-java-format and ktfmt plugins wrap a text-to-text formatter library the
 * same way. The platform runs the task off the EDT and applies the result as a diff, which keeps
 * the caret, folding and undo history intact.
 */
public class GrammarFormattingService extends AsyncDocumentFormattingService {
	public static final String NOTIFICATION_GROUP = "ANTLR Grammar Tools";
	private static final Logger LOG = Logger.getInstance(GrammarFormattingService.class);

	@Override
	public boolean canFormat(@NotNull PsiFile file) {
		return file instanceof ANTLRv4FileRoot;
	}

	@Override
	public @NotNull Set<Feature> getFeatures() {
		// The formatter has no range mode: a range request formats the whole grammar and keeps
		// the changes on the requested lines (TextDiffs.keepChangesIn). Without this feature,
		// Reformat on a selection and reformat-on-save's "changed lines only" did nothing.
		return EnumSet.of(Feature.FORMAT_FRAGMENTS);
	}

	@Override
	protected @NotNull String getNotificationGroupId() {
		return NOTIFICATION_GROUP;
	}

	@Override
	protected @NotNull String getName() {
		return "ANTLR grammar formatter";
	}

	@Override
	protected FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
		int width = request.getContext().getCodeStyleSettings().getRightMargin(ANTLRv4Language.INSTANCE);
		String text = request.getDocumentText();
		return new FormattingTask() {
			@Override
			public void run() {
				try {
					String formatted = Formatter.format(text, width);
					List<TextRange> ranges = request.getFormattingRanges();
					boolean wholeFile = ranges.size()==1 && ranges.get(0).getStartOffset()==0
						&& ranges.get(0).getEndOffset()>=text.length();
					request.onTextReady(wholeFile ? formatted : TextDiffs.keepChangesIn(text, formatted, ranges));
				}
				catch (ProcessCanceledException e) {
					throw e;
				}
				catch (IllegalStateException e) { // syntax errors
					request.onError("Cannot format grammar", e.getMessage());
				}
				catch (RuntimeException e) { // formatter bug: report it rather than leave the request hanging
					LOG.warn("antlr4-xl formatter failed", e);
					request.onError("Cannot format grammar", "Internal formatter error: "+e);
				}
			}

			@Override
			public boolean cancel() {
				return false; // runs in well under a second
			}

			@Override
			public boolean isRunUnderProgress() {
				return false;
			}
		};
	}
}
