package org.antlr.intellij.plugin.jps;

import org.jetbrains.jps.build.Standalone;
import org.jetbrains.jps.cmdline.JpsModelLoader;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.java.JpsJavaSdkType;
import org.jetbrains.jps.model.serialization.JpsProjectLoader;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs real JPS builds, in this JVM, on a small project written to a temp directory: module
 * {@code demo} with source roots {@code src} and {@code gen} (generated) and the ANTLR runtime
 * as a library. The builder is found as in the IDE's build process: through META-INF/services.
 * <p>
 * Prior art: the platform's own builder tests (JpsBuildTestCase) do the same. That framework
 * isn't published for current IDE builds, so this uses the published standalone build.
 */
public class AntlrSourceGeneratorBuilderTest {
	private static final String JDK_NAME = "test-jdk";
	private static final String HELLO =
		"grammar Hello;\ngreeting : 'hello' ID ;\nID : [a-z]+ ;\nWS : [ \\t\\r\\n]+ -> skip ;\n";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private Path project;
	private File buildData;
	/** File times set by the tests move forward from here, so every change is seen as one.
	 *  It starts in the past: JPS takes a source newer than the build's start as changed
	 *  during the build, and builds it again next time.
	 */
	private long clock = System.currentTimeMillis()-3_600_000;

	@Before
	public void createProject() throws Exception {
		project = temp.newFolder("project").toPath().toRealPath();
		buildData = temp.newFolder("build-data");
		write(".idea/modules.xml",
			"<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>\n"+
			"  <module fileurl=\"file://$PROJECT_DIR$/demo.iml\" filepath=\"$PROJECT_DIR$/demo.iml\"/>\n"+
			"</modules></component></project>\n");
		writeMisc("");
		writeModule("gen");
	}

	@Test
	public void generatesRecognizersAndCompilesThemInTheSameBuild() throws Exception {
		write("src/demo/Hello.g4", HELLO);
		write("src/demo/Main.java", "package demo;\npublic class Main { Class<?> parser = HelloParser.class; }\n");

		assertEquals(Collections.emptyList(), errors(build()));

		assertTrue(exists("gen/demo/HelloParser.java"));
		assertTrue(exists("gen/demo/HelloLexer.java"));
		assertTrue(exists("gen/demo/Hello.tokens"));
		assertTrue(read("gen/demo/HelloParser.java").contains("package demo;"));
		assertTrue(exists("out/production/demo/demo/HelloParser.class"));
		assertTrue(exists("out/production/demo/demo/Main.class"));
	}

	@Test
	public void generatesAgainOnlyWhenAGrammarChanged() throws Exception {
		write("src/demo/Hello.g4", HELLO);
		write("src/demo/Main.java", "package demo;\npublic class Main {}\n");
		build();
		FileTime generated = modified("gen/demo/HelloParser.java");

		build();
		assertEquals("nothing changed", generated, modified("gen/demo/HelloParser.java"));

		write("src/demo/Main.java", "package demo;\npublic class Main { int changed; }\n");
		build();
		assertEquals("only Java changed", generated, modified("gen/demo/HelloParser.java"));

		write("src/demo/Hello.g4", HELLO+"farewell : 'bye' ID ;\n");
		assertEquals(Collections.emptyList(), errors(build()));
		assertTrue(read("gen/demo/HelloParser.java").contains("farewell"));
	}

	@Test
	public void aChangedLexerRegeneratesTheParserThatUsesItsVocabulary() throws Exception {
		write("src/demo/HelloLexer.g4", "lexer grammar HelloLexer;\nHELLO : 'hello' ;\nID : [a-z]+ ;\nWS : [ \\t\\r\\n]+ -> skip ;\n");
		write("src/demo/HelloParser.g4", "parser grammar HelloParser;\noptions { tokenVocab=HelloLexer; }\ngreeting : HELLO ID ;\n");

		assertEquals(Collections.emptyList(), messages(build(), BuildMessage.Kind.ERROR, BuildMessage.Kind.WARNING));
		assertTrue(exists("out/production/demo/demo/HelloParser.class"));
		assertTrue(exists("out/production/demo/demo/HelloLexer.class"));

		// A new first token shifts the token types the parser was generated with.
		write("src/demo/HelloLexer.g4", "lexer grammar HelloLexer;\nBYE : 'bye' ;\nHELLO : 'hello' ;\nID : [a-z]+ ;\nWS : [ \\t\\r\\n]+ -> skip ;\n");
		assertEquals(Collections.emptyList(), messages(build(), BuildMessage.Kind.ERROR, BuildMessage.Kind.WARNING));
		assertTrue(read("gen/demo/HelloParser.java").contains("BYE=1"));
	}

	@Test
	public void aGrammarErrorIsABuildErrorAtItsPosition() throws Exception {
		write("src/demo/Broken.g4", "grammar Broken;\nstart : missing ;\n");
		write("src/demo/Main.java", "package demo;\npublic class Main {}\n");

		List<BuildMessage> messages = build();

		List<CompilerMessage> fromBuilder = new ArrayList<>();
		for (BuildMessage message : messages) {
			if ( message instanceof CompilerMessage && AntlrSourceGeneratorBuilder.NAME.equals(((CompilerMessage)message).getCompilerName()) ) {
				fromBuilder.add((CompilerMessage)message);
			}
		}
		assertEquals(fromBuilder.toString(), 1, fromBuilder.size());
		CompilerMessage error = fromBuilder.get(0);
		assertEquals(BuildMessage.Kind.ERROR, error.getKind());
		assertEquals("reference to undefined rule: missing", error.getMessageText());
		assertEquals(path("src/demo/Broken.g4"), error.getSourcePath());
		assertEquals(2, error.getLine());
		assertEquals(9, error.getColumn());
		assertFalse("the compiler doesn't run after a grammar error", exists("out/production/demo/demo/Main.class"));
	}

	@Test
	public void aRemovedGrammarTakesItsGeneratedFilesWithIt() throws Exception {
		write("src/demo/Hello.g4", HELLO);
		write("src/demo/Other.g4", "grammar Other;\nother : 'other' ;\n");
		build();
		assertTrue(exists("gen/demo/OtherParser.java"));

		Files.delete(project.resolve("src/demo/Other.g4"));
		assertEquals(Collections.emptyList(), errors(build()));

		assertFalse(exists("gen/demo/OtherParser.java"));
		assertFalse(exists("gen/demo/Other.tokens"));
		assertTrue(exists("gen/demo/HelloParser.java"));
	}

	@Test
	public void aDeletedGeneratedFileComesBack() throws Exception {
		write("src/demo/Hello.g4", HELLO);
		build();

		Files.delete(project.resolve("gen/demo/HelloParser.java"));
		assertEquals(Collections.emptyList(), errors(build()));

		assertTrue(exists("gen/demo/HelloParser.java"));
		assertTrue(exists("out/production/demo/demo/HelloParser.class"));
	}

	@Test
	public void usesTheProjectsSettings() throws Exception {
		writeModule("generated");
		writeMisc(
			"<component name=\"ANTLRGenerationPreferences\">\n"+
			"  <option name=\"perGrammarGenerationSettings\"><list>\n"+
			"    <PerGrammarGenerationSettings>\n"+
			"      <option name=\"fileName\" value=\"$PROJECT_DIR$/src/demo/Hello.g4\"/>\n"+
			"      <option name=\"outputDir\" value=\"generated\"/>\n"+
			"      <option name=\"pkg\" value=\"custom.pkg\"/>\n"+
			"      <option name=\"generateListener\" value=\"false\"/>\n"+
			"      <option name=\"generateVisitor\" value=\"true\"/>\n"+
			"    </PerGrammarGenerationSettings>\n"+
			"  </list></option>\n"+
			"</component>\n");
		write("src/demo/Hello.g4", HELLO);

		assertEquals(Collections.emptyList(), errors(build()));

		assertTrue(read("generated/custom/pkg/HelloParser.java").contains("package custom.pkg;"));
		assertTrue(exists("generated/custom/pkg/HelloVisitor.java"));
		assertFalse(exists("generated/custom/pkg/HelloListener.java"));
		assertTrue(exists("out/production/demo/custom/pkg/HelloParser.class"));
	}

	@Test
	public void aPackageInTheHeaderIsNotPassedAgain() throws Exception {
		write("src/demo/Hello.g4", "grammar Hello;\n@header { package com.foo.bar; }\ngreeting : 'hello' ;\n");

		assertEquals(Collections.emptyList(), errors(build()));

		String parser = read("gen/HelloParser.java");
		assertEquals("one package declaration", parser.indexOf("package "), parser.lastIndexOf("package "));
		assertTrue(exists("out/production/demo/com/foo/bar/HelloParser.class"));
	}

	/** Builds what changed since the last build of this test, as the IDE's Build does. */
	private List<BuildMessage> build() throws Exception {
		JpsModelLoader loader = () -> {
			JpsModel model = JpsElementFactory.getInstance().createModel();
			model.getGlobal().addSdk(JDK_NAME, System.getProperty("java.home"),
				System.getProperty("java.version"), JpsJavaSdkType.INSTANCE);
			JpsProjectLoader.loadProject(model.getProject(), Collections.emptyMap(), project);
			return model;
		};
		List<BuildMessage> messages = Collections.synchronizedList(new ArrayList<>());
		Standalone.runBuild(loader, buildData, false, Collections.emptySet(), true,
			Collections.emptyList(), false, messages::add);
		return messages;
	}

	private static List<String> errors(List<BuildMessage> messages) {
		return messages(messages, BuildMessage.Kind.ERROR);
	}

	private static List<String> messages(List<BuildMessage> messages, BuildMessage.Kind... kinds) {
		List<String> texts = new ArrayList<>();
		for (BuildMessage message : messages) {
			for (BuildMessage.Kind kind : kinds) {
				if ( message.getKind()==kind ) texts.add(message.toString());
			}
		}
		return texts;
	}

	private void writeModule(String generatedSourceRoot) throws IOException {
		String runtime = new File(org.antlr.v4.runtime.Parser.class.getProtectionDomain().getCodeSource().getLocation().getPath())
			.getPath().replace(File.separatorChar, '/');
		write("demo.iml",
			"<module type=\"JAVA_MODULE\" version=\"4\">\n"+
			"  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">\n"+
			"    <content url=\"file://$MODULE_DIR$\">\n"+
			"      <sourceFolder url=\"file://$MODULE_DIR$/src\" isTestSource=\"false\"/>\n"+
			"      <sourceFolder url=\"file://$MODULE_DIR$/"+generatedSourceRoot+"\" isTestSource=\"false\" generated=\"true\"/>\n"+
			"    </content>\n"+
			"    <orderEntry type=\"inheritedJdk\"/>\n"+
			"    <orderEntry type=\"sourceFolder\" forTests=\"false\"/>\n"+
			"    <orderEntry type=\"module-library\"><library><CLASSES>\n"+
			"      <root url=\"jar://"+runtime+"!/\"/>\n"+
			"    </CLASSES></library></orderEntry>\n"+
			"  </component>\n"+
			"</module>\n");
	}

	private void writeMisc(String components) throws IOException {
		write(".idea/misc.xml",
			"<project version=\"4\">\n"+
			"<component name=\"ProjectRootManager\" version=\"2\" project-jdk-name=\""+JDK_NAME+"\" project-jdk-type=\"JavaSDK\">\n"+
			"  <output url=\"file://$PROJECT_DIR$/out\"/>\n"+
			"</component>\n"+
			components+
			"</project>\n");
	}

	private void write(String relativePath, String text) throws IOException {
		Path file = project.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
		clock += 5000;
		Files.setLastModifiedTime(file, FileTime.fromMillis(clock));
	}

	private String read(String relativePath) throws IOException {
		return new String(Files.readAllBytes(project.resolve(relativePath)), StandardCharsets.UTF_8);
	}

	private boolean exists(String relativePath) {
		return Files.exists(project.resolve(relativePath));
	}

	private FileTime modified(String relativePath) throws IOException {
		return Files.getLastModifiedTime(project.resolve(relativePath));
	}

	private String path(String relativePath) {
		return project.resolve(relativePath).toString().replace(File.separatorChar, '/');
	}
}
