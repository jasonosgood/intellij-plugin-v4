package org.antlr.intellij.plugin.diagnostics;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Test;
import xl.errors.Diagnostics;
import xl.errors.Position;
import xl.errors.PreCheck;
import xl.errors.TokenCheck;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class XlErrorsIntegrationTest {
	@Test
	public void preCheckUsesGeneratedLexerAndCodePointPositions() {
		var diagnostics = new Diagnostics();
		var result = PreCheck.run("😀\u200B".getBytes(StandardCharsets.UTF_8), PreCheck.Options.DEFAULT, diagnostics);

		assertTrue(result.parse());
		assertEquals(1, diagnostics.sorted().size());
		var diagnostic = diagnostics.sorted().getFirst();
		assertEquals("invisible-char", diagnostic.code());
		assertEquals(new Position(1, 1), diagnostic.range().start());
		assertEquals("", diagnostic.fixes().getFirst().replacement());
	}

	@Test
	public void tokenCheckReportsUnclosedBracket() {
		var open = new CommonToken(1, "(");
		open.setLine(1);
		open.setCharPositionInLine(0);
		var eof = new CommonToken(Token.EOF, "<EOF>");
		var vocabulary = new VocabularyImpl(new String[]{null, "'('", "')'"}, new String[3]);
		var options = new TokenCheck.Options(List.of(new TokenCheck.Pair(1, 2)), 100, false);
		var diagnostics = new Diagnostics();

		assertTrue(TokenCheck.run(List.of(open, eof), vocabulary, options, diagnostics));
		var diagnostic = diagnostics.sorted().getFirst();
		assertEquals("unclosed-bracket", diagnostic.code());
		assertEquals(")", diagnostic.fixes().getFirst().replacement());
		assertEquals(1, diagnostic.labels().size());
	}
}