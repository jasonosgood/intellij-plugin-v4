package org.antlr.intellij.plugin.format;

import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.comparison.DiffTooBigException;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.DumbProgressIndicator;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Applies a tool's rewrite of a whole grammar (sorted, formatted) as line-level edits.
 * <p>
 * Prior art: the platform applies an {@code AsyncDocumentFormattingService} result as a line
 * diff instead of replacing the document, so the caret, folding, bookmarks and scroll position
 * outside the changed lines survive; {@link #apply} does the same for actions. For formatting
 * a range, google-java-format formats the whole file and keeps only the replacements that
 * overlap the requested lines; {@link #keepChangesIn} does the same.
 */
public final class TextDiffs {
	private TextDiffs() {
	}

	/** Replaces the document's text by {@code newText} with one edit per changed block of lines. Call in a write action. */
	public static void apply(@NotNull Document doc, @NotNull String newText) {
		String oldText = doc.getText();
		List<LineFragment> fragments = lineDiff(oldText, newText);
		if ( fragments==null ) {
			doc.replaceString(0, doc.getTextLength(), newText);
			return;
		}
		for (int i = fragments.size() - 1; i>=0; i--) { // last first, so offsets stay valid
			LineFragment f = fragments.get(i);
			doc.replaceString(f.getStartOffset1(), f.getEndOffset1(),
			                  newText.substring(f.getStartOffset2(), f.getEndOffset2()));
		}
	}

	/**
	 * {@code oldText} with only those changes from {@code newText} whose lines overlap one of
	 * {@code ranges} (offsets in {@code oldText}); all of {@code newText} if the diff is too big.
	 */
	@NotNull
	public static String keepChangesIn(@NotNull String oldText, @NotNull String newText, @NotNull List<TextRange> ranges) {
		List<LineFragment> fragments = lineDiff(oldText, newText);
		if ( fragments==null ) return newText;
		StringBuilder result = new StringBuilder(oldText);
		for (int i = fragments.size() - 1; i>=0; i--) {
			LineFragment f = fragments.get(i);
			TextRange changed = new TextRange(f.getStartOffset1(), f.getEndOffset1());
			if ( ranges.stream().anyMatch(r -> r.intersects(changed)) ) {
				result.replace(f.getStartOffset1(), f.getEndOffset1(),
				               newText.substring(f.getStartOffset2(), f.getEndOffset2()));
			}
		}
		return result.toString();
	}

	@Nullable
	private static List<LineFragment> lineDiff(CharSequence oldText, CharSequence newText) {
		try {
			return ComparisonManager.getInstance().compareLines(oldText, newText, ComparisonPolicy.DEFAULT,
			                                                    DumbProgressIndicator.INSTANCE);
		}
		catch (DiffTooBigException e) {
			return null;
		}
	}
}
