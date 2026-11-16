package org.antlr.intellij.plugin.generation;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarAST;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
		/** The files written, by the grammar file (as given in the arguments) they came from. */
		public final Map<String, List<File>> outputs;

		Result(List<ToolMessage> messages, Throwable failure, Map<String, List<File>> outputs) {
			this.messages = Collections.unmodifiableList(messages);
			this.failure = failure;
			this.outputs = Collections.unmodifiableMap(outputs);
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

	/** The tool, noting each file it writes (it asks for every output's writer here) and
	 *  warning about a stray {@code .tokens} file before each grammar is processed. */
	private static final class RecordingTool extends Tool {
		final Map<String, List<File>> outputs = new LinkedHashMap<>();
		final List<ToolMessage> messages = new ArrayList<>();

		RecordingTool(String[] args) {
			super(args);
		}

		@Override
		public void process(Grammar g, boolean gencode) {
			ToolMessage stray = strayTokensFile(g, libDirectory, outputDirectory);
			if ( stray!=null ) messages.add(stray);
			super.process(g, gencode);
		}

		@Override
		public Writer getOutputFileWriter(Grammar g, String fileName) throws IOException {
			Writer writer = super.getOutputFileWriter(g, fileName);
			if ( outputDirectory!=null ) { // without one the tool writes to memory
				outputs.computeIfAbsent(g.fileName, grammar -> new ArrayList<>())
					.add(new File(getOutputDirectory(g.fileName), fileName));
			}
			return writer;
		}
	}

	/** @param args the tool's command line: options, then grammar files */
	public static Result run(List<String> args) {
		RecordingTool antlr = new RecordingTool(args.toArray(new String[0]));
		List<ToolMessage> messages = antlr.messages;
		antlr.removeListeners();
		antlr.addListener(new ANTLRToolListener() {
			@Override
			public void info(String msg) {
				messages.add(new ToolMessage(ToolMessage.Kind.INFO, oneLine(antlr, msg), msg, null, -1, -1));
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
		return new Result(messages, failure, antlr.outputs);
	}

	/** A warning when {@code g}'s {@code tokenVocab} will be read from a stray {@code .tokens}
	 *  file ({@link #strayTokensFile(String, String, String)}). The build can't ask, so it warns;
	 *  the Generate action asks before running. */
	static ToolMessage strayTokensFile(Grammar g, String libDirectory, String outputDirectory) {
		String vocab = g.getOptionString("tokenVocab");
		File tokens = strayTokensFile(vocab, libDirectory, outputDirectory);
		if ( tokens==null ) return null;
		GrammarAST option = g.ast==null ? null : g.ast.getOptionAST("tokenVocab");
		int line = option==null ? -1 : option.getLine();
		int column = option==null ? -1 : option.getCharPositionInLine();
		String summary = "tokens file "+tokens.getPath()+" is used instead of the one generated from "+
			vocab+".g4 and may be out of date; delete it";
		String text = "warning: "+g.fileName+(line>0 ? ":"+line+":"+column : "")+": "+summary;
		return new ToolMessage(ToolMessage.Kind.WARNING, text, summary, g.fileName, line, column);
	}

	/**
	 * The {@code .tokens} file a {@code tokenVocab} of {@code vocab} will be read from, when it is
	 * stray: in the {@code -lib} directory with the lexer grammar it comes from beside it, so it
	 * is output of an earlier run (an older plugin wrote them beside the grammars, as does a
	 * hand-run {@code antlr4}). ANTLR looks in {@code -lib} before the output directory, so that
	 * file shadows the one generated now and the recognizer gets its token types, which may be
	 * out of date. Null when there is none, or when {@code -lib} is the output directory (the
	 * file is the generated one). A {@code .tokens} file without its lexer grammar is a
	 * vocabulary supplied on purpose: null.
	 */
	public static File strayTokensFile(String vocab, String libDirectory, String outputDirectory) {
		if ( vocab==null || libDirectory==null ) return null;
		File lib = new File(libDirectory);
		File tokens = new File(lib, vocab+".tokens");
		if ( !tokens.isFile() || !new File(lib, vocab+".g4").isFile() ) return null;
		if ( outputDirectory!=null && lib.getAbsoluteFile().toPath().normalize()
			.equals(new File(outputDirectory).getAbsoluteFile().toPath().normalize()) ) {
			return null;
		}
		return tokens;
	}

	private static ToolMessage message(Tool antlr, ToolMessage.Kind kind, ANTLRMessage msg) {
		String text = oneLine(antlr, antlr.errMgr.getMessageTemplate(msg).render());
		String summary = msg.getMessageTemplate(false).render();
		return new ToolMessage(kind, text, summary, msg.fileName, msg.line, msg.charPosition);
	}

	private static String oneLine(Tool antlr, String text) {
		return antlr.errMgr.formatWantsSingleLineMessage() ? text.replace('\n', ' ') : text;
	}
}
