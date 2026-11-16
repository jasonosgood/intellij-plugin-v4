package org.antlr.intellij.plugin.generation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BuildMessagesTest {

	@Test
	public void outputNotSourceRootRoundTrips() {
		String text = BuildMessages.outputNotSourceRoot("demo", "/project/gen");

		assertArrayEquals(new String[] {"demo", "/project/gen"}, BuildMessages.parseOutputNotSourceRoot(text));
		assertNull(BuildMessages.parseOutputNotSourceRoot("no separator"));
	}

	@Test
	public void showsAPathRelativeToTheDirectoryThatHoldsIt() {
		assertEquals("gen", BuildMessages.displayPath("/project/gen", Collections.singletonList("/project")));
		assertEquals("gen/parser", BuildMessages.displayPath("/project/gen/parser", Arrays.asList("/other", "/project/")));
		assertEquals(".", BuildMessages.displayPath("/project", Collections.singletonList("/project")));
	}

	@Test
	public void showsAPathOutsideEveryDirectoryInFull() {
		assertEquals("/elsewhere/gen", BuildMessages.displayPath("/elsewhere/gen", Collections.singletonList("/project")));
		assertEquals("a sibling with the same prefix isn't inside",
			"/project-two/gen", BuildMessages.displayPath("/project-two/gen", Collections.singletonList("/project")));
		assertEquals("/project/gen", BuildMessages.displayPath("/project/gen", Collections.emptyList()));
	}
}
