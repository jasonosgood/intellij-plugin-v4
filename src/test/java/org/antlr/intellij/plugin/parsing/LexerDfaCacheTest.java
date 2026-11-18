package org.antlr.intellij.plugin.parsing;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** Plain JUnit (no IDE fixture): preview lexers of one grammar object share its DFA and ATN. */
public class LexerDfaCacheTest {
	private static final String GRAMMAR = """
		lexer grammar L;
		channels { COMMENTS }
		ID : [a-z]+ ;
		INT : [0-9]+ ;
		LINE_COMMENT : '//' ~[\\n]* -> channel(COMMENTS) ;
		WS : [ \\n]+ -> skip ;
		STR_START : '"' -> pushMode(STR) ;
		mode STR;
		STR_END : '"' -> popMode ;
		TEXT : ~'"'+ ;
		""";
	private static final String INPUT = "ab 12 // note\n\"x y\" c";

	@Test
	public void lexersOfOneGrammarShareTheDfa() throws Exception {
		LexerGrammar lg = new LexerGrammar(GRAMMAR);
		LexerInterpreter first = LexerDfaCache.newLexer(lg, CharStreams.fromString(INPUT));
		LexerInterpreter second = LexerDfaCache.newLexer(lg, CharStreams.fromString(INPUT));
		assertSame(first.getInterpreter().decisionToDFA, second.getInterpreter().decisionToDFA);
		assertSame(first.getATN(), second.getATN());
	}

	/** A reload makes a new grammar object, which starts with an empty cache. */
	@Test
	public void anotherGrammarObjectHasItsOwnDfa() throws Exception {
		LexerInterpreter before = LexerDfaCache.newLexer(new LexerGrammar(GRAMMAR), CharStreams.fromString(""));
		LexerInterpreter after = LexerDfaCache.newLexer(new LexerGrammar(GRAMMAR), CharStreams.fromString(""));
		assertNotSame(before.getInterpreter().decisionToDFA, after.getInterpreter().decisionToDFA);
	}

	/** Same tokens as ANTLR's own interpreter, modes and channels included, cold and warm. */
	@Test
	public void lexesAsCreateLexerInterpreterDoes() throws Exception {
		LexerGrammar lg = new LexerGrammar(GRAMMAR);
		String expected = tokens(lg.createLexerInterpreter(CharStreams.fromString(INPUT)));
		assertEquals(expected, tokens(LexerDfaCache.newLexer(lg, CharStreams.fromString(INPUT))));
		assertEquals(expected, tokens(LexerDfaCache.newLexer(lg, CharStreams.fromString(INPUT))));
	}

	private static String tokens(Lexer lexer) {
		CommonTokenStream stream = new CommonTokenStream(lexer);
		stream.fill();
		List<Token> tokens = stream.getTokens();
		StringBuilder sb = new StringBuilder();
		for (Token t : tokens) {
			sb.append(lexer.getVocabulary().getSymbolicName(t.getType())).append(':').append(t.getText())
				.append('@').append(t.getChannel()).append(' ');
		}
		return sb.toString();
	}
}
