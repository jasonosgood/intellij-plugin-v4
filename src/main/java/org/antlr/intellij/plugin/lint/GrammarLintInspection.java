package org.antlr.intellij.plugin.lint;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;
import org.jetbrains.annotations.NotNull;

/**
 * The antlr4-xl linter as an inspection: on/off and severity in Settings › Inspections › ANTLR,
 * and results in Code › Inspect Code. The work is done by {@link GrammarLintAnnotator}.
 * <p>
 * Prior art: IntelliJ's ESLint integration pairs an {@code ExternalAnnotator} (runs the whole-file
 * tool off the read lock, offers its fixes) with an {@code ExternalAnnotatorBatchInspection} like
 * this one.
 */
public class GrammarLintInspection extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
	public static final String SHORT_NAME = "AntlrGrammarLint";

	@Override
	public @NotNull String getShortName() {
		return SHORT_NAME;
	}
}
