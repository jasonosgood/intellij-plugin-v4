package org.antlr.intellij.plugin.parsing;

import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;

import java.util.List;

public class RunANTLROnGrammarFileTest extends AntlrFixtureTestCase {

	public void testPackageOptionShouldNotBeAddedIfDeclaredInHeader() {
		VirtualFile file = myFixture.addFileToProject("mypkg/myGrammarWithHeader.g4",
				"grammar myGrammarWithHeader;\n@header { package com.foo.bar; }\nFOO: 'foo';").getVirtualFile();
		List<String> options = RunANTLROnGrammarFile.getANTLRArgsAsList(getProject(), file);

		assertFalse(options.contains("-package"));
	}

	public void testPackageOptionShouldBeAddedIfNotDeclaredInHeader() {
		VirtualFile file = myFixture.addFileToProject("mypkg/myGrammarWithoutHeader.g4",
				"grammar myGrammarWithoutHeader; FOO: 'foo';").getVirtualFile();
		List<String> options = RunANTLROnGrammarFile.getANTLRArgsAsList(getProject(), file);

		assertTrue(options.contains("-package"));
		assertTrue(options.contains("mypkg"));
	}

}