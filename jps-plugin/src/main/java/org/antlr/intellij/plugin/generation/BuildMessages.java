package org.antlr.intellij.plugin.generation;

/**
 * What the JPS builder tells the IDE beyond the messages shown in the Build tool window. The
 * build process sends these as custom builder messages; the IDE listens for them. Both sides
 * load this class, so it uses neither's API.
 */
public final class BuildMessages {
	/** Identifies the builder as the sender. */
	public static final String BUILDER_ID = "antlr4";

	/**
	 * Grammars were skipped because their output directory isn't under a source root of their
	 * module. The text is {@link #outputNotSourceRoot}'s.
	 */
	public static final String OUTPUT_NOT_SOURCE_ROOT = "output-not-source-root";

	private static final char SEPARATOR = '\n';

	private BuildMessages() {
	}

	/** @param outputRoot the output directory without the package's directories, with / separators */
	public static String outputNotSourceRoot(String moduleName, String outputRoot) {
		return moduleName+SEPARATOR+outputRoot;
	}

	/** The module name and the output root of {@link #outputNotSourceRoot}, or null if malformed. */
	public static String[] parseOutputNotSourceRoot(String text) {
		int separator = text.indexOf(SEPARATOR);
		if ( separator<=0 || separator==text.length()-1 ) return null;
		return new String[] {text.substring(0, separator), text.substring(separator+1)};
	}

	/**
	 * The path as a message shows it: relative to the first of the directories that holds it
	 * (a module's content roots), or unchanged when none does. All with / separators.
	 * <p>
	 * Prior art: javac's and the IDE's own build messages name files relative to the project.
	 */
	public static String displayPath(String path, Iterable<String> directories) {
		for (String directory : directories) {
			String prefix = directory.endsWith("/") ? directory : directory+"/";
			if ( path.equals(directory) || path.equals(prefix) ) return ".";
			if ( path.startsWith(prefix) ) return path.substring(prefix.length());
		}
		return path;
	}
}
