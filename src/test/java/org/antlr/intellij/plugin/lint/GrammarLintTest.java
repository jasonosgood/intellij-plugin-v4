package org.antlr.intellij.plugin.lint;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.util.TextRange;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import xl.Linter;
import xl.errors.Diagnostic;
import xl.errors.Position;
import xl.errors.Range;

import java.util.List;

public class GrammarLintTest extends AntlrFixtureTestCase {
	private static final String GRAMMAR = "grammar T;\nstart : a EOF ;\na : ( B ) | C | C ;\nB : 'b' ;\nC : 'c' ;\n";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		myFixture.enableInspections(new GrammarLintInspection());
	}

	public void testReportsFindings() {
		myFixture.configureByText("T.g4", GRAMMAR);
		List<HighlightInfo> infos = myFixture.doHighlighting();
		assertTrue(infos.stream().anyMatch(i -> i.getDescription()!=null && i.getDescription().startsWith("lint4:")));
		assertTrue(infos.stream().anyMatch(i -> i.getDescription()!=null && i.getDescription().startsWith("lint2:")));
	}

	public void testFixOneFinding() {
		myFixture.configureByText("T.g4", GRAMMAR);
		myFixture.doHighlighting();
		IntentionAction fix = myFixture.getAllQuickFixes().stream()
			.filter(a -> a.getText().startsWith("Fix lint4")).findFirst().orElseThrow();
		myFixture.launchAction(fix);
		assertEquals("grammar T;\nstart : a EOF ;\na : B | C | C ;\nB : 'b' ;\nC : 'c' ;\n", myFixture.getEditor().getDocument().getText());
	}

	public void testFixAll() {
		myFixture.configureByText("T.g4", GRAMMAR);
		myFixture.doHighlighting();
		IntentionAction fix = myFixture.getAllQuickFixes().stream()
			.filter(a -> a.getText().equals("Fix all grammar lint findings in file")).findFirst().orElseThrow();
		myFixture.launchAction(fix);
		assertEquals("grammar T;\nstart : a EOF ;\na : B | C ;\nB : 'b' ;\nC : 'c' ;\n", myFixture.getEditor().getDocument().getText());
	}

	/** Removing the parens exposes a duplicate alternative. */
	private static final String CASCADE = "grammar T;\nstart : a EOF ;\na : ( B | C ) | C ;\nB : 'b' ;\nC : 'c' ;\n";
	private static final String CASCADE_FIXED = "grammar T;\nstart : a EOF ;\na : B | C ;\nB : 'b' ;\nC : 'c' ;\n";

	public void testFixOneRelintsAndShowsExposedFinding() {
		myFixture.configureByText("T.g4", CASCADE);
		assertFalse(hasLint(myFixture.doHighlighting(), "lint2:"));
		IntentionAction fix = myFixture.getAllQuickFixes().stream()
			.filter(a -> a.getText().startsWith("Fix lint4")).findFirst().orElseThrow();
		myFixture.launchAction(fix);
		assertTrue(hasLint(myFixture.doHighlighting(), "lint2:"));
		IntentionAction next = myFixture.getAllQuickFixes().stream()
			.filter(a -> a.getText().startsWith("Fix lint2")).findFirst().orElseThrow();
		myFixture.launchAction(next);
		assertEquals(CASCADE_FIXED, myFixture.getEditor().getDocument().getText());
	}

	public void testFixAllFollowsExposedFindings() {
		myFixture.configureByText("T.g4", CASCADE);
		myFixture.doHighlighting();
		IntentionAction fix = myFixture.getAllQuickFixes().stream()
			.filter(a -> a.getText().equals("Fix all grammar lint findings in file")).findFirst().orElseThrow();
		myFixture.launchAction(fix);
		assertEquals(CASCADE_FIXED, myFixture.getEditor().getDocument().getText());
		assertFalse(hasLint(myFixture.doHighlighting(), "lint"));
	}

	public void testFixAllIsStepwiseAndSettles() {
		String fixed = GrammarLintAnnotator.fixAll(CASCADE, null);
		assertEquals(CASCADE_FIXED, fixed);
		assertEquals(fixed, GrammarLintAnnotator.fixAll(fixed, null));
	}

	private static boolean hasLint(List<HighlightInfo> infos, String prefix) {
		return infos.stream().anyMatch(i -> i.getDescription()!=null && i.getDescription().startsWith(prefix));
	}

	/** An unused single-child rule is only reported as unused (lint3), not as inlinable (lint1). */
	public void testUnusedSingleChildRuleIsOnlyUnused() {
		String text = "grammar T;\nstart : B EOF ;\nsix : 'ORANGE' ;\nB : 'b' ;\n";
		List<GrammarLintAnnotator.Problem> problems = GrammarLintAnnotator.lint(text);
		assertTrue(problems.stream().anyMatch(p -> p.lint().equals("lint3")));
		assertTrue(problems.stream().noneMatch(p -> p.lint().equals("lint1")));
	}

	public void testSkipsGrammarWithSyntaxErrors() {
		myFixture.configureByText("T.g4", "grammar T;\nstart : ( B ) | ;;\n");
		assertTrue(myFixture.doHighlighting().stream()
			.noneMatch(i -> i.getDescription()!=null && i.getDescription().startsWith("lint")));
	}

	public void testOffsetsAfterCharactersOutsideTheBmp() {
		String text = "grammar T;\n// 😀 emoji\nstart : a EOF ;\na : ( B ) ;\nB : 'b' ;\n";
		List<GrammarLintAnnotator.Problem> problems = GrammarLintAnnotator.lint(text);
		GrammarLintAnnotator.Problem parens = problems.stream().filter(p -> p.lint().equals("lint4")).findFirst().orElseThrow();
		assertEquals("(", parens.range().substring(text));
	}

	public void testLinterUsesSharedDiagnosticsAndGroupsFixes() {
		Diagnostic parens = new Linter().lint(GRAMMAR).stream()
			.filter(d -> d.code().equals("lint4")).findFirst().orElseThrow();
		assertEquals("WARNING", parens.severity().name());
		assertEquals(2, parens.fixes().size());
		assertTrue(parens.fixes().stream().allMatch(f -> f.replacement().isEmpty()));
	}

	public void testMultilineCodePointRangeConvertsToEditorOffsets() {
		String text = "grammar T;\n// 😀 emoji\nstart : ( B ) EOF ;\nB : 'b' ;\n";
		TextRange range = GrammarLintAnnotator.range(text, new Range(new Position(3, 0), new Position(4, 0)));
		assertEquals(text.substring(text.indexOf("start"), text.indexOf("B :")), range.substring(text));
	}
}
