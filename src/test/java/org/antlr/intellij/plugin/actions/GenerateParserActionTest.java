package org.antlr.intellij.plugin.actions;

import com.intellij.ide.util.PropertiesComponent;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;

public class GenerateParserActionTest extends AntlrFixtureTestCase {
	public void testWarnsAboutBuildToolUntilToldNotTo() {
		PropertiesComponent properties = PropertiesComponent.getInstance(getProject());
		try {
			assertTrue(GenerateParserAction.warnsAboutBuildTool(getProject()));
			properties.setValue(GenerateParserAction.DONT_WARN_BUILD_TOOL, true);
			assertFalse(GenerateParserAction.warnsAboutBuildTool(getProject()));
		}
		finally {
			properties.unsetValue(GenerateParserAction.DONT_WARN_BUILD_TOOL);
		}
	}
}
