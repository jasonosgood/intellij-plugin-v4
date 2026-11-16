package org.antlr.intellij.plugin.jps;

import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.serialization.JpsProjectLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Loads projects the way the build process does and reads the settings from the result. */
public class AntlrProjectSettingsTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void readsWhatTheIdeStores() throws Exception {
		Path project = project(
			"<component name=\"ANTLRGenerationPreferences\">\n"+
			"  <option name=\"perGrammarGenerationSettings\"><list>\n"+
			"    <PerGrammarGenerationSettings>\n"+
			"      <option name=\"fileName\" value=\"$PROJECT_DIR$/src/demo/Hello.g4\"/>\n"+
			"      <option name=\"autoGen\" value=\"true\"/>\n"+ // a removed setting, in old projects
			"      <option name=\"outputDir\" value=\"$PROJECT_DIR$/generated\"/>\n"+
			"      <option name=\"libDir\" value=\"\"/>\n"+
			"      <option name=\"encoding\" value=\"UTF-8\"/>\n"+
			"      <option name=\"pkg\" value=\"demo\"/>\n"+
			"      <option name=\"language\" value=\"Java\"/>\n"+
			"      <option name=\"generateListener\" value=\"false\"/>\n"+
			"      <option name=\"generateVisitor\" value=\"true\"/>\n"+
			"    </PerGrammarGenerationSettings>\n"+
			"    <PerGrammarGenerationSettings>\n"+
			"      <option name=\"fileName\" value=\"$PROJECT_DIR$/src/*/*Lexer.g4\"/>\n"+
			"      <option name=\"pkg\" value=\"lexers\"/>\n"+
			"    </PerGrammarGenerationSettings>\n"+
			"    <PerGrammarGenerationSettings>\n"+
			"      <option name=\"fileName\" value=\"*\"/>\n"+
			"      <option name=\"pkg\" value=\"everything.else\"/>\n"+
			"    </PerGrammarGenerationSettings>\n"+
			"  </list></option>\n"+
			"</component>\n");
		AntlrProjectSettings settings = load(project);
		String dir = project.toString().replace('\\', '/');

		GenerationSettings hello = settings.settingsFor(dir+"/src/demo/Hello.g4");
		assertEquals("demo", hello.pkg);
		assertEquals("path macros are expanded on load", dir+"/generated", hello.outputDir);
		assertEquals("", hello.libDir);
		assertEquals("UTF-8", hello.encoding);
		assertEquals("Java", hello.language);
		assertFalse(hello.generateListener);
		assertTrue(hello.generateVisitor);

		GenerationSettings lexer = settings.settingsFor(dir+"/src/demo/HelloLexer.g4");
		assertEquals("lexers", lexer.pkg);
		assertNull("an option that was never set isn't stored", lexer.outputDir);
		assertTrue("the listener is on unless stored as off", lexer.generateListener);
		assertFalse("the visitor is off unless stored as on", lexer.generateVisitor);

		assertEquals("everything.else", settings.settingsFor(dir+"/other/Other.g4").pkg);
	}

	@Test
	public void aProjectThatStoresNothingGetsTheDefaults() throws Exception {
		AntlrProjectSettings settings = load(project(""));
		assertSame(GenerationSettings.DEFAULTS, settings.settingsFor("/anywhere/Hello.g4"));
	}

	private AntlrProjectSettings load(Path project) throws IOException {
		JpsModel model = JpsElementFactory.getInstance().createModel();
		JpsProjectLoader.loadProject(model.getProject(), Collections.emptyMap(), project);
		return AntlrProjectSettings.of(model.getProject());
	}

	private Path project(String miscComponents) throws IOException {
		Path project = temp.newFolder("project").toPath().toRealPath();
		Files.createDirectories(project.resolve(".idea"));
		write(project.resolve(".idea/modules.xml"),
			"<project version=\"4\"><component name=\"ProjectModuleManager\"><modules/></component></project>\n");
		write(project.resolve(".idea/misc.xml"), "<project version=\"4\">\n"+miscComponents+"</project>\n");
		return project;
	}

	private static void write(Path file, String text) throws IOException {
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}
}
