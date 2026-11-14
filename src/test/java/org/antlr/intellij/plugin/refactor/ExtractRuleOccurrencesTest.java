package org.antlr.intellij.plugin.refactor;

import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Plain JUnit (no IDE fixture): parses grammar text with the ANTLR meta-grammar and
 * checks which spans {@link ExtractRuleOccurrences} would replace.
 * <p>
 * Input marks the user's selection with «…»; expected output marks every
 * occurrence found with […].
 */
public class ExtractRuleOccurrencesTest {

	@Test
	public void findsSameSequenceInOtherRules() {
		check("grammar T;\na : «B C» D ;\nb : E B C ;\n",
		      "grammar T;\na : [B C] D ;\nb : E [B C] ;\n");
	}

	@Test
	public void ignoresWhitespaceAndComments() {
		check("grammar T;\na : «B C» ;\nb : B /* note */\n    C ;\n",
		      "grammar T;\na : [B C] ;\nb : [B /* note */\n    C] ;\n");
	}

	@Test
	public void findsInsideNestedBlocks() {
		check("grammar T;\na : «B C» ;\nb : (X | B C)* ;\n",
		      "grammar T;\na : [B C] ;\nb : (X | [B C])* ;\n");
	}

	@Test
	public void skipsElementWithEbnfSuffix() {
		// `B C*` means B (C*), so B C is not a unit there
		check("grammar T;\na : «B C» ;\nb : B C* ;\nc : B C? ;\n",
		      "grammar T;\na : [B C] ;\nb : B C* ;\nc : B C? ;\n");
	}

	@Test
	public void matchesElementWithSameSuffix() {
		check("grammar T;\na : «B C*» ;\nb : B C* ;\n",
		      "grammar T;\na : [B C*] ;\nb : [B C*] ;\n");
	}

	@Test
	public void skipsNegatedAndLabeledElements() {
		check("grammar T;\na : «B» ;\nb : ~B ;\nc : x=B ;\nd : B ;\n",
		      "grammar T;\na : [B] ;\nb : ~B ;\nc : x=B ;\nd : [B] ;\n");
	}

	@Test
	public void alternativesMustBeWholeAlternatives() {
		// in `X B | C` the run `B | C` is not a unit: it would bind as X (B | C)
		check("grammar T;\na : («B | C») ;\nb : (B | C | D) ;\nc : (X B | C) ;\n",
		      "grammar T;\na : ([B | C]) ;\nb : ([B | C] | D) ;\nc : (X B | C) ;\n");
	}

	@Test
	public void findsTopLevelAlternatives() {
		check("grammar T;\na : «B | C» ;\nb : B | C | D ;\n",
		      "grammar T;\na : [B | C] ;\nb : [B | C] | D ;\n");
	}

	@Test
	public void parserSelectionDoesNotMatchLexerRules() {
		check("grammar T;\na : «'x' 'y'» ;\nB : 'x' 'y' ;\nc : 'x' 'y' ;\n",
		      "grammar T;\na : ['x' 'y'] ;\nB : 'x' 'y' ;\nc : ['x' 'y'] ;\n");
	}

	@Test
	public void lexerSelectionMatchesOnlyLexerRules() {
		check("grammar T;\na : 'x' 'y' ;\nB : «'x' 'y'» ;\nC : 'z' | 'x' 'y' ;\n",
		      "grammar T;\na : 'x' 'y' ;\nB : ['x' 'y'] ;\nC : 'z' | ['x' 'y'] ;\n");
	}

	@Test
	public void lexerSkipsSuffixAndNot() {
		check("grammar T;\nA : «'a'» ;\nB : 'a'+ ;\nC : ~'a' ;\nD : 'a' -> skip ;\n",
		      "grammar T;\nA : ['a'] ;\nB : 'a'+ ;\nC : ~'a' ;\nD : ['a'] -> skip ;\n");
	}

	@Test
	public void doesNotMatchRuleNamesOrOptions() {
		check("grammar T;\nb : «a» ;\na : X ;\nc : a ;\n",
		      "grammar T;\nb : [a] ;\na : X ;\nc : [a] ;\n");
	}

	@Test
	public void overlappingOccurrencesKeepTheFirst() {
		check("grammar T;\na : «B B» ;\nb : B B B ;\n",
		      "grammar T;\na : [B B] ;\nb : [B B] B ;\n");
	}

	@Test
	public void selectionWinsOverEarlierOverlap() {
		check("grammar T;\na : B «B B» ;\n",
		      "grammar T;\na : B [B B] ;\n");
	}

	@Test
	public void selectionThatIsNotAUnitIsStillReturned() {
		check("grammar T;\na : «B | C» D ;\n",
		      "grammar T;\na : [B | C] D ;\n");
	}

	// ---------------------------------------------------------------------------------

	private static void check(String markedInput, String expected) {
		int selStart = markedInput.indexOf('«');
		int selEnd = markedInput.indexOf('»') - 1; // exclusive, after removing «
		String grammar = markedInput.replace("«", "").replace("»", "");

		ParsingResult result = ParsingUtils.parseANTLRGrammar(grammar);
		assertEquals("grammar must parse cleanly", 0, result.syntaxErrorListener.getSyntaxErrors().size());
		TokenStream tokens = result.parser.getTokenStream();

		Token start = RefactorUtils.getTokenForCharIndex(tokens, selStart);
		Token stop = RefactorUtils.getTokenForCharIndex(tokens, selEnd - 1);
		List<Interval> found = ExtractRuleOccurrences.find(
			(ParserRuleContext) result.tree, tokens, start.getTokenIndex(), stop.getTokenIndex());

		StringBuilder marked = new StringBuilder(grammar);
		for (int i = found.size() - 1; i>=0; i--) {
			Interval occ = found.get(i);
			marked.insert(tokens.get(occ.b).getStopIndex() + 1, ']');
			marked.insert(tokens.get(occ.a).getStartIndex(), '[');
		}
		assertEquals(expected, marked.toString());
	}
}
