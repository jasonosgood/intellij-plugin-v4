package org.antlr.intellij.plugin.actions;

import com.intellij.build.FilePosition;
import com.intellij.build.events.BuildEvent;
import com.intellij.build.events.FailureResult;
import com.intellij.build.events.FileMessageEvent;
import com.intellij.build.events.FinishBuildEvent;
import com.intellij.build.events.MessageEvent;
import com.intellij.build.events.StartBuildEvent;
import com.intellij.build.events.SuccessResult;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.generation.ToolMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What a Generate run sends the Build tool window. A fixture test: the event builders are an
 *  application service. */
public class GenerationBuildOutputTest extends AntlrFixtureTestCase {
	private final List<BuildEvent> events = new ArrayList<>();
	private final List<Object> ids = new ArrayList<>();

	private void report(List<ToolMessage> messages, Throwable failure) {
		GenerationReport report = GenerationReport.of("P.g4", "/p/gen", messages, failure);
		GenerationBuildOutput.report((id, event) -> { ids.add(id); events.add(event); },
			"/p", "P.g4", "/p/gen", 1000L, messages, failure, report);
	}

	private static ToolMessage message(ToolMessage.Kind kind, String file, int line, int column, String summary) {
		return new ToolMessage(kind, kind+"(0): "+file+":"+line+":"+column+": "+summary, summary, file, line, column);
	}

	public void testErrorsGoToTheirPlaceAndFailTheRun() {
		report(List.of(message(ToolMessage.Kind.ERROR, "/p/L.g4", 3, 4, "token <A> is bad")), null);

		assertEquals(3, events.size());
		Object id = ids.get(0);
		for (Object each : ids) assertSame("one run, one id", id, each);

		StartBuildEvent start = (StartBuildEvent)events.get(0);
		assertEquals("Generate P.g4", start.getBuildDescriptor().getTitle());
		assertEquals("/p", start.getBuildDescriptor().getWorkingDir());

		FileMessageEvent error = (FileMessageEvent)events.get(1);
		assertEquals(MessageEvent.Kind.ERROR, error.getKind());
		assertEquals("token <A> is bad", error.getMessage());
		assertSame(id, error.getParentId());
		FilePosition position = error.getFilePosition();
		assertEquals(Path.of("/p/L.g4"), position.getPath());
		assertEquals("lines from 0", 2, position.getStartLine());
		assertEquals(4, position.getStartColumn());

		FinishBuildEvent finish = (FinishBuildEvent)events.get(2);
		assertEquals("Couldn't generate P.g4: 1 error", finish.getMessage());
		assertTrue(finish.getResult() instanceof FailureResult);
	}

	public void testSuccessSaysWhereTheFilesWent() {
		report(List.of(message(ToolMessage.Kind.WARNING, "/p/P.g4", 2, 0, "unused")), null);

		assertEquals(4, events.size());
		assertEquals(MessageEvent.Kind.WARNING, ((MessageEvent)events.get(1)).getKind());
		MessageEvent output = (MessageEvent)events.get(2);
		assertEquals(MessageEvent.Kind.INFO, output.getKind());
		assertEquals("Output directory: /p/gen", output.getMessage());
		FinishBuildEvent finish = (FinishBuildEvent)events.get(3);
		assertEquals("Generated P.g4 with 1 warning", finish.getMessage());
		assertTrue(finish.getResult() instanceof SuccessResult);
	}

	public void testToolFailureIsAnError() {
		report(List.of(), new IllegalStateException("boom"));

		assertEquals(3, events.size());
		MessageEvent error = (MessageEvent)events.get(1);
		assertEquals(MessageEvent.Kind.ERROR, error.getKind());
		assertEquals("ANTLR tool failed: java.lang.IllegalStateException: boom", error.getMessage());
		assertTrue(((FinishBuildEvent)events.get(2)).getResult() instanceof FailureResult);
	}
}
