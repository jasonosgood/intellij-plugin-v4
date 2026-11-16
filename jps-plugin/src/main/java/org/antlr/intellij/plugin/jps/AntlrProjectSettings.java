package org.antlr.intellij.plugin.jps;

import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.antlr.intellij.plugin.generation.SettingsLookup;
import org.jdom.Element;
import org.jetbrains.jps.model.JpsElementChildRole;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.JpsSimpleElement;
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A project's per-grammar code generation settings, as the build process sees them: read
 * from the {@code ANTLRGenerationPreferences} component of {@code .idea/misc.xml}, where the
 * IDE's {@code ANTLRv4GrammarPropertiesComponent} stores what the Configure ANTLR dialog edits.
 * Immutable. This reads the persisted format; it must follow any change to it (DECISIONS.md,
 * "Keep stable").
 * <p>
 * Prior art: every bundled JPS plugin reads its IDE settings this way (a
 * JpsModelSerializerExtension putting an element into the JPS project).
 */
public final class AntlrProjectSettings {
	static final String COMPONENT_NAME = "ANTLRGenerationPreferences";
	static final String CONFIG_FILE = "misc.xml";

	private static final JpsElementChildRole<JpsSimpleElement<AntlrProjectSettings>> ROLE =
		JpsElementChildRoleBase.create("ANTLR code generation settings");
	private static final AntlrProjectSettings NONE = new AntlrProjectSettings(Collections.emptyList());

	/** The settings stored under one file pattern. */
	private static final class Entry {
		final String pattern;
		final GenerationSettings settings;

		Entry(String pattern, GenerationSettings settings) {
			this.pattern = pattern;
			this.settings = settings;
		}
	}

	private final List<Entry> entries;

	private AntlrProjectSettings(List<Entry> entries) {
		this.entries = entries;
	}

	/** The project's settings; never null (a project that stored none has only defaults). */
	public static AntlrProjectSettings of(JpsProject project) {
		JpsSimpleElement<AntlrProjectSettings> element = project.getContainer().getChild(ROLE);
		return element==null ? NONE : element.getData();
	}

	static void load(JpsProject project, Element component) {
		project.getContainer().setChild(ROLE, JpsElementFactory.getInstance().createSimpleElement(parse(component)));
	}

	/**
	 * @param grammarPath the grammar's path with {@code /} separators, as the IDE stores them
	 */
	public GenerationSettings settingsFor(String grammarPath) {
		Entry entry = SettingsLookup.find(entries, e -> e.pattern, grammarPath, null);
		return entry==null ? GenerationSettings.DEFAULTS : entry.settings;
	}

	/**
	 * Reads the component as the IDE's XML serializer writes it:
	 * <pre>
	 * &lt;option name="perGrammarGenerationSettings">&lt;list>
	 *   &lt;PerGrammarGenerationSettings>
	 *     &lt;option name="fileName" value="$PROJECT_DIR$/src/Hello.g4"/>
	 *     &lt;option name="pkg" value="demo"/> ...
	 * </pre>
	 * An option equal to its default isn't written: listener on, visitor off, strings unset.
	 */
	public static AntlrProjectSettings parse(Element component) {
		List<Entry> entries = new ArrayList<>();
		for (Element option : component.getChildren("option")) {
			if ( !"perGrammarGenerationSettings".equals(option.getAttributeValue("name")) ) continue;
			Element list = option.getChild("list");
			if ( list==null ) continue;
			for (Element grammar : list.getChildren("PerGrammarGenerationSettings")) {
				Map<String, String> values = new HashMap<>();
				for (Element value : grammar.getChildren("option")) {
					values.put(value.getAttributeValue("name"), value.getAttributeValue("value"));
				}
				String pattern = values.get("fileName");
				if ( pattern==null ) continue;
				entries.add(new Entry(pattern, new GenerationSettings(
					values.get("outputDir"),
					values.get("libDir"),
					values.get("encoding"),
					values.get("pkg"),
					values.get("language"),
					flag(values.get("generateListener"), true),
					flag(values.get("generateVisitor"), false))));
			}
		}
		return new AntlrProjectSettings(Collections.unmodifiableList(entries));
	}

	private static boolean flag(String value, boolean byDefault) {
		return value==null ? byDefault : Boolean.parseBoolean(value);
	}
}
