package org.antlr.intellij.plugin.actions;

import org.antlr.intellij.plugin.generation.ToolMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class GenerationReportTest {
	private static ToolMessage message(ToolMessage.Kind kind, String file, int line, String summary) {
		return new ToolMessage(kind, kind+"(0): "+file+":"+line+":0: "+summary, summary, file, line, 0);
	}

	@Test
	public void success() {
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen", List.of(), null);
		assertEquals(GenerationReport.Severity.INFO, report.severity);
		assertEquals("Generated P.g4", report.title);
		assertEquals("to /p/gen", report.content);
	}

	/** The bug this replaces: "parser for X generated" though the tool reported errors. */
	@Test
	public void errorsAreAFailure() {
		ToolMessage error = message(ToolMessage.Kind.ERROR, "/p/L.g4", 3, "token <A> is bad");
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen",
			List.of(message(ToolMessage.Kind.WARNING, "/p/P.g4", 1, "w"), error), null);
		assertEquals(GenerationReport.Severity.ERROR, report.severity);
		assertEquals("Couldn't generate P.g4: 1 error", report.title);
		assertEquals("L.g4:3: token &lt;A&gt; is bad", report.content); // escaped: notifications take HTML
		assertSame(error, report.firstError);
	}

	@Test
	public void manyErrorsAreCounted() {
		List<ToolMessage> errors = new ArrayList<>();
		for (int i = 1; i<=GenerationReport.MAX_LISTED+2; i++) {
			errors.add(message(ToolMessage.Kind.ERROR, "/p/P.g4", i, "e"+i));
		}
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen", errors, null);
		assertEquals("Couldn't generate P.g4: 7 errors", report.title);
		assertEquals("P.g4:1: e1<br>P.g4:2: e2<br>P.g4:3: e3<br>P.g4:4: e4<br>P.g4:5: e5<br>and 2 more", report.content);
	}

	@Test
	public void warningsStillGenerate() {
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen",
			List.of(message(ToolMessage.Kind.WARNING, "/p/P.g4", 2, "unused")), null);
		assertEquals(GenerationReport.Severity.WARNING, report.severity);
		assertEquals("Generated P.g4 with 1 warning", report.title);
		assertEquals("to /p/gen<br>P.g4:2: unused", report.content);
	}

	@Test
	public void toolFailure() {
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen", List.of(),
			new IllegalStateException("boom"));
		assertEquals(GenerationReport.Severity.ERROR, report.severity);
		assertEquals("Couldn't generate P.g4", report.title);
		assertEquals("java.lang.IllegalStateException: boom", report.content);
		assertNull(report.firstError);
	}
}
