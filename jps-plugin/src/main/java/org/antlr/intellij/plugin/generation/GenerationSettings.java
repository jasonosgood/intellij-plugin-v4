package org.antlr.intellij.plugin.generation;

/**
 * One grammar's code generation settings, as plain data: what the Configure ANTLR dialog
 * stores, without the IDE classes that persist it. Empty or null means "not set".
 */
public final class GenerationSettings {
	public final String outputDir;
	public final String libDir;
	public final String encoding;
	public final String pkg;
	public final String language;
	public final boolean generateListener;
	public final boolean generateVisitor;

	public GenerationSettings(String outputDir,
							  String libDir,
							  String encoding,
							  String pkg,
							  String language,
							  boolean generateListener,
							  boolean generateVisitor)
	{
		this.outputDir = outputDir;
		this.libDir = libDir;
		this.encoding = encoding;
		this.pkg = pkg;
		this.language = language;
		this.generateListener = generateListener;
		this.generateVisitor = generateVisitor;
	}
}
