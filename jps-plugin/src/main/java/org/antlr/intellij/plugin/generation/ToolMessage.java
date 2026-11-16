package org.antlr.intellij.plugin.generation;

/** Something the ANTLR tool said during a run, as data: each caller shows it its own way. */
public final class ToolMessage {
	public enum Kind { INFO, WARNING, ERROR }

	public final Kind kind;
	/** The message as the tool prints it, on one line. */
	public final String text;
	/** The grammar file it is about, or null. */
	public final String file;
	/** 1-based, or -1 when the message has no position. */
	public final int line;
	/** 0-based, as ANTLR counts, or -1 when the message has no position. */
	public final int column;

	public ToolMessage(Kind kind, String text, String file, int line, int column) {
		this.kind = kind;
		this.text = text;
		this.file = file;
		this.line = line;
		this.column = column;
	}

	@Override
	public String toString() {
		return kind+": "+text;
	}
}
