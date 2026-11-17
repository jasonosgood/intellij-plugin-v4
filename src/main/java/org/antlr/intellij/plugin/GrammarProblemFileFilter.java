package org.antlr.intellij.plugin;

import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;

/** Opts grammar files into the platform's tracking of files with errors
 *  ({@code WolfTheProblemSolver}): their errors underline them in the Project view, and the
 *  preview pauses while a grammar it uses has any ({@code PreviewService#getPause}).
 *  Grammars in libraries are left out, as their errors aren't the user's to fix.
 *  <p>
 *  Prior art: the Java plugin's {@code DefaultProblemFileHighlightFilter} and the LSP API's
 *  {@code LspProblemFileHighlightFilter} register the same extension point.
 */
public final class GrammarProblemFileFilter implements Condition<VirtualFile> {
	private final Project project;

	public GrammarProblemFileFilter(Project project) {
		this.project = project;
	}

	@Override
	public boolean value(VirtualFile file) {
		return FileTypeRegistry.getInstance().isFileOfType(file, ANTLRv4FileType.INSTANCE)
			&& !ProjectFileIndex.getInstance(project).isInLibrary(file);
	}
}
