package org.antlr.intellij.plugin.generation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ANTLR tool's options for one grammar, from its settings and its place in the project.
 * The Generate action and the JPS builder both build their arguments here, so they agree on
 * where generated files go (doc/B2_JPS_BUILDER.md, decision 4). No IDE or JPS classes.
 */
public final class GenerationArgs {
	/** Where recognizers go when the settings name no output directory, below the content root. */
	public static final String DEFAULT_OUTPUT_DIR = "gen";

	private final Map<String, String> options;
	private final String outputRoot;

	private GenerationArgs(Map<String, String> options, String outputRoot) {
		this.options = Collections.unmodifiableMap(options);
		this.outputRoot = outputRoot;
	}

	public static GenerationArgs of(GenerationSettings settings, GrammarPlace place) {
		Map<String, String> options = new LinkedHashMap<>();

		String pkg = settings.pkg;
		if ( isBlank(pkg) ) pkg = place.inferredPackage.get();
		if ( !isBlank(pkg) ) options.put("-package", pkg);

		if ( !isBlank(settings.language) ) options.put("-Dlanguage="+settings.language, "");

		// The output directory: below the content root unless absolute, then the package's path.
		String outputDir = isEmpty(settings.outputDir) ? DEFAULT_OUTPUT_DIR : settings.outputDir;
		outputDir = underContentRoot(place.expandPath.apply(outputDir), place);
		String outputRoot = outputDir;
		if ( !isBlank(pkg) ) outputDir += File.separator+pkg.replace('.', File.separatorChar);
		options.put("-o", outputDir);

		// Where ANTLR looks for imported grammars and .tokens files: beside the grammar by default.
		String libDir = isEmpty(settings.libDir) ? place.grammarDir : settings.libDir;
		options.put("-lib", underContentRoot(place.expandPath.apply(libDir), place));

		if ( !isBlank(settings.encoding) ) options.put("-encoding", settings.encoding);

		options.put(settings.generateListener ? "-listener" : "-no-listener", "");
		options.put(settings.generateVisitor ? "-visitor" : "-no-visitor", "");

		return new GenerationArgs(options, outputRoot);
	}

	/** Option to value; "" for an option without one. */
	public Map<String, String> asMap() {
		return options;
	}

	/** The options as command-line arguments; the caller adds the grammar files. */
	public List<String> asList() {
		List<String> args = new ArrayList<>();
		for (Map.Entry<String, String> option : options.entrySet()) {
			args.add(option.getKey());
			if ( !option.getValue().isEmpty() ) args.add(option.getValue());
		}
		return args;
	}

	/** The directory the generated files go to, package directories included. */
	public String outputDir() {
		return options.get("-o");
	}

	/** The output directory without the package's directories: what would be a source root. */
	public String outputRoot() {
		return outputRoot;
	}

	private static String underContentRoot(String dir, GrammarPlace place) {
		return new File(dir).isAbsolute() ? dir : place.contentRoot+File.separator+dir;
	}

	private static boolean isEmpty(String s) {
		return s==null || s.isEmpty();
	}

	private static boolean isBlank(String s) {
		return s==null || s.trim().isEmpty();
	}
}
