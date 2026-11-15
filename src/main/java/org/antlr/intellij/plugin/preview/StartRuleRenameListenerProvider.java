package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.refactoring.listeners.RefactoringElementAdapter;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider;
import org.antlr.intellij.plugin.psi.ParserRuleSpecNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Renaming a grammar's start rule renames the preview's start rule too, and undoing the
 *  rename undoes that. The preview switches to the new name when the grammar is saved and
 *  reloaded ({@link PreviewState#withGrammars}).
 *  <p>
 *  Prior art: run configurations follow a renamed class or method through this extension
 *  point ({@code RefactoringListenerProvider} on run configurations).
 */
public class StartRuleRenameListenerProvider implements RefactoringElementListenerProvider {
	@Override
	public @Nullable RefactoringElementListener getListener(PsiElement element) {
		if ( !(element instanceof ParserRuleSpecNode rule) ) return null;
		PsiFile psiFile = rule.getContainingFile();
		VirtualFile file = psiFile==null ? null : psiFile.getVirtualFile();
		if ( file==null ) return null;
		// Only if the preview is in use; don't create the service for a rename
		PreviewService preview = element.getProject().getServiceIfCreated(PreviewService.class);
		if ( preview==null ) return null;
		String oldName = rule.getName();
		if ( oldName==null || !preview.getPreviewState(file).isStartRule(oldName) ) return null;

		return new RefactoringElementAdapter() {
			@Override
			protected void elementRenamedOrMoved(@NotNull PsiElement newElement) {
				if ( newElement instanceof PsiNamedElement named && named.getName()!=null ) {
					preview.startRuleRenamed(file, named.getName());
				}
			}

			@Override
			public void undoElementMovedOrRenamed(@NotNull PsiElement newElement, @NotNull String oldQualifiedName) {
				preview.startRuleRenamed(file, oldName);
			}
		};
	}
}
