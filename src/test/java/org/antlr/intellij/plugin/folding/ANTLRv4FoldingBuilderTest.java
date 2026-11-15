package org.antlr.intellij.plugin.folding;

import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.testFramework.EditorTestUtil;

public class ANTLRv4FoldingBuilderTest extends AntlrFixtureTestCase {

	public void test_folding_should_not_throw_on_incomplete_prequel() {
		// Given
		myFixture.configureByText("foo.g4", "grammar foo;\n @\n");

		// When
		buildInitialFoldings();

		// Then
		FoldRegion[] allFoldRegions = myFixture.getEditor().getFoldingModel().getAllFoldRegions();
		assertEquals(0, allFoldRegions.length);
	}

	public void test_should_not_fold_single_line() {
		// Given
		myFixture.configureByText("foo.g4", "grammar foo;\n @members { int i; }\n");

		// When
		buildInitialFoldings();

		// Then
		FoldRegion[] allFoldRegions = myFixture.getEditor().getFoldingModel().getAllFoldRegions();
		assertEquals(0, allFoldRegions.length);
	}

	private void buildInitialFoldings() {
		EditorTestUtil.buildInitialFoldingsInBackground(myFixture.getEditor());
	}
}
