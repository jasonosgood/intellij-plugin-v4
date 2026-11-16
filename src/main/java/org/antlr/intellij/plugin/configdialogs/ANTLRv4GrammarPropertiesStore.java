package org.antlr.intellij.plugin.configdialogs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.xmlb.annotations.Property;
import org.antlr.intellij.plugin.generation.SettingsLookup;
import org.antlr.intellij.plugin.parsing.CaseChangingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stores settings related to code generation per grammar file.
 */
public class ANTLRv4GrammarPropertiesStore {

	static final ANTLRv4GrammarProperties DEFAULT_GRAMMAR_PROPERTIES = initDefaultGrammarProperties();

	@Property
	private final List<ANTLRv4GrammarProperties> perGrammarGenerationSettings = new ArrayList<>();

	public void add(ANTLRv4GrammarProperties properties) {
		perGrammarGenerationSettings.add(properties);
	}

	public ANTLRv4GrammarProperties getGrammarProperties(String grammarFile) {
		// The rules (exact path, wildcard, the project's "*" entry, defaults) are shared with
		// the JPS builder, which reads these settings from the project files.
		return SettingsLookup.find(perGrammarGenerationSettings, settings -> settings.fileName,
								   grammarFile, DEFAULT_GRAMMAR_PROPERTIES);
	}

	private ANTLRv4GrammarProperties getOrCreateGrammarProperties(String grammarFile) {

		ANTLRv4GrammarProperties properties = getGrammarProperties(grammarFile);

		if ( Objects.equals(properties.fileName, grammarFile) ) {
			return properties;
		}

		ANTLRv4GrammarProperties newProperties = new ANTLRv4GrammarProperties(properties);
		newProperties.fileName = grammarFile;

		add(newProperties);

		return newProperties;
	}

	public static ANTLRv4GrammarProperties getGrammarProperties(Project project, VirtualFile grammarFile) {
		return getGrammarProperties(project, grammarFile.getPath());
	}

	/**
	 * Defaults to settings defined in the project if they exist, or to empty settings.
	 */
	public static ANTLRv4GrammarProperties getGrammarProperties(Project project, String grammarFile) {
		ANTLRv4GrammarPropertiesStore store = ANTLRv4GrammarPropertiesComponent.getInstance(project).getState();
		return store.getGrammarProperties(grammarFile);
	}

	/**
	 * Get the properties for this grammar, or create a new properties object derived from the project settings if
	 * they exist, or from the default empty settings otherwise.
	 */
	public static ANTLRv4GrammarProperties getOrCreateGrammarProperties(Project project, String grammarFile) {
		ANTLRv4GrammarPropertiesStore store = ANTLRv4GrammarPropertiesComponent.getInstance(project).getState();
		return store.getOrCreateGrammarProperties(grammarFile);
	}

	private static ANTLRv4GrammarProperties initDefaultGrammarProperties() {
		ANTLRv4GrammarProperties defaultSettings = new ANTLRv4GrammarProperties();

		defaultSettings.fileName = "**";
		defaultSettings.outputDir = "";
		defaultSettings.libDir = "";
		defaultSettings.encoding = "";
		defaultSettings.pkg = "";
		defaultSettings.language = "";
		defaultSettings.generateListener = true;
		defaultSettings.generateVisitor = true;
		defaultSettings.caseChangingStrategy = CaseChangingStrategy.LEAVE_AS_IS;

		return defaultSettings;
	}
}
