package org.antlr.intellij.plugin.preview;

import org.antlr.intellij.adaptor.parser.SyntaxErrorListener;
import org.antlr.intellij.plugin.parsing.LexerErrorSpans;
import org.antlr.intellij.plugin.parsing.LexingResult;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;

import java.util.List;

import static org.antlr.intellij.plugin.preview.TokenRows.Kind.ERROR;
import static org.antlr.intellij.plugin.preview.TokenRows.Kind.SKIPPED;
import static org.antlr.intellij.plugin.preview.TokenRows.Kind.TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JUnit (no IDE fixture): tokenizes text with a lexer grammar and checks the Tokens tab's rows. */
public class TokenRowsTest {
	private static final String GRAMMAR = """
		lexer grammar L;
		channels { COMMENTS }
		ID : [a-z]+ ;
		PLUS : '+' ;
		WS : [ \\t]+ -> channel(HIDDEN) ;
		NL : '\\n' -> skip ;
		COMMENT : '#' ~'\\n'* -> channel(COMMENTS) ;
		""";

	@Test
	public void rowsHaveIndexTypeTextPositionAndChannel() throws Exception {
		List<TokenRows.Row> rows = tokens("ab +cd");
		assertEquals(List.of("ID", "WS", "PLUS", "ID", "EOF"), types(rows));
		TokenRows.Row cd = rows.get(3);
		assertEquals(3, cd.index());
		assertEquals("cd", cd.text());
		assertEquals("1:4", cd.position());
		assertEquals("", cd.channel());
		assertFalse(cd.hidden());
		assertEquals(4, cd.startIndex());
		assertEquals(5, cd.stopIndex());
	}

	@Test
	public void tokensOffTheDefaultChannelAreHiddenAndNamed() throws Exception {
		List<TokenRows.Row> rows = tokens("a #note");
		TokenRows.Row ws = rows.get(1);
		assertTrue(ws.hidden());
		assertEquals("HIDDEN", ws.channel());
		TokenRows.Row comment = rows.get(2);
		assertTrue(comment.hidden());
		assertEquals("COMMENTS", comment.channel());
	}

	@Test
	public void textIsEscapedAsGrunShowsIt() throws Exception {
		List<TokenRows.Row> rows = tokens("a \t b");
		assertEquals(" \\t ", rows.get(1).text());
		assertEquals("<EOF>", rows.get(rows.size() - 1).text());
	}

	@Test
	public void skippedTextGetsARowWithItsPosition() throws Exception {
		List<TokenRows.Row> rows = rows("ab\n\ncd");
		assertEquals(List.of(TOKEN, SKIPPED, TOKEN, TOKEN), kinds(rows));
		TokenRows.Row skipped = rows.get(1);
		assertEquals(-1, skipped.index());
		assertEquals("(skipped)", skipped.type());
		assertEquals("\\n\\n", skipped.text());
		assertEquals("1:2", skipped.position());
		assertEquals(2, skipped.startIndex());
		assertEquals(3, skipped.stopIndex());
		assertEquals("3:0", rows.get(2).position()); // cd
	}

	@Test
	public void skippedTextAtTheStartAndBeforeEof() throws Exception {
		assertEquals(List.of(SKIPPED, TOKEN, SKIPPED, TOKEN), kinds(rows("\na\n")));
	}

	@Test
	public void textNoRuleMatchedIsAnErrorNotSkipped() throws Exception {
		List<TokenRows.Row> rows = rows("a\n!\nb");
		assertEquals(List.of(TOKEN, SKIPPED, ERROR, SKIPPED, TOKEN, TOKEN), kinds(rows));
		TokenRows.Row error = rows.get(2);
		assertEquals("(error)", error.type());
		assertEquals("!", error.text());
		assertEquals("2:0", error.position());
		assertEquals(2, error.startIndex());
		assertEquals(2, error.stopIndex());
	}

	@Test
	public void noGapRowsWithoutGaps() throws Exception {
		assertEquals(List.of(TOKEN, TOKEN, TOKEN, TOKEN), kinds(rows("a+b")));
	}

	@Test
	public void literalOnlyTypesShowTheirLiteral() {
		assertEquals("'+'", TokenRows.typeName(1, new VocabularyImpl(new String[] {null, "'+'"}, new String[] {null, null})));
	}

	@Test
	public void unnamedChannelsShowTheirNumber() {
		assertEquals("HIDDEN", TokenRows.channelName(1, List.of()));
		assertEquals("7", TokenRows.channelName(7, List.of("DEFAULT_TOKEN_CHANNEL", "HIDDEN")));
	}

	/** Rows for the tokens alone; the input must lex without errors. */
	private static List<TokenRows.Row> tokens(String input) throws Exception {
		List<TokenRows.Row> rows = rows(input);
		assertFalse(kinds(rows).contains(ERROR));
		return rows.stream().filter(r -> r.kind()==TOKEN).toList();
	}

	private static List<TokenRows.Row> rows(String input) throws Exception {
		LexerGrammar lg = new LexerGrammar(GRAMMAR);
		LexerInterpreter lexer = lg.createLexerInterpreter(CharStreams.fromString(input));
		SyntaxErrorListener errors = new SyntaxErrorListener();
		lexer.removeErrorListeners();
		lexer.addErrorListener(errors);
		lexer.addErrorListener(new LexerErrorSpans());
		LexingResult result = LexingResult.of(new CommonTokenStream(lexer), errors, lg);
		return TokenRows.rows(result.tokens(), result.vocabulary(), result.channelNames(), result.errorSpans());
	}

	private static List<String> types(List<TokenRows.Row> rows) {
		return rows.stream().map(TokenRows.Row::type).toList();
	}

	private static List<TokenRows.Kind> kinds(List<TokenRows.Row> rows) {
		return rows.stream().map(TokenRows.Row::kind).toList();
	}
}
