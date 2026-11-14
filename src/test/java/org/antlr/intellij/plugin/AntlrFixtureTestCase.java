package org.antlr.intellij.plugin;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** Fixture test base that tolerates the Tool Output editor the plugin keeps alive. */
public abstract class AntlrFixtureTestCase extends BasePlatformTestCase {
	@Override
	protected void tearDown() throws Exception {
		TestUtils.tearDownIgnoringObjectNotDisposedException(() -> super.tearDown());
	}
}
