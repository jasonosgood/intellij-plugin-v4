package org.antlr.intellij.plugin.format;

import com.intellij.psi.codeStyle.CodeStyleManager;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;

import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/** Reformat Code and Sort Parser Rules on .g4 files, through the IDE entry points. */
public class GrammarToolsTest extends AntlrFixtureTestCase {
	private static final String REFORMAT = "reformat";
	private static final String SORT = "antlr.SortRules";
	private static final String SORT_THEN_UNDO = "sort+undo";

	public void testReformatCodeUsesGrammarFormatter() {
		check(REFORMAT,
		      "grammar T;\nstart : a | b ;\na:'a';\nb : 'b' ;\n",
		      "grammar T;\n\nstart\n    : a\n    | b\n    ;\n\na\n    : 'a'\n    ;\n\nb\n    : 'b'\n    ;\n");
	}

	public void testReformatLeavesBrokenGrammarAlone() {
		String broken = "grammar T;\nstart : a | ;;\n'unterminated\n";
		check(REFORMAT, broken, broken);
	}

	public void testSortRulesKeepsStartRuleAndLexerOrder() {
		check(SORT,
		      "grammar T;\nstart : c b a ;\n// about c\nc : 'c' ;\nb : 'b' ;\na : 'a' ;\nZ : 'z' ;\nY : 'y' ;\n",
		      "grammar T;\nstart : c b a ;\na : 'a' ;\nb : 'b' ;\n// about c\nc : 'c' ;\nZ : 'z' ;\nY : 'y' ;\n");
	}

	public void testSortRulesIsOneUndoStep() {
		String unsorted = "grammar T;\nstart : b a ;\nb : 'b' ;\na : 'a' ;\n";
		check(SORT_THEN_UNDO, unsorted, unsorted);
	}

	private void check(String operation, String before, String expected) {
		myFixture.configureByText("T.g4", before);
		switch ( operation ) {
			case REFORMAT -> runWriteCommandAction(getProject(),
				() -> { CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile()); });
			case SORT_THEN_UNDO -> {
				myFixture.performEditorAction(SORT);
				assertFalse("sort changed nothing", before.equals(myFixture.getEditor().getDocument().getText()));
				myFixture.performEditorAction("$Undo");
			}
			default -> myFixture.performEditorAction(operation);
		}
		assertEquals(expected, myFixture.getEditor().getDocument().getText());
	}
}
