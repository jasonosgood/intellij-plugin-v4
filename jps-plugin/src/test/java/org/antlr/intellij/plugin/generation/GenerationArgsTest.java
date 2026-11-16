package org.antlr.intellij.plugin.generation;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The same cases as the plugin's fixture test of the same name, without an IDE. */
public class GenerationArgsTest {
	private static final GenerationSettings DEFAULTS = new GenerationSettings("", "", "", "", "", true, true);

	@Test
	public void defaults() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("-package", "mypkg");
		expected.put("-o", "/root/gen/mypkg");
		expected.put("-lib", "/root/src/mypkg");
		expected.put("-listener", "");
		expected.put("-visitor", "");
		assertEquals(expected, GenerationArgs.of(DEFAULTS, place("mypkg")).asMap());
	}

	@Test
	public void everySetting() {
		GenerationSettings settings = new GenerationSettings("out/antlr", "lib", "UTF-8", "com.x", "Python3", false, false);
		GrammarPlace place = new GrammarPlace("/root/src/mypkg", "/root",
			() -> { fail("the settings name a package"); return null; }, path -> path);

		GenerationArgs args = GenerationArgs.of(settings, place);

		assertEquals(Arrays.asList(
			"-package", "com.x",
			"-Dlanguage=Python3",
			"-o", "/root/out/antlr/com/x",
			"-lib", "/root/lib",
			"-encoding", "UTF-8",
			"-no-listener",
			"-no-visitor"), args.asList());
		assertEquals("/root/out/antlr/com/x", args.outputDir());
	}

	@Test
	public void noPackageMeansNoPackageDirectory() {
		for (String none : new String[] {null, ""}) {
			Map<String, String> args = GenerationArgs.of(DEFAULTS, place(none)).asMap();
			assertEquals("/root/gen", args.get("-o"));
			assertEquals(false, args.containsKey("-package"));
		}
	}

	@Test
	public void absoluteDirectoriesStayAsTheyAre() {
		GenerationSettings settings = new GenerationSettings("/abs/out", "/abs/lib", null, null, null, true, false);
		Map<String, String> args = GenerationArgs.of(settings, place(null)).asMap();
		assertEquals("/abs/out", args.get("-o"));
		assertEquals("/abs/lib", args.get("-lib"));
	}

	@Test
	public void pathMacrosAreExpandedBeforeDirectoriesAreResolved() {
		GenerationSettings settings = new GenerationSettings("$PROJECT_DIR$/generated", "$PROJECT_DIR$/vocab", "", "", "", true, true);
		GrammarPlace place = new GrammarPlace("/root/src/mypkg", "/root", () -> "mypkg",
			path -> path.replace("$PROJECT_DIR$", "/project"));
		Map<String, String> args = GenerationArgs.of(settings, place).asMap();
		assertEquals("/project/generated/mypkg", args.get("-o"));
		assertEquals("/project/vocab", args.get("-lib"));
	}

	private static GrammarPlace place(String inferredPackage) {
		return new GrammarPlace("/root/src/mypkg", "/root", () -> inferredPackage, path -> path);
	}
}
