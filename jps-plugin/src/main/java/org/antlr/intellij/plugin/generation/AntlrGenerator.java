package org.antlr.intellij.plugin.generation;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the ANTLR tool to generate recognizers and returns what it said as data. Each run
 * makes its own {@link Tool} and keeps no state, so runs on different threads don't share
 * anything (the build process may build modules in parallel).
 */
public final class AntlrGenerator {
	/** What a run produced besides files. */
	public static final class Result {
		/** In the order the tool reported them. */
		public final List<ToolMessage> messages;
		/** What the tool threw, or null. Its errors in grammars are messages, not this. */
		public final Throwable failure;

		Result(List<ToolMessage> messages, Throwable failure) {
			this.messages = Collections.unmodifiableList(messages);
			this.failure = failure;
		}

		public boolean hasErrors() {
			if ( failure!=null ) return true;
			for (ToolMessage message : messages) {
				if ( message.kind==ToolMessage.Kind.ERROR ) return true;
			}
			return false;
		}
	}

	private AntlrGenerator() {
	}

	/** @param args the tool's command line: options, then grammar files */
	public static Result run(List<String> args) {
		Tool antlr = new Tool(args.toArray(new String[0]));
		List<ToolMessage> messages = new ArrayList<>();
		antlr.removeListeners();
		antlr.addListener(new ANTLRToolListener() {
			@Override
			public void info(String msg) {
				messages.add(new ToolMessage(ToolMessage.Kind.INFO, oneLine(antlr, msg), null, -1, -1));
			}

			@Override
			public void error(ANTLRMessage msg) {
				messages.add(message(antlr, ToolMessage.Kind.ERROR, msg));
			}

			@Override
			public void warning(ANTLRMessage msg) {
				messages.add(message(antlr, ToolMessage.Kind.WARNING, msg));
			}
		});

		Throwable failure = null;
		try {
			antlr.processGrammarsOnCommandLine();
		}
		catch (Throwable e) { // the tool can fail in many ways on a broken grammar; report them all
			failure = e;
		}
		return new Result(messages, failure);
	}

	private static ToolMessage message(Tool antlr, ToolMessage.Kind kind, ANTLRMessage msg) {
		String text = oneLine(antlr, antlr.errMgr.getMessageTemplate(msg).render());
		return new ToolMessage(kind, text, msg.fileName, msg.line, msg.charPosition);
	}

	private static String oneLine(Tool antlr, String text) {
		return antlr.errMgr.formatWantsSingleLineMessage() ? text.replace('\n', ' ') : text;
	}
}
