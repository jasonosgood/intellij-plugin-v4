package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.parsing.LexerDfaCache;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Speed baseline for the preview on antlr4-xl's curated {@code grammar/refactorme.g4}
 * (declared as {@code oracle_normal}): grammar load (ANTLR's full analysis), the first parse (cold
 * prediction cache) and a second (warm, see {@code ParserDfaCache}), then lexing alone: an
 * empty input (the cost of making the lexer, see {@code LexerDfaCache}) and a large one, twice
 * each. Prints timings; asserts nothing about them. Off by default; run with {@code ANTLR_PERF=1}:
 * {@code ANTLR_PERF=1 ./gradlew :test --rerun --tests '*PreviewPerformanceTest'}.
 * The fixture is copied to {@code oracle_normal.g4} outside the timed section because ANTLR
 * requires the file name to match its grammar declaration.
 */
public class PreviewPerformanceTest extends AntlrFixtureTestCase {
	private static final String GRAMMAR = "antlr4-xl/grammar/refactorme.g4";
	private static final String INPUT = "SELECT a, b FROM t WHERE a CONNECT BY a GROUP BY a WINDOW w AS () QUALIFY a";
	private static final int RUNS = 3;

	public void testOracleLoadAndParseTimes() throws IOException {
		if ( System.getenv("ANTLR_PERF")==null ) return;
		File file = new File(GRAMMAR).getAbsoluteFile();
		assertTrue(GRAMMAR + " not found (antlr4-xl submodule checked out?)", file.isFile());
		Path temp = Files.createTempDirectory(Files.createDirectories(Path.of("tmp")), "preview-perf-");
		try {
			File copy = Files.copy(file.toPath(), temp.resolve("oracle_normal.g4")).toFile();
			VirtualFile vfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(copy);
			assertNotNull("Could not find temporary grammar " + copy, vfile);
			timePreview(vfile);
		}
		finally {
			assertTrue(FileUtil.delete(temp.toFile()));
		}
	}

	private void timePreview(VirtualFile vfile) {
		for (int run = 1; run<=RUNS; run++) {
			long t0 = System.nanoTime();
			Grammar[] grammars = ParsingUtils.loadGrammars(vfile, getProject());
			long t1 = System.nanoTime();
			assertNotNull(grammars);
			LexerGrammar lg = (LexerGrammar) grammars[0];
			Grammar g = grammars[1];
			ParsingResult result = ParsingUtils.parseText(g, lg, "start", vfile, INPUT, getProject());
			long t2 = System.nanoTime();
			assertNotNull(result);
			assertEquals(result.syntaxErrorListener.getSyntaxErrors().stream().map(InputPanel::getErrorDisplayString).toList().toString(),
				0, result.syntaxErrorListener.getSyntaxErrors().size());
			assertNotNull(ParsingUtils.parseText(g, lg, "start", vfile, INPUT, getProject()));
			long t3 = System.nanoTime();
			System.out.printf("PERF refactorme.g4 (oracle_normal) run %d: load %d ms, parse cold %d ms, warm %d ms (%d rules, %d syntax errors)%n",
				run, (t1 - t0)/1_000_000, (t2 - t1)/1_000_000, (t3 - t2)/1_000_000, g.rules.size(),
				result.syntaxErrorListener.getSyntaxErrors().size());
			// lexing alone: an empty input (making the interpreter) and a large one, twice each
			String large = (INPUT + ";\n").repeat(LARGE_LINES);
			for (String[] input : new String[][] {{"empty", ""}, {LARGE_LINES + " lines", large}}) {
				for (int i = 1; i<=2; i++) {
					long l0 = System.nanoTime();
					assertNotNull(ParsingUtils.lexText(lg, vfile, input[1], getProject()));
					System.out.printf("PERF refactorme.g4 (oracle_normal) run %d: lex %s #%d %d ms%n",
						run, input[0], i, (System.nanoTime() - l0)/1_000_000);
				}
			}
			long uncached = 0, cached = 0;
			for (int i = 0; i<10; i++) {
				long start = System.nanoTime();
				assertNotNull(lg.createLexerInterpreter(CharStreams.fromString("")));
				uncached += System.nanoTime() - start;
				start = System.nanoTime();
				assertNotNull(LexerDfaCache.newLexer(lg, CharStreams.fromString("")));
				cached += System.nanoTime() - start;
			}
			System.out.printf("PERF refactorme.g4 (oracle_normal) run %d: lexer construction uncached %.2f ms, cached %.2f ms (mean of 10)%n",
				run, uncached/10_000_000.0, cached/10_000_000.0);
		}
	}

	private static final int LARGE_LINES = 5000;
}
