package org.antlr.intellij.plugin.configdialogs;

import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins down the ANTLR tool arguments that a grammar's settings produce, as they were before
 * the generation core was extracted (doc/B2_JPS_BUILDER.md, step 1). Each test uses its own
 * grammar file: the light fixture's project, and so its settings store, is shared by all tests.
 */
public class GenerationArgsTest extends AntlrFixtureTestCase {

	public void testDefaults() {
		VirtualFile grammar = addGrammar("mypkg/Defaults.g4", "grammar Defaults; A : 'a' ;");
		String root = contentRoot(grammar);

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("-package", "mypkg");
		expected.put("-o", root+"/gen/mypkg");
		expected.put("-lib", root+"/mypkg");
		expected.put("-listener", "");
		expected.put("-visitor", "");
		assertEquals(expected, args(grammar));
	}

	public void testEverySetting() {
		VirtualFile grammar = addGrammar("mypkg/Every.g4", "grammar Every; A : 'a' ;");
		String root = contentRoot(grammar);
		ANTLRv4GrammarProperties settings = settingsOf(grammar);
		settings.outputDir = "out/antlr";
		settings.libDir = "lib";
		settings.pkg = "com.x";
		settings.language = "Python3";
		settings.encoding = "UTF-8";
		settings.generateListener = false;
		settings.generateVisitor = false;

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("-package", "com.x");
		expected.put("-Dlanguage=Python3", "");
		expected.put("-o", root+"/out/antlr/com/x");
		expected.put("-lib", root+"/lib");
		expected.put("-encoding", "UTF-8");
		expected.put("-no-listener", "");
		expected.put("-no-visitor", "");
		assertEquals(expected, args(grammar));
	}

	public void testAbsoluteDirectoriesAndPackageFromHeader() {
		VirtualFile grammar = addGrammar("mypkg/Header.g4",
			"grammar Header;\n@header { package com.foo.bar; }\nA : 'a' ;");
		ANTLRv4GrammarProperties settings = settingsOf(grammar);
		settings.outputDir = "/abs/out";
		settings.libDir = "/abs/lib";

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("-o", "/abs/out"); // the header's package adds no subdirectory
		expected.put("-lib", "/abs/lib");
		expected.put("-listener", "");
		expected.put("-visitor", "");
		assertEquals(expected, args(grammar));
	}

	public void testGrammarInTheSourceRootHasNoPackage() {
		VirtualFile grammar = addGrammar("Top.g4", "grammar Top; A : 'a' ;");
		String root = contentRoot(grammar);

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("-o", root+"/gen");
		expected.put("-lib", root);
		expected.put("-listener", "");
		expected.put("-visitor", "");
		assertEquals(expected, args(grammar));
	}

	public void testPathMacrosAreExpanded() {
		VirtualFile grammar = addGrammar("mypkg/Macro.g4", "grammar Macro; A : 'a' ;");
		ANTLRv4GrammarProperties settings = settingsOf(grammar);
		settings.outputDir = "$PROJECT_DIR$/generated";
		settings.libDir = "$PROJECT_DIR$/vocab";

		Map<String, String> args = args(grammar);
		String projectDir = getProject().getBasePath();
		assertEquals(projectDir+"/generated/mypkg", args.get("-o"));
		assertEquals(projectDir+"/vocab", args.get("-lib"));
	}

	private VirtualFile addGrammar(String path, String text) {
		return myFixture.addFileToProject(path, text).getVirtualFile();
	}

	private ANTLRv4GrammarProperties settingsOf(VirtualFile grammar) {
		return ANTLRv4GrammarPropertiesStore.getOrCreateGrammarProperties(getProject(), grammar.getPath());
	}

	/** In the light fixture the content root is the grammar's source root. */
	private static String contentRoot(VirtualFile grammar) {
		String path = grammar.getPath();
		String relative = path.contains("/mypkg/") ? "/mypkg/"+grammar.getName() : "/"+grammar.getName();
		return path.substring(0, path.length()-relative.length());
	}

	/** Option to value ("" for a flag). The order of options is not part of the contract. */
	private Map<String, String> args(VirtualFile grammar) {
		List<String> list = RunANTLROnGrammarFile.getANTLRArgsAsList(getProject(), grammar);
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i<list.size(); i++) {
			boolean hasValue = i+1<list.size() && !list.get(i+1).startsWith("-");
			map.put(list.get(i), hasValue ? list.get(i+1) : "");
			if ( hasValue ) i++;
		}
		return map;
	}
}
