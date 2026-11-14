package org.antlr.intellij.plugin.refactor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain JUnit (no IDE fixture) for name suggestion and the resulting grammar text.
 * The user's selection is marked with «…».
 */
public class ExtractRulePlanTest {

	// --- suggested names -------------------------------------------------------------

	@Test
	public void namesFromReferences() {
		assertEquals("exprCommaType", suggest("grammar T;\na : «expr ',' type» ;\nexpr : X ;\ntype : Y ;\n"));
	}

	@Test
	public void namesSeparatedListAsList() {
		assertEquals("exprList", suggest("grammar T;\na : «expr (',' expr)*» ;\nexpr : X ;\n"));
	}

	@Test
	public void namesParenthesizedExpression() {
		assertEquals("parenExpr", suggest("grammar T;\na : «'(' expr ')'» ;\nexpr : X ;\n"));
	}

	@Test
	public void namesFromTokensAndKeywords() {
		assertEquals("ifCondition", suggest("grammar T;\na : «'if' CONDITION» ;\n"));
		assertEquals("stringLiteral", suggest("grammar T;\na : «STRING_LITERAL» ;\n"));
	}

	@Test
	public void namesLexerRulesInUpperSnake() {
		assertEquals("DIGIT_DOT", suggest("lexer grammar T;\nA : «Digit '.'» ;\nfragment Digit : [0-9] ;\n"));
	}

	@Test
	public void ignoresLabelsAndElementOptions() {
		assertEquals("expr", suggest("grammar T;\na : «x=expr» ;\nb : y ;\n"));
		assertEquals("expr", suggest("grammar T;\na : «expr<assoc=right>» ;\nb : y ;\n"));
	}

	@Test
	public void avoidsExistingNames() {
		assertEquals("expr2", suggest("grammar T;\na : «expr» X ;\nexpr : Y ;\n"));
		assertEquals("expr3", suggest("grammar T;\na : «expr» X ;\nexpr : Y ;\nexpr2 : Z ;\n"));
	}

	@Test
	public void avoidsReservedWords() {
		assertEquals("whileRule", suggest("grammar T;\na : «'while'» ;\n"));
	}

	@Test
	public void fallsBackWhenNoWords() {
		assertEquals("addSub", suggest("grammar T;\na : «'+' '-'» ;\n"));
		assertEquals("newRule", suggest("grammar T;\na : «'=>' '<-'» ;\n"));
		assertEquals("NEW_RULE", suggest("lexer grammar T;\nA : «[a-z]+» ;\n"));
	}

	// --- resulting grammar ------------------------------------------------------------

	@Test
	public void replacesOnlySelection() {
		assertEquals("grammar T;\na : bc D ;\n\nbc : B C ;\nb : B C ;\n",
		             extract("grammar T;\na : «B C» D ;\nb : B C ;\n", "bc", false));
	}

	@Test
	public void replacesAllOccurrences() {
		assertEquals("grammar T;\na : bc D ;\n\nbc : B C ;\nb : E bc ;\nc : (bc)* ;\n",
		             extract("grammar T;\na : «B C» D ;\nb : E B C ;\nc : (B C)* ;\n", "bc", true));
	}

	@Test
	public void replacesOccurrencesBeforeTheSelection() {
		assertEquals("grammar T;\nb : bc ;\na : bc D ;\n\nbc : B C ;\n",
		             extract("grammar T;\nb : B C ;\na : «B C» D ;\n", "bc", true));
	}

	@Test
	public void trimsWhitespaceAroundSelection() {
		assertEquals("grammar T;\na :   bc   D ;\n\nbc : B C ;\n",
		             extract("grammar T;\na : «  B C  » D ;\n", "bc", false));
	}

	@Test
	public void insertsCorrectlyWhenNextRuleIsOnSameLine() {
		assertEquals("grammar T;\na : bc ;\n\nbc : B C ;\n b : X ;\n",
		             extract("grammar T;\na : «B C» ; b : X ;\n", "bc", false));
	}

	@Test
	public void insertsAtEndOfFileWithoutNewline() {
		assertEquals("grammar T;\na : bc ;\n\nbc : B C ;\n",
		             extract("grammar T;\na : «B C» ;", "bc", false));
	}

	@Test
	public void rejectsSelectionOutsideRuleBody() {
		assertNull(plan("«grammar» T;\na : B ;\n"));
		assertNull(plan("grammar T;\na : B ;«  »\n"));
	}

	@Test
	public void rejectsSelectionSpanningRules() {
		assertNull(plan("grammar T;\na : «B ;\nb : C» ;\n"));
		assertNull(plan("grammar T;\na : B «;\nb» : C ;\n"));
	}

	@Test
	public void rejectsRuleNameAndTerminator() {
		assertNull(plan("grammar T;\n«a» : B ;\n"));
		assertNull(plan("grammar T;\na : «B ;»\n"));
		assertNull(plan("grammar T;\na «: B» ;\n"));
		assertNull(plan("lexer grammar T;\n«A» : 'a' ;\n"));
	}

	@Test
	public void rejectsPartialElementsAndAlternatives() {
		assertNull(plan("grammar T;\nx : y «A | B» z ;\n"));   // would change precedence
		assertNull(plan("grammar T;\nx : «A» * ;\n"));          // inside A*
		assertNull(plan("grammar T;\nx : y=«A» ;\n"));          // inside a labelled element
		assertNull(plan("grammar T;\nx : «A |» B ;\n"));        // dangling |
	}

	@Test
	public void acceptsWholeElementsAndAlternatives() {
		assertEquals("grammar T;\nx : n ;\n\nn : A | B ;\n",
		             extract("grammar T;\nx : «A | B» ;\n", "n", false));
		assertEquals("grammar T;\nx : n | C ;\n\nn : A | B ;\n",
		             extract("grammar T;\nx : «A | B» | C ;\n", "n", false));
		assertEquals("grammar T;\nx : y (n | C) ;\n\nn : A | B ;\n",
		             extract("grammar T;\nx : y («A | B» | C) ;\n", "n", false));
		assertEquals("grammar T;\nx : n # one | C # two ;\n\nn : A B ;\n",
		             extract("grammar T;\nx : «A B» # one | C # two ;\n", "n", false));
		assertEquals("lexer grammar T;\nX : N 'b' ;\n\nN : 'a'* ;\n",
		             extract("lexer grammar T;\nX : «'a'*» 'b' ;\n", "N", false));
	}

	@Test
	public void rejectsLeftRecursiveSelection() {
		assertTrue(reason("grammar T;\ne : «e '*' e» | INT ;\n").contains("left-recursive"));
	}

	@Test
	public void allowsOwnRuleReferenceNotAtLeftEdge() {
		assertEquals("grammar T;\ne : '(' n ')' | INT ;\n\nn : e '*' e ;\n",
		             extract("grammar T;\ne : '(' «e '*' e» ')' | INT ;\n", "n", false));
	}

	@Test
	public void replaceAllSkipsLeftRecursiveOccurrences() {
		ExtractRulePlan plan = plan("grammar T;\ne : e '*' e | INT ;\nx : «e '*' e» ;\n");
		assertEquals(1, plan.occurrences.size());
	}

	@Test
	public void alternativeLabelsMoveOnlyWithAllAlternatives() {
		assertTrue(reason("grammar T;\nx : «A # a | B # b» | C # c ;\n").contains("label"));
		assertEquals("grammar T;\nx : n ;\n\nn : A # a | B # b ;\n",
		             extract("grammar T;\nx : «A # a | B # b» ;\n", "n", false));
	}

	@Test
	public void rejectsBreakingActionReferences() {
		assertTrue(reason("grammar T;\nx : «y=A» B {$y.text} ;\n").contains("$y"));    // label moves away
		assertTrue(reason("grammar T;\nx : «A» B {$A.text} ;\n").contains("$A"));      // element moves away
		assertTrue(reason("grammar T;\nx : A «B {$A.text}» ;\n").contains("$A"));      // action loses its element
		assertTrue(reason("grammar T;\nx : «B {$text}» ;\n").contains("$text"));       // rule attribute
		assertEquals("grammar T;\nx : n B ;\n\nn : y=A {$y.text} ;\n",
		             extract("grammar T;\nx : «y=A {$y.text}» B ;\n", "n", false));    // moves together
	}

	@Test
	public void replaceAllSkipsOccurrencesWhoseActionsWouldBreak() {
		ExtractRulePlan plan = plan("grammar T;\nx : «A B» ;\ny : A B {$B.text} ;\nz : A B ;\n");
		assertEquals(2, plan.occurrences.size()); // x and z, not y
	}

	@Test
	public void reservedNames() {
		assertTrue(ExtractRuleNameSuggester.isReserved("class"));
		assertTrue(ExtractRuleNameSuggester.isReserved("grammar"));
		assertTrue(ExtractRuleNameSuggester.isReserved("null"));
		assertFalse(ExtractRuleNameSuggester.isReserved("expr"));
	}

	// ---------------------------------------------------------------------------------

	private static String reason(String marked) {
		int start = marked.indexOf('«');
		int end = marked.indexOf('»') - 1;
		try {
			ExtractRulePlan.plan(marked.replace("«", "").replace("»", ""), start, end);
			return "";
		}
		catch (ExtractRulePlan.Rejected r) {
			return r.getMessage();
		}
	}

	private static ExtractRulePlan plan(String marked) {
		int start = marked.indexOf('«');
		int end = marked.indexOf('»') - 1;
		return ExtractRulePlan.create(marked.replace("«", "").replace("»", ""), start, end);
	}

	private static String suggest(String marked) {
		return plan(marked).suggestedName;
	}

	private static String extract(String marked, String name, boolean all) {
		return plan(marked).apply(name, all);
	}
}
