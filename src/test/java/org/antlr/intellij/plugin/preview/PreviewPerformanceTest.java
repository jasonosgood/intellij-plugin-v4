package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;

import java.io.File;

/**
 * Speed baseline for the preview on the largest grammar known, antlr4-xl's
 * {@code test/oracle_normal.g4}: grammar load (ANTLR's full analysis) and one parse. Prints
 * timings; asserts nothing about them. Off by default; run with {@code ANTLR_PERF=1}:
 * {@code ANTLR_PERF=1 ./gradlew test --tests '*PreviewPerformanceTest'}.
 */
public class PreviewPerformanceTest extends AntlrFixtureTestCase {
	private static final String GRAMMAR = "antlr4-xl/test/oracle_normal.g4";
	private static final String INPUT = "SELECT a, b FROM t WHERE a = 1";
	private static final int RUNS = 3;

	public void testOracleLoadAndParseTimes() {
		if ( System.getenv("ANTLR_PERF")==null ) return;
		File file = new File(GRAMMAR).getAbsoluteFile();
		VirtualFile vfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
		assertNotNull(GRAMMAR + " not found (antlr4-xl submodule checked out?)", vfile);
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
			System.out.printf("PERF oracle_normal run %d: load %d ms, parse %d ms (%d rules, %d syntax errors)%n",
				run, (t1 - t0)/1_000_000, (t2 - t1)/1_000_000, g.rules.size(),
				result.syntaxErrorListener.getSyntaxErrors().size());
		}
	}
}
