package org.antlr.intellij.plugin.parsing;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.tool.Grammar;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** Plain JUnit (no IDE fixture): preview parsers of one grammar object share its prediction cache. */
public class ParserDfaCacheTest {
	private static final String GRAMMAR = """
		grammar T;
		s : 'a' 'b' | 'a' 'c' ;
		""";

	@Test
	public void parsersOfOneGrammarShareTheDfa() throws Exception {
		Grammar g = new Grammar(GRAMMAR);
		PreviewParser first = ParserDfaCache.newParser(g, noTokens());
		PreviewParser second = ParserDfaCache.newParser(g, noTokens());
		assertSame(first.getInterpreter().decisionToDFA, second.getInterpreter().decisionToDFA);
		assertSame(first.getInterpreter().getSharedContextCache(), second.getInterpreter().getSharedContextCache());
		assertSame(first.getATN(), second.getATN());
	}

	@Test
	public void profilingKeepsTheSharedDfa() throws Exception {
		Grammar g = new Grammar(GRAMMAR);
		PreviewParser plain = ParserDfaCache.newParser(g, noTokens());
		PreviewParser profiled = ParserDfaCache.newParser(g, noTokens());
		profiled.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		profiled.setProfile(true);
		assertSame(plain.getInterpreter().decisionToDFA, profiled.getInterpreter().decisionToDFA);
	}

	/** A reload makes a new grammar object, which starts with an empty cache. */
	@Test
	public void anotherGrammarObjectHasItsOwnDfa() throws Exception {
		PreviewParser before = ParserDfaCache.newParser(new Grammar(GRAMMAR), noTokens());
		PreviewParser after = ParserDfaCache.newParser(new Grammar(GRAMMAR), noTokens());
		assertNotSame(before.getInterpreter().decisionToDFA, after.getInterpreter().decisionToDFA);
	}

	private static CommonTokenStream noTokens() {
		return new CommonTokenStream(new ListTokenSource(List.of()));
	}
}
