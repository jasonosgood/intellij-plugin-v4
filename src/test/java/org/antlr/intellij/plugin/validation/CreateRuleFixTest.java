package org.antlr.intellij.plugin.validation;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.TextRange;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;

public class CreateRuleFixTest extends AntlrFixtureTestCase {

	@Override
	protected String getTestDataPath() {
		return "src/test/resources/quickfixes/CreateRuleFix/";
	}

	public void testQuickFixDescriptionShouldShowRuleName() {
		// Given
		myFixture.configureByFile("missingRule.g4");
		CreateRuleFix createRuleFix = new CreateRuleFix(TextRange.create(30, 37), myFixture.getFile());

		// When
		String description = createRuleFix.getText();

		// Then
		assertEquals("Create rule 'newRule'", description);
	}

	public void testQuickFixShouldCreateRuleAfterCurrentRule() {
		// Given
		myFixture.configureByFile("missingRule.g4");
		CreateRuleFix createRuleFix = new CreateRuleFix(TextRange.create(30, 37), myFixture.getFile());

		// When
		WriteCommandAction.runWriteCommandAction(getProject(), // quick fixes run inside a command
				() -> createRuleFix.invoke(getProject(), myFixture.getEditor(), myFixture.getFile())
		);

		// Then
		assertEquals(
				"grammar missingRule;\n\n" +
				"myRule: newRule;\n\n" +
				"newRule: ' ';",
				myFixture.getFile().getText()
		);
	}
}
