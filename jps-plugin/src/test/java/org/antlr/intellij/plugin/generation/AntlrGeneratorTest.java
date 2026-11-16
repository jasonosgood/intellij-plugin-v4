package org.antlr.intellij.plugin.generation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AntlrGeneratorTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void generatesARecognizerAndSaysNothing() throws Exception {
		File grammar = grammar("Hello.g4", "grammar Hello;\ngreeting : 'hello' ID ;\nID : [a-z]+ ;\n");
		File out = temp.newFolder("out");

		AntlrGenerator.Result result = AntlrGenerator.run(Arrays.asList(
			"-o", out.getPath(), "-package", "demo", "-no-listener", grammar.getPath()));

		assertEquals(Collections.emptyList(), result.messages);
		assertNull(result.failure);
		assertFalse(result.hasErrors());
		assertTrue(new File(out, "HelloParser.java").exists());
		assertTrue(new File(out, "HelloLexer.java").exists());
		assertTrue(new File(out, "Hello.tokens").exists());
		assertFalse(new File(out, "HelloListener.java").exists());
	}

	@Test
	public void reportsAGrammarErrorWithItsPosition() throws Exception {
		File grammar = grammar("Broken.g4", "grammar Broken;\nstart : missing ;\n");
		File out = temp.newFolder("out");

		AntlrGenerator.Result result = AntlrGenerator.run(Arrays.asList("-o", out.getPath(), grammar.getPath()));

		assertTrue(result.hasErrors());
		assertNull(result.failure);
		assertEquals(result.messages.toString(), 1, result.messages.size());
		ToolMessage error = result.messages.get(0);
		assertEquals(ToolMessage.Kind.ERROR, error.kind);
		assertEquals(grammar.getPath(), error.file);
		assertEquals(2, error.line);
		assertEquals(8, error.column);
		assertTrue(error.text, error.text.contains("reference to undefined rule: missing"));
		assertFalse("no recognizer from a broken grammar", new File(out, "BrokenParser.java").exists());
	}

	private static final String LEXER = "lexer grammar L;\nA : 'a' ;\nB : 'b' ;\n";
	private static final String PARSER = "parser grammar P;\noptions { tokenVocab=L; }\ns : A B ;\n";

	/** A .tokens file beside its lexer grammar is output of an earlier run, and ANTLR reads it
	 *  (from -lib) instead of the one it generates now. */
	@Test
	public void warnsAboutAStrayTokensFile() throws Exception {
		File lexer = grammar("L.g4", LEXER);
		File parser = grammar("P.g4", PARSER);
		grammar("L.tokens", "A=1\n");
		File out = temp.newFolder("out");

		AntlrGenerator.Result result = AntlrGenerator.run(Arrays.asList(
			"-o", out.getPath(), "-lib", temp.getRoot().getPath(), lexer.getPath(), parser.getPath()));

		// Ours, then the hazard itself: ANTLR read the stale file, which lacks B.
		assertEquals(result.messages.toString(), 2, result.messages.size());
		ToolMessage warning = result.messages.get(0);
		assertEquals(ToolMessage.Kind.WARNING, warning.kind);
		assertEquals(parser.getPath(), warning.file);
		assertEquals(2, warning.line);
		assertEquals(21, warning.column);
		assertTrue(warning.summary, warning.summary.contains("L.tokens"));
		assertTrue(result.messages.get(1).text, result.messages.get(1).text.contains("implicit definition of token B"));
		assertFalse(result.hasErrors());
	}

	/** A .tokens file without its lexer grammar is a vocabulary supplied on purpose. */
	@Test
	public void suppliedTokensFileIsNotStray() throws Exception {
		File parser = grammar("P.g4", PARSER);
		grammar("L.tokens", "A=1\nB=2\n");
		File out = temp.newFolder("out");

		AntlrGenerator.Result result = AntlrGenerator.run(Arrays.asList(
			"-o", out.getPath(), "-lib", temp.getRoot().getPath(), parser.getPath()));

		assertEquals(Collections.emptyList(), result.messages);
	}

	/** Generating beside the grammars: the .tokens file there is the generated one. */
	@Test
	public void tokensFileInTheOutputDirectoryIsNotStray() throws Exception {
		File lexer = grammar("L.g4", LEXER);
		File parser = grammar("P.g4", PARSER);
		String dir = temp.getRoot().getPath();

		AntlrGenerator.Result first = AntlrGenerator.run(Arrays.asList("-o", dir, "-lib", dir, lexer.getPath(), parser.getPath()));
		assertEquals(Collections.emptyList(), first.messages);
		AntlrGenerator.Result again = AntlrGenerator.run(Arrays.asList("-o", dir, "-lib", dir, lexer.getPath(), parser.getPath()));
		assertEquals(Collections.emptyList(), again.messages);
	}

	private File grammar(String name, String text) throws Exception {
		File file = new File(temp.getRoot(), name);
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
