package org.antlr.intellij.plugin.generation;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Where a grammar sits in its project: the facts {@link GenerationArgs} needs that the IDE
 * and the build process each find their own way (the IDE from its project model and PSI, the
 * build from the JPS model).
 */
public final class GrammarPlace {
	/** The directory holding the grammar. */
	public final String grammarDir;
	/** The content root the grammar is under; relative directories in the settings start here. */
	public final String contentRoot;
	/** The package to generate into when the settings name none: the package of the grammar's
	 *  directory, or null or "" for none (also when the grammar's {@code @header} declares one
	 *  itself). Asked only when the settings name no package.
	 */
	public final Supplier<String> inferredPackage;
	/** Expands path macros such as {@code $PROJECT_DIR$}. */
	public final UnaryOperator<String> expandPath;

	public GrammarPlace(String grammarDir,
						String contentRoot,
						Supplier<String> inferredPackage,
						UnaryOperator<String> expandPath)
	{
		this.grammarDir = grammarDir;
		this.contentRoot = contentRoot;
		this.inferredPackage = inferredPackage;
		this.expandPath = expandPath;
	}
}
