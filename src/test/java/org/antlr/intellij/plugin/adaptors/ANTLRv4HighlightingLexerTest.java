package org.antlr.intellij.plugin.adaptors;

import com.intellij.lexer.Lexer;
import com.intellij.testFramework.LexerTestCase;
import org.antlr.intellij.plugin.ANTLRv4TokenTypes;

import java.nio.file.Files;
import java.nio.file.Path;

/** The editor's highlighting lexer (xl's lexer, the plugin's token types). Uses the platform's
 *  {@code LexerTestCase}: {@code doTest} also checks that the lexer restarts correctly at every
 *  token, which is what the editor does after each edit.
 */
public class ANTLRv4HighlightingLexerTest extends LexerTestCase {
	/** Every construct that put the plugin's own lexer in a mode: options, tokens, channels,
	 *  actions (nested, with strings), predicates, arguments and char sets; plus comments.
	 */
	private static final String GRAMMAR = """
		/** doc */
		grammar T;
		options { language = Java; superClass = Base; }
		tokens { A, B }
		channels { C }
		@members { int n = 0; void f() { if (n > 0) { n--; } } String s = "}"; char c = '{'; }
		r[int x] returns [int y] : A {n++;} | {n > 0}? B ;
		ID : [a-zA-Z_]+ -> skip ; // line comment
		/* block */ WS : [ \\t]+ -> channel(HIDDEN) ;
		""";

	@Override
	protected Lexer createLexer() {
		return new ANTLRv4HighlightingLexer();
	}

	@Override
	protected String getDirPath() {
		return "";
	}

	public void testEveryXlTokenHasAPluginTokenType() {
		assertEmpty(ANTLRv4HighlightingLexer.unmappedXlTokens());
	}

	public void testRestartsCorrectlyAnywhere() {
		checkCorrectRestart(GRAMMAR);
		assertFalse(printTokens(GRAMMAR, 0).contains("BAD_TOKEN"));
	}

	public void testRuleWithKeywordsCommandsAndCode() {
		doTest("fragment X : 'x' -> skip ; r : {p}? X+ ;", """
			'fragment' ('fragment')
			WS (' ')
			TOKEN_REF ('X')
			WS (' ')
			COLON (':')
			WS (' ')
			STRING_LITERAL (''x'')
			WS (' ')
			RARROW ('->')
			WS (' ')
			RULE_REF ('skip')
			WS (' ')
			SEMI (';')
			WS (' ')
			RULE_REF ('r')
			WS (' ')
			COLON (':')
			WS (' ')
			ACTION_CONTENT ('{p}?')
			WS (' ')
			TOKEN_REF ('X')
			STAR ('+')
			WS (' ')
			SEMI (';')""");
	}

	public void testOptionsBlockIsAnOpeningBraceForTheBraceMatcher() {
		doTest("options { k = 1; }", """
			OPTIONS ('options {')
			WS (' ')
			RULE_REF ('k')
			WS (' ')
			ASSIGN ('=')
			WS (' ')
			INT ('1')
			SEMI (';')
			WS (' ')
			RBRACE ('}')""");
	}

	/** An unclosed brace marks one bad character; the rest still lexes. */
	public void testUnclosedBraceMarksOneCharacter() {
		doTest("r : { A ;", """
			RULE_REF ('r')
			WS (' ')
			COLON (':')
			WS (' ')
			BAD_TOKEN ('{')
			WS (' ')
			TOKEN_REF ('A')
			WS (' ')
			SEMI (';')""");
	}

	/** The grammar whose typing was slow (the plugin's lexer, restarted in options mode). */
	public void testLargeGrammarHasNoBadCharacters() throws Exception {
		Path oracle = Path.of("antlr4-xl/test/oracle_normal.g4");
		if ( !Files.exists(oracle) ) return; // submodule not checked out
		Lexer lexer = createLexer();
		lexer.start(Files.readString(oracle));
		int tokens = 0;
		for (; lexer.getTokenType()!=null; lexer.advance(), tokens++) {
			assertNotSame("bad character at " + lexer.getTokenStart(), ANTLRv4TokenTypes.BAD_TOKEN_TYPE, lexer.getTokenType());
		}
		assertTrue(tokens > 70_000);
	}
}
