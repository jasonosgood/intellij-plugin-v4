package org.antlr.intellij.plugin.actions;

import com.intellij.openapi.util.text.StringUtil;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What Generate ANTLR Recognizer tells the user after a run: its title (the Build tool window's
 * outcome, and the status bar's or the balloon's title), its severity, and for a failure the
 * balloon's text (HTML, as notifications take it) and where the first error is. Errors in the
 * grammar are also editor annotations and Build tool window entries
 * ({@link GenerationBuildOutput}).
 * <p>
 * Prior art: actions running a Gradle or Maven task report in the Build tool window and raise
 * a balloon only on failure.
 */
public final class GenerationReport {
	public enum Severity { INFO, WARNING, ERROR }

	/** Messages listed in the notification; the rest are counted. */
	static final int MAX_LISTED = 5;

	public final Severity severity;
	public final String title;
	public final String content;
	/** The first error with a position, to go to from the notification; null when there is none. */
	@Nullable public final ToolMessage firstError;

	private GenerationReport(Severity severity, String title, String content, @Nullable ToolMessage firstError) {
		this.severity = severity;
		this.title = title;
		this.content = content;
		this.firstError = firstError;
	}

	/**
	 * @param grammarName the grammar's file name
	 * @param outputDir   where the files went, as shown to the user
	 * @param messages    what the tool said, in order
	 * @param failure     what the tool threw, or null
	 */
	public static GenerationReport of(String grammarName, String outputDir, List<ToolMessage> messages,
									  @Nullable Throwable failure) {
		if ( failure!=null ) {
			return new GenerationReport(Severity.ERROR, "Couldn't generate "+grammarName,
										StringUtil.escapeXmlEntities(failure.toString()), null);
		}
		List<ToolMessage> errors = ofKind(messages, ToolMessage.Kind.ERROR);
		if ( !errors.isEmpty() ) {
			ToolMessage first = null;
			for (ToolMessage error : errors) {
				if ( error.file!=null && error.line>0 ) {
					first = error;
					break;
				}
			}
			return new GenerationReport(Severity.ERROR,
				"Couldn't generate "+grammarName+": "+count(errors.size(), "error"), list(errors), first);
		}
		String to = "to "+StringUtil.escapeXmlEntities(outputDir);
		List<ToolMessage> warnings = ofKind(messages, ToolMessage.Kind.WARNING);
		if ( !warnings.isEmpty() ) {
			return new GenerationReport(Severity.WARNING,
				"Generated "+grammarName+" with "+count(warnings.size(), "warning"), to+"<br>"+list(warnings), null);
		}
		return new GenerationReport(Severity.INFO, "Generated "+grammarName, to, null);
	}

	private static List<ToolMessage> ofKind(List<ToolMessage> messages, ToolMessage.Kind kind) {
		List<ToolMessage> result = new ArrayList<>();
		for (ToolMessage message : messages) {
			if ( message.kind==kind ) result.add(message);
		}
		return result;
	}

	/** One line per message, "File.g4:3: summary", then how many more. */
	private static String list(List<ToolMessage> messages) {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i<messages.size() && i<MAX_LISTED; i++) {
			ToolMessage message = messages.get(i);
			String where = message.file==null ? ""
				: new File(message.file).getName()+(message.line>0 ? ":"+message.line : "")+": ";
			lines.add(StringUtil.escapeXmlEntities(where+message.summary));
		}
		if ( messages.size()>MAX_LISTED ) lines.add("and "+(messages.size()-MAX_LISTED)+" more");
		return String.join("<br>", lines);
	}

	private static String count(int n, String noun) {
		return n+" "+noun+(n==1 ? "" : "s");
	}
}
