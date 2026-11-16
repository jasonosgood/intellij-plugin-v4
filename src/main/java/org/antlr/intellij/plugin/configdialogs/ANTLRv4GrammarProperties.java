package org.antlr.intellij.plugin.configdialogs;

import com.intellij.util.xmlb.annotations.OptionTag;
import com.intellij.util.xmlb.annotations.Property;
import com.intellij.util.xmlb.annotations.Tag;
import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.antlr.intellij.plugin.parsing.CaseChangingStrategy;

/**
 * Holds all the settings related to a given grammar file. These settings
 * can be used during code generation, in the Preview window etc.
 * <p>
 * Settings can be modified via a user interface in {@link ConfigANTLRPerGrammar}
 * and are saved in {@code .idea/misc.xml} thanks to {@link ANTLRv4GrammarPropertiesComponent}.
 * <p>
 * Settings for the current project are stored in .idea/misc.xml, under PerGrammarGenerationSettings tags.
 * <p>
 * These are the settings for a specific grammar file:
 *  <p>
 *      &lt;PerGrammarGenerationSettings>
 *   &lt;option name="fileName" value="$PROJECT_DIR$/src/main/antlr/org/antlr/intellij/plugin/parser/ANTLRv4Parser.g4" />
 *  <p>
 *      But we also allow regex matching:
 *  <p>
 *      &lt;option name="fileName" value="$PROJECT_DIR$/src/main/antlr/.*Parser.g4" />
 * Which should match any file that ends with Parser.g4 under src/main/antlr.
 *
 * Then we also support default settings for the current project:
 *  <p>
 *      &lt;option name="fileName" value="*" />
 *  <p>These settings will be used if we couldn't match more specific fileNames (either by full names of by matching a regex).
 * <p>
 * Then if there was no default settings for the current project, we use ANTLRv4GrammarPropertiesStore.DEFAULT_GRAMMAR_PROPERTIES which are application-wide settings provided by the plugin.
 * <p>
 * The "**" has no special meaning, it kinda looks like a catch-all name pattern.
 */
@Tag("PerGrammarGenerationSettings")
public class ANTLRv4GrammarProperties implements Cloneable {

    static final String PROJECT_SETTINGS_PREFIX = "*";

    @Property
    String fileName;

    @Property
    String outputDir;

    @Property
    String libDir;

    @Property
    String encoding;

    @Property
    String pkg;

    @Property
    String language;

    @Property
    boolean generateListener = true;

    @Property
    boolean generateVisitor;

    @Property
    @OptionTag(converter = CaseChangingStrategyConverter.class)
    CaseChangingStrategy caseChangingStrategy = CaseChangingStrategy.LEAVE_AS_IS;

    public ANTLRv4GrammarProperties() {
    }

    public ANTLRv4GrammarProperties(ANTLRv4GrammarProperties source) {
        this.fileName = source.fileName;
        this.outputDir = source.outputDir;
        this.libDir = source.libDir;
        this.encoding = source.encoding;
        this.pkg = source.pkg;
        this.language = source.language;
        this.generateListener = source.generateListener;
        this.generateVisitor = source.generateVisitor;
        this.caseChangingStrategy = source.caseChangingStrategy;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public String getLibDir() {
        return libDir;
    }

    public String getEncoding() {
        return encoding;
    }

    public String getPackage() {
        return pkg;
    }

    public String getLanguage() {
        return language;
    }

    public boolean shouldGenerateParseTreeListener() {
        return generateListener;
    }

    public boolean shouldGenerateParseTreeVisitor() {
        return generateVisitor;
    }

    public CaseChangingStrategy getCaseChangingStrategy() {
        return caseChangingStrategy;
    }

    /** The code generation settings as plain data, for the generation core. */
    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(outputDir, libDir, encoding, pkg, language, generateListener, generateVisitor);
    }

    @Override
    public ANTLRv4GrammarProperties clone() throws CloneNotSupportedException {
        return (ANTLRv4GrammarProperties) super.clone();
    }
}
