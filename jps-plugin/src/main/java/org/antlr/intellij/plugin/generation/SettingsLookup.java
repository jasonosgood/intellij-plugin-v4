package org.antlr.intellij.plugin.generation;

import com.intellij.openapi.fileTypes.WildcardFileNameMatcher;

import java.util.List;
import java.util.function.Function;

/**
 * Finds the settings for a grammar among a project's entries, each stored under a file
 * pattern. In order: the entry whose pattern is the grammar's path; the first entry whose
 * pattern matches it as a wildcard ({@code *} and {@code ?}); the project's own defaults, which
 * are the entry stored under {@code *}; the fallback.
 * <p>
 * Generic in the entry type so the IDE (its persisted, editable settings objects) and the
 * build process (plain data read from the project files) share one set of rules.
 * {@link WildcardFileNameMatcher} is a platform class that the build process also has: it
 * ships in the JPS model jar.
 */
public final class SettingsLookup {
	/** The pattern under which a project's default settings are stored. */
	public static final String PROJECT_DEFAULTS_PATTERN = "*";

	private SettingsLookup() {
	}

	/** @param grammarPath the grammar's path, with {@code /} separators */
	public static <T> T find(List<T> entries, Function<T, String> patternOf, String grammarPath, T fallback) {
		T found = match(entries, patternOf, grammarPath);
		if ( found==null ) found = match(entries, patternOf, PROJECT_DEFAULTS_PATTERN);
		return found!=null ? found : fallback;
	}

	private static <T> T match(List<T> entries, Function<T, String> patternOf, String name) {
		for (T entry : entries) {
			if ( name.equals(patternOf.apply(entry)) ) return entry;
		}
		for (T entry : entries) {
			String pattern = patternOf.apply(entry);
			if ( pattern!=null && matchesWildcard(pattern, name) ) return entry;
		}
		return null;
	}

	private static boolean matchesWildcard(String pattern, String name) {
		try {
			return new WildcardFileNameMatcher(pattern).acceptsCharSequence(name);
		}
		catch (RuntimeException e) { // a pattern that isn't a valid wildcard matches nothing
			return false;
		}
	}
}
