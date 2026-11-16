package org.antlr.intellij.plugin.configdialogs;

import com.intellij.util.xmlb.XmlSerializer;
import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.antlr.intellij.plugin.jps.AntlrProjectSettings;
import org.jdom.Element;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Guards the seam between the IDE, which writes the per-grammar settings with the platform's
 * XML serializer, and the JPS builder, which reads that XML by hand in the build process
 * (doc/B2_JPS_BUILDER.md, decision 3): what one writes, the other must read as the same values.
 */
public class SettingsReadByTheBuildTest {
	private static final String GRAMMAR = "/project/src/demo/Hello.g4";

	@Test
	public void theBuildReadsWhatTheIdeWrites() {
		ANTLRv4GrammarProperties edited = new ANTLRv4GrammarProperties();
		edited.fileName = GRAMMAR;
		edited.outputDir = "generated";
		edited.libDir = "vocab";
		edited.encoding = "UTF-8";
		edited.pkg = "demo";
		edited.language = "Python3";
		edited.generateListener = false;
		edited.generateVisitor = true;
		ANTLRv4GrammarProperties untouched = new ANTLRv4GrammarProperties();
		untouched.fileName = "/project/src/demo/Other.g4";
		ANTLRv4GrammarPropertiesStore store = new ANTLRv4GrammarPropertiesStore();
		store.add(edited);
		store.add(untouched);

		Element written = XmlSerializer.serialize(store);
		AntlrProjectSettings read = AntlrProjectSettings.parse(written);

		assertSameSettings(edited, read.settingsFor(GRAMMAR));
		assertSameSettings(untouched, read.settingsFor(untouched.fileName));
	}

	@Test
	public void bothSidesHaveTheSameDefaults() {
		assertSameSettings(ANTLRv4GrammarPropertiesStore.DEFAULT_GRAMMAR_PROPERTIES, GenerationSettings.DEFAULTS);
	}

	private static void assertSameSettings(ANTLRv4GrammarProperties ide, GenerationSettings build) {
		GenerationSettings expected = ide.toGenerationSettings();
		assertEquals("outputDir", expected.outputDir, build.outputDir);
		assertEquals("libDir", expected.libDir, build.libDir);
		assertEquals("encoding", expected.encoding, build.encoding);
		assertEquals("pkg", expected.pkg, build.pkg);
		assertEquals("language", expected.language, build.language);
		assertEquals("generateListener", expected.generateListener, build.generateListener);
		assertEquals("generateVisitor", expected.generateVisitor, build.generateVisitor);
	}
}
