package org.antlr.intellij.plugin.xl;

import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.junit.Test;
import xl.GrammarSyntaxException;
import xl.Linter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

/**
 * The plugin (ANTLRv4Parser.g4, behind the editor) and antlr4-xl (ANTLR4.g4, behind Format, Sort
 * and lint) parse grammars with different meta-grammars, deliberately kept separate (see the
 * worklog, item 10). This guards against them drifting apart: every grammar in the test corpus
 * must be accepted by both or rejected by both. Set GRAMMARS_V4 to a grammars-v4 checkout to
 * check that corpus too.
 */
public class MetaGrammarAgreementTest {
	/** Known differences, by file name. ANTLR itself rejects these (in semantic checks). */
	private static final Set<String> KNOWN = Set.of(
		"LexerElementLabel.g4" // a label in a lexer rule: xl's grammar has no syntax for it
	);

	@Test
	public void bothParsersAcceptTheSameGrammars() throws IOException {
		List<String> disagreements = new ArrayList<>();
		List<Path> dirs = new ArrayList<>(List.of(Path.of("src/test/resources"), Path.of("antlr4-xl/test")));
		String grammarsV4 = System.getenv("GRAMMARS_V4");
		if ( grammarsV4!=null ) dirs.add(Path.of(grammarsV4));
		for (Path dir : dirs) {
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".g4")).sorted().toList()) {
					String text = Files.readString(file);
					boolean plugin = ParsingUtils.parseANTLRGrammar(text).syntaxErrorListener.getSyntaxErrors().isEmpty();
					boolean xl = xlAccepts(text);
					if ( plugin!=xl && !KNOWN.contains(file.getFileName().toString()) ) {
						disagreements.add(file + ": plugin " + (plugin ? "accepts" : "rejects") +
						                  ", antlr4-xl " + (xl ? "accepts" : "rejects"));
					}
				}
			}
		}
		assertEquals("", String.join("\n", disagreements));
	}

	private static boolean xlAccepts(String text) {
		try {
			new Linter().lint(text);
			return true;
		}
		catch (GrammarSyntaxException e) {
			return false;
		}
	}
}
