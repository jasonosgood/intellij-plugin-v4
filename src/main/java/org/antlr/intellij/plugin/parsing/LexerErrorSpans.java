package org.antlr.intellij.plugin.parsing;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Interval;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Records the input each lexer error consumed, so the preview can tell text the lexer
 *  couldn't match from text a rule skipped (both leave a gap between tokens). On a token
 *  recognition error the lexer reports the text from the token's start through the character
 *  it failed on, then consumes that character ({@code Lexer.nextToken}, {@code recover}), so
 *  the reported span is exactly what the error consumed.
 */
public class LexerErrorSpans extends BaseErrorListener {
	private final List<Interval> spans = new ArrayList<>();

	@Override
	public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
							String msg, RecognitionException e) {
		if ( !(recognizer instanceof Lexer lexer) ) return;
		int start = lexer._tokenStartCharIndex;
		int stop = Math.min(lexer.getInputStream().index(), lexer.getInputStream().size() - 1); // not past EOF
		if ( stop>=start ) spans.add(Interval.of(start, stop));
	}

	public @NotNull List<Interval> getSpans() {
		return List.copyOf(spans);
	}

	/** The spans recorded by the listener on {@code lexer}; none if it has none. */
	static @NotNull List<Interval> of(@NotNull Lexer lexer) {
		for (Object listener : lexer.getErrorListeners()) {
			if ( listener instanceof LexerErrorSpans spans ) return spans.getSpans();
		}
		return List.of();
	}
}
