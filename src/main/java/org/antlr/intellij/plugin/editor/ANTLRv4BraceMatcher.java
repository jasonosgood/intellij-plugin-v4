package org.antlr.intellij.plugin.editor;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.antlr.intellij.plugin.ANTLRv4TokenTypes.getTokenElementType;
import static org.antlr.intellij.plugin.parser.ANTLRv4Lexer.*;

public class ANTLRv4BraceMatcher implements PairedBraceMatcher {

	/** Pairs of the highlighting lexer's tokens ({@code ANTLRv4HighlightingLexer}). Actions and
	 *  {@code [...]} are one token each there, so their brackets aren't matched.
	 */
	@NotNull
	@Override
	public BracePair[] getPairs() {
		return new BracePair[] {
				new BracePair(getTokenElementType(LPAREN), getTokenElementType(RPAREN), false),
				new BracePair(getTokenElementType(OPTIONS), getTokenElementType(RBRACE), true),
				new BracePair(getTokenElementType(TOKENS), getTokenElementType(RBRACE), true),
				new BracePair(getTokenElementType(CHANNELS), getTokenElementType(RBRACE), true),
				new BracePair(getTokenElementType(LT), getTokenElementType(GT), false),
		};
	}

	@Override
	public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType contextType) {
		return true;
	}

	@Override
	public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
		return openingBraceOffset;
	}
}
