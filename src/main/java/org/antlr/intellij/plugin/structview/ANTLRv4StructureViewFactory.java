package org.antlr.intellij.plugin.structview;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class ANTLRv4StructureViewFactory implements PsiStructureViewFactory {
	/**
	 * Fake a blank tree view with a warning, for a non-.g4 file mapped to this file type.
	 * Implements the core interface directly: {@code PsiTreeElementBase} lives in the
	 * {@code intellij.platform.structureView} module, which this plugin doesn't depend on.
	 */
	public static class DummyViewTreeElement implements StructureViewTreeElement, ItemPresentation {
		private final PsiElement element;

		public DummyViewTreeElement(PsiElement psiElement) {
			this.element = psiElement;
		}

		@Override
		public Object getValue() {
			return element;
		}

		@NotNull
		@Override
		public ItemPresentation getPresentation() {
			return this;
		}

		@Override
		public TreeElement @NotNull [] getChildren() {
			return EMPTY_ARRAY;
		}

		@Nullable
		@Override
		public String getPresentableText() {
			return "Sorry .g not supported (use .g4)";
		}

		@Nullable
		@Override
		public Icon getIcon(boolean unused) {
			return null;
		}
	}

	@Nullable
    @Override
    public StructureViewBuilder getStructureViewBuilder(final PsiFile psiFile) {
        return new TreeBasedStructureViewBuilder() {
			@NotNull
			@Override
			public StructureViewModel createStructureViewModel(@Nullable Editor editor) {
				VirtualFile grammarFile = psiFile.getVirtualFile();
				if ( grammarFile==null || !grammarFile.getName().endsWith(".g4") ) {
					return new StructureViewModelBase(psiFile, new DummyViewTreeElement(psiFile));
				}
                return new ANTLRv4StructureViewModel((ANTLRv4FileRoot)psiFile);
			}
        };
    }
}
