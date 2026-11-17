package org.antlr.intellij.plugin.preview;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Interval;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Tokens as the rows of the preview's Tokens tab, with the input between them. Pure logic,
 *  no IDE classes.
 *  <p>
 *  Prior art: {@code grun -tokens} prints {@link org.antlr.v4.runtime.CommonToken#toString()},
 *  {@code [@index,start:stop='text',<type>,channel=n,line:col]}; ANTLR Lab lists the same
 *  fields in a table. The text is escaped the same way ({@code \n}, {@code \r}, {@code \t}).
 *  Differs: the type is shown by name, and the channel by name, blank for the default channel.
 *  <p>
 *  Also differs: input between tokens gets rows too, since the tab is for seeing what the
 *  lexer did, and {@code -> skip} leaves no token behind. Such a gap is either skipped by a
 *  rule or consumed by a lexer error; error spans tell them apart. Which rule skipped is not
 *  known here (doc/DECISIONS.md, "Skipped text in the Tokens tab"). Prior art: the PSI
 *  Viewer's "show PsiWhiteSpace" option.
 */
public final class TokenRows {
	private TokenRows() {}

	public enum Kind {
		/** A token in the stream. */
		TOKEN,
		/** Input between tokens that a rule skipped. */
		SKIPPED,
		/** Input a lexer error consumed: no rule matched it. */
		ERROR
	}

	/** One token, or one stretch of input between tokens.
	 *  @param index the token's index in the stream; -1 for input between tokens
	 *  @param hidden whether the token is off the default channel (the parser doesn't see it)
	 *  @param startIndex the first character in the input; for EOF, the input's length
	 *  @param stopIndex the last character in the input, inclusive
	 */
	public record Row(@NotNull Kind kind, int index, @NotNull String type, @NotNull String text, int line, int column,
					  @NotNull String channel, boolean hidden, int startIndex, int stopIndex)
	{
		public @NotNull String position() {
			return line + ":" + column;
		}
	}

	static final String SKIPPED_TYPE = "(skipped)";
	static final String ERROR_TYPE = "(error)";

	/** Rows for {@code tokens} in order, with rows for the input between them.
	 *  @param errorSpans the input lexer errors consumed
	 */
	public static @NotNull List<Row> rows(@NotNull List<? extends Token> tokens,
										  @NotNull Vocabulary vocabulary,
										  @NotNull List<String> channelNames,
										  @NotNull List<Interval> errorSpans) {
		List<Row> rows = new ArrayList<>(tokens.size());
		Cursor cursor = new Cursor(); // the input just after the last token
		for (Token t : tokens) {
			CharStream input = t.getInputStream();
			int start = t.getStartIndex();
			if ( input!=null && start>cursor.index ) {
				addGapRows(rows, input.getText(Interval.of(cursor.index, start - 1)), cursor, errorSpans);
			}
			int channel = t.getChannel();
			rows.add(new Row(Kind.TOKEN, t.getTokenIndex(), typeName(t.getType(), vocabulary), escape(t.getText()),
							 t.getLine(), t.getCharPositionInLine(),
							 channel==Token.DEFAULT_CHANNEL ? "" : channelName(channel, channelNames),
							 channel!=Token.DEFAULT_CHANNEL, start, t.getStopIndex()));
			if ( input!=null && start>=0 && t.getStopIndex()>=start ) {
				cursor.moveTo(t.getLine(), t.getCharPositionInLine(), start);
				cursor.advance(input.getText(Interval.of(start, t.getStopIndex())));
			}
		}
		return rows;
	}

	/** Rows for the input {@code gap} at {@code cursor}: runs of skipped text, and runs a lexer error consumed. */
	private static void addGapRows(List<Row> rows, String gap, Cursor cursor, List<Interval> errorSpans) {
		int[] chars = gap.codePoints().toArray(); // input indexes count code points
		int i = 0;
		while ( i<chars.length ) {
			boolean error = inErrorSpan(cursor.index, errorSpans);
			int line = cursor.line, column = cursor.column, start = cursor.index;
			StringBuilder text = new StringBuilder();
			while ( i<chars.length && inErrorSpan(cursor.index, errorSpans)==error ) {
				text.appendCodePoint(chars[i]);
				cursor.advance(chars[i]);
				i++;
			}
			rows.add(new Row(error ? Kind.ERROR : Kind.SKIPPED, -1, error ? ERROR_TYPE : SKIPPED_TYPE,
							 escape(text.toString()), line, column, "", false, start, cursor.index - 1));
		}
	}

	private static boolean inErrorSpan(int index, List<Interval> errorSpans) {
		for (Interval span : errorSpans) {
			if ( span.a<=index && index<=span.b ) return true;
		}
		return false;
	}

	/** A position in the input: code point index, line (from 1) and column (from 0), counted as the lexer does. */
	private static final class Cursor {
		int index = 0, line = 1, column = 0;

		void moveTo(int line, int column, int index) {
			this.line = line;
			this.column = column;
			this.index = index;
		}

		void advance(String text) {
			text.codePoints().forEach(this::advance);
		}

		void advance(int c) {
			index++;
			if ( c=='\n' ) {
				line++;
				column = 0;
			}
			else {
				column++;
			}
		}
	}

	/** The symbolic name ({@code ID}), else the display name ({@code '+'}, or the number). */
	static @NotNull String typeName(int type, @NotNull Vocabulary vocabulary) {
		if ( type==Token.EOF ) return "EOF";
		String symbolic = vocabulary.getSymbolicName(type);
		return symbolic!=null ? symbolic : vocabulary.getDisplayName(type);
	}

	static @NotNull String channelName(int channel, @NotNull List<String> channelNames) {
		if ( channel>=0 && channel<channelNames.size() && channelNames.get(channel)!=null ) {
			return channelNames.get(channel);
		}
		return channel==Token.HIDDEN_CHANNEL ? "HIDDEN" : String.valueOf(channel);
	}

	/** {@code text} with newlines and tabs escaped, as {@code CommonToken.toString} does. */
	static @NotNull String escape(String text) {
		if ( text==null ) return "";
		return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
