package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** What the preview knows about one grammar file: its grammars, the start rule, and the
 *  result of the last parse. Immutable: {@link PreviewService} replaces a grammar's snapshot whole, on
 *  the EDT, and anyone may keep one (a background parse, the profiler) and read it later from
 *  any thread, seeing grammars, start rule and parse result that belong together.
 *  <p>
 *  Prior art: immutable snapshots published by reference, as the platform does for PSI stubs
 *  and React does for state. The view's own state per grammar (input editor, input text) is
 *  not here; {@link InputPanel} owns it.
 *
 *  @param parserGrammar the parser or combined grammar; null for a lexer grammar or before loading
 *  @param lexerGrammar the lexer grammar, separate or from a combined grammar; null before loading
 *  @param startRule the rule parses start from; null if the grammar has no parser rules
 *  @param parsingResult the last parse, made with exactly these grammars and start rule; null
 *                       until one finishes
 *  @param renamedStartRule the start rule's new name after a rename refactoring; it becomes the
 *                          start rule once grammars that have it load (the preview reloads on
 *                          save only, so the loaded grammars still have the old name)
 */
public record PreviewState(@NotNull VirtualFile grammarFile,
						   @Nullable Grammar parserGrammar,
						   @Nullable LexerGrammar lexerGrammar,
						   @Nullable String startRule,
						   @Nullable ParsingResult parsingResult,
						   @Nullable String renamedStartRule)
{
	/** A grammar not loaded yet. */
	public static PreviewState empty(@NotNull VirtualFile grammarFile) {
		return new PreviewState(grammarFile, null, null, null, null, null);
	}

	/** Newly loaded grammars. The start rule becomes its new name if it was renamed and the
	 *  parser grammar has that name; else it stays if the grammar still has it, else becomes the
	 *  grammar's first rule. The old parse result belonged to the old grammars and is dropped.
	 */
	public PreviewState withGrammars(@Nullable LexerGrammar lexer, @Nullable Grammar parser) {
		if ( renamedStartRule!=null && parser!=null && parser.getRule(renamedStartRule)!=null ) {
			return new PreviewState(grammarFile, parser, lexer, renamedStartRule, null, null);
		}
		return new PreviewState(grammarFile, parser, lexer, startRuleIn(parser, startRule), null, renamedStartRule);
	}

	/** A start rule the user chose; the old parse result belonged to the old start rule. */
	public PreviewState withStartRule(@Nullable String rule) {
		return new PreviewState(grammarFile, parserGrammar, lexerGrammar, rule, null, null);
	}

	/** The start rule was renamed to {@code newName} in the grammar's text. */
	public PreviewState withRenamedStartRule(@NotNull String newName) {
		return new PreviewState(grammarFile, parserGrammar, lexerGrammar, startRule, parsingResult, newName);
	}

	/** This snapshot with the result of parsing with it. */
	public PreviewState withParsingResult(@Nullable ParsingResult result) {
		return new PreviewState(grammarFile, parserGrammar, lexerGrammar, startRule, result, renamedStartRule);
	}

	/** Whether {@code ruleName} is the start rule, by its loaded or its renamed name. */
	public boolean isStartRule(@NotNull String ruleName) {
		return ruleName.equals(startRule) || ruleName.equals(renamedStartRule);
	}

	/** Whether {@code other} has the same grammars and start rule, so a parse made with one
	 *  belongs to the other.
	 */
	public boolean sameInputsAs(@NotNull PreviewState other) {
		return grammarFile.equals(other.grammarFile) &&
			parserGrammar==other.parserGrammar &&
			lexerGrammar==other.lexerGrammar &&
			Objects.equals(startRule, other.startRule);
	}

	public @Nullable Grammar getMainGrammar() {
		return parserGrammar!=null ? parserGrammar : lexerGrammar;
	}

	/** {@code current} if {@code g} has it, else {@code g}'s first rule; null if {@code g} has no
	 *  rules. A grammar that failed to load keeps the start rule for when it's fixed.
	 */
	private static @Nullable String startRuleIn(@Nullable Grammar g, @Nullable String current) {
		if ( g==null || g.rules.isEmpty() ) return null;
		if ( current!=null && (g==ParsingUtils.BAD_PARSER_GRAMMAR || g.getRule(current)!=null) ) return current;
		return g.rules.getElement(0).name;
	}
}
