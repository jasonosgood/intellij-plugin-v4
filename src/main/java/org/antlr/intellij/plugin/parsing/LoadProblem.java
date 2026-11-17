package org.antlr.intellij.plugin.parsing;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Why the preview can't show a grammar: a reason for its banner, in a sentence, and the file
 *  to fix, if there is one. The errors themselves are the editor's to show (and the build's);
 *  the preview only points to them.
 */
public record LoadProblem(@NotNull String reason, @Nullable VirtualFile file) {
	/** {@code file} has errors, as the editor or ANTLR reports them. */
	public static @NotNull LoadProblem errorsIn(@NotNull VirtualFile file) {
		return new LoadProblem("Errors in " + file.getName() + ".", file);
	}
}
