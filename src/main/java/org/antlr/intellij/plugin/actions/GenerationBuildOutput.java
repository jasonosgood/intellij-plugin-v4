package org.antlr.intellij.plugin.actions;

import com.intellij.build.BuildProgressListener;
import com.intellij.build.DefaultBuildDescriptor;
import com.intellij.build.FilePosition;
import com.intellij.build.events.EventResult;
import com.intellij.build.events.FinishBuildEvent;
import com.intellij.build.events.MessageEvent;
import com.intellij.build.events.StartBuildEvent;
import com.intellij.build.events.impl.FailureResultImpl;
import com.intellij.build.events.impl.SuccessResultImpl;
import com.intellij.openapi.util.text.StringUtil;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Shows a Generate ANTLR Recognizer run in the Build tool window: one entry per run, with
 * ANTLR's messages under it, each going to its place in the grammar. The window opens by
 * itself only when the run fails (the platform's default for a build).
 * <p>
 * Prior art: actions that run a Gradle or Maven task report in the Build tool window, and the
 * IDE's build shows the JPS builder's ANTLR messages there the same way (summary as the title,
 * the file position to go to). Uses the event builders of {@code com.intellij.build.events},
 * which the platform marks experimental; the alternatives (event implementations,
 * {@code BuildEvents}) are internal.
 */
final class GenerationBuildOutput {
	private GenerationBuildOutput() {
	}

	/**
	 * @param view        where the events go: the project's {@code BuildViewManager}, or a test's listener
	 * @param workingDir  the project's directory, against which the view shows relative paths
	 * @param grammarName the grammar's file name
	 * @param outputDir   where the files went
	 * @param startTime   when the run started, in milliseconds since the epoch
	 * @param messages    what the tool said, in order
	 * @param failure     what the tool threw, or null
	 * @param report      the run's outcome, as the action reports it
	 */
	static void report(BuildProgressListener view, String workingDir, String grammarName, String outputDir,
					   long startTime, List<ToolMessage> messages, @Nullable Throwable failure,
					   GenerationReport report) {
		Object id = new Object();
		DefaultBuildDescriptor descriptor =
			new DefaultBuildDescriptor(id, "Generate "+grammarName, workingDir, startTime);
		view.onEvent(id, StartBuildEvent.builder("Generating "+grammarName, descriptor).build());
		for (ToolMessage message : messages) {
			view.onEvent(id, event(id, message));
		}
		if ( failure!=null ) {
			view.onEvent(id, MessageEvent.builder("ANTLR tool failed: "+failure, MessageEvent.Kind.ERROR)
				.withParentId(id)
				.withDescription(StringUtil.getThrowableText(failure))
				.build());
		}
		boolean failed = report.severity==GenerationReport.Severity.ERROR;
		if ( !failed ) {
			view.onEvent(id, MessageEvent.builder("Output directory: "+outputDir, MessageEvent.Kind.INFO)
				.withParentId(id)
				.build());
		}
		EventResult outcome = failed ? new FailureResultImpl() : new SuccessResultImpl();
		view.onEvent(id, FinishBuildEvent.builder(id, report.title, outcome)
			.withTime(System.currentTimeMillis())
			.build());
	}

	private static MessageEvent event(Object parentId, ToolMessage message) {
		MessageEvent.Kind kind = switch ( message.kind ) {
			case ERROR -> MessageEvent.Kind.ERROR;
			case WARNING -> MessageEvent.Kind.WARNING;
			case INFO -> MessageEvent.Kind.INFO;
		};
		var builder = MessageEvent.builder(message.summary, kind)
			.withParentId(parentId)
			.withDescription(message.text);
		if ( message.file!=null ) {
			// The view counts lines and columns from 0; ANTLR counts lines from 1.
			Path file = Path.of(message.file);
			FilePosition position = message.line>0
				? new FilePosition(file, message.line-1, Math.max(message.column, 0))
				: new FilePosition(file, 0, 0);
			builder = builder.withFilePosition(position);
		}
		return builder.build();
	}
}
