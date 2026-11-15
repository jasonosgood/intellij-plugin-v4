package org.antlr.intellij.plugin;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Output for the "Tool Output" tool window. Anyone can print at any time, from any thread;
 * until the tool window's console exists, output is buffered and replayed into it when it
 * attaches. The console view belongs to the tool window ({@link ANTLRv4ToolWindowFactory}),
 * not to this object, so nothing here outlives a tool window or needs disposing.
 * <p>
 * Prior art: the platform's Build tool window keeps events in a model that views attach to;
 * the buffer is capped like a console's cycle buffer (1 MB by default).
 */
public final class ToolOutput {
	static final int MAX_BUFFERED_CHARS = 1024*1024;

	private record Chunk(String text, ConsoleViewContentType type) {
	}

	private final ArrayDeque<Chunk> buffer = new ArrayDeque<>();
	private int bufferedChars;
	private @Nullable ConsoleView view;

	public synchronized void print(@NotNull String text, @NotNull ConsoleViewContentType type) {
		if ( view!=null ) {
			view.print(text, type); // thread-safe; the console defers to the EDT itself
			return;
		}
		buffer.addLast(new Chunk(text, type));
		bufferedChars += text.length();
		while ( bufferedChars>MAX_BUFFERED_CHARS && buffer.size()>1 ) {
			bufferedChars -= buffer.removeFirst().text().length();
		}
	}

	/** Shows output in {@code console}, starting with what was printed before it existed. */
	public synchronized void attach(@NotNull ConsoleView console) {
		for (Chunk c : buffer) {
			console.print(c.text(), c.type());
		}
		buffer.clear();
		bufferedChars = 0;
		view = console;
	}

	/** Stops printing to {@code console}; called before it is disposed. */
	public synchronized void detach(@NotNull ConsoleView console) {
		if ( view==console ) view = null;
	}
}
