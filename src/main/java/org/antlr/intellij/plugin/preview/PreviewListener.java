package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/** What happens to the preview, published by {@link PreviewService} on the project's message
 *  bus, always on the EDT. The preview panel subscribes with its own disposable; the service
 *  never calls the UI. Read the state with {@link PreviewService#getPreviewState}.
 *  <p>
 *  Prior art: the platform's services publish to project {@code Topic}s that UI subscribes to
 *  (e.g. {@code FileEditorManagerListener}, {@code ToolWindowManagerListener}).
 */
public interface PreviewListener {
	@Topic.ProjectLevel
	Topic<PreviewListener> TOPIC = new Topic<>("ANTLR preview", PreviewListener.class);

	/** {@code grammarFile} became the grammar the preview shows; its grammars are loaded. */
	default void grammarSelected(@NotNull VirtualFile grammarFile) {}

	/** {@code grammarFile} was saved and reloaded (or a grammar it uses was). */
	default void grammarReloaded(@NotNull VirtualFile grammarFile) {}

	/** The user chose a start rule for {@code grammarFile}. */
	default void startRuleChanged(@NotNull VirtualFile grammarFile) {}

	/** {@code grammarFile}'s tab closed; its preview state is gone. */
	default void grammarClosed(@NotNull VirtualFile grammarFile) {}

	/** No grammar is selected any more (its tab closed, or another kind of file came first). */
	default void grammarDeselected() {}

	/** Errors appeared in or went from a file the preview uses: see {@link PreviewService#getPause}. */
	default void pauseChanged() {}

	/** A file the preview uses was edited, saved or reverted: see {@link PreviewService#getUnsavedInput}. */
	default void unsavedChanged() {}

	/** A parse is taking long enough to offer cancelling it. */
	default void parseSlow() {}

	/** A parse finished; {@code state} is the snapshot it was made with, with its result. */
	default void parseCompleted(@NotNull PreviewState state, long nanos) {}

	/** The user cancelled the running parse. */
	default void parseCancelled() {}

	/** A grammar load or a parse started or ended: see {@link PreviewService#isLoading} and
	 *  {@link PreviewService#isParsing}.
	 */
	default void busyChanged() {}
}
