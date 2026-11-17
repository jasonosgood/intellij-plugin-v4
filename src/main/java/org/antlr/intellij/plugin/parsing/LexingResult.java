package org.antlr.intellij.plugin.parsing;

import org.antlr.intellij.adaptor.parser.SyntaxErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The whole token stream a lexer made from the preview's input, every channel and EOF
 *  included, with the names to show it by (the preview's Tokens tab).
 *  <p>
 *  Prior art: {@code grun -tokens} (TestRig) fills the stream and prints every token.
 *
 *  @param tokens the tokens in order, hidden channels and EOF included
 *  @param vocabulary the lexer's token type names
 *  @param channelNames the lexer's channel names by channel number; may be shorter than the
 *                      highest channel used
 *  @param errorSpans the input each lexer error consumed (see {@link LexerErrorSpans})
 *  @param syntaxErrorListener the lexer's errors (token recognition errors)
 */
public record LexingResult(@NotNull List<Token> tokens,
						   @NotNull Vocabulary vocabulary,
						   @NotNull List<String> channelNames,
						   @NotNull List<Interval> errorSpans,
						   @NotNull SyntaxErrorListener syntaxErrorListener)
{
	/** Fills {@code stream} to EOF and takes its tokens; its source must be a {@link Lexer} for
	 *  {@code lg}, with a {@link LexerErrorSpans} listener for the error spans. A parser may
	 *  stop before EOF, so the rest of the input is lexed here.
	 */
	public static @NotNull LexingResult of(@NotNull CommonTokenStream stream, @NotNull SyntaxErrorListener errors,
										   @NotNull LexerGrammar lg) {
		stream.fill();
		Lexer lexer = (Lexer) stream.getTokenSource();
		return new LexingResult(List.copyOf(stream.getTokens()), lexer.getVocabulary(), channelNames(lg),
								LexerErrorSpans.of(lexer), errors);
	}

	/** {@code lg}'s channel names by channel number. Not the lexer interpreter's {@code
	 *  getChannelNames()}: ANTLR 4.13.2's {@code Grammar.createLexerInterpreter} puts
	 *  {@code DEFAULT_TOKEN_CHANNEL} and {@code HIDDEN} before {@code channelValueToNameList},
	 *  which is already indexed by channel number, so user channels come out two places late.
	 */
	static @NotNull List<String> channelNames(@NotNull LexerGrammar lg) {
		List<String> names = new ArrayList<>(lg.channelValueToNameList);
		while ( names.size()<2 ) names.add(null);
		names.set(Token.DEFAULT_CHANNEL, "DEFAULT_TOKEN_CHANNEL");
		names.set(Token.HIDDEN_CHANNEL, "HIDDEN");
		return names;
	}
}
