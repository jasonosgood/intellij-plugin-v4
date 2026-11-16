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

	private File grammar(String name, String text) throws Exception {
		File file = new File(temp.getRoot(), name);
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
