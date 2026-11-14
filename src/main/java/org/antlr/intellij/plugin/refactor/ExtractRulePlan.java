package org.antlr.intellij.plugin.refactor;

import com.intellij.openapi.util.TextRange;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Everything Extract Rule needs to know about a selection, computed from grammar text
 * without touching the IDE: the trimmed selection, its duplicates, a suggested name,
 * and the text edits to perform.
 */
public final class ExtractRulePlan {
	/** A replacement of {@code range} in the original text by {@code text}. */
	public static final class Edit {
		public final TextRange range;
		public final String text;

		Edit(TextRange range, String text) {
			this.range = range;
			this.text = text;
		}
	}

	/** The selection, trimmed of surrounding whitespace and comments. */
	public final TextRange selection;
	/** All places the new rule can replace, in document order; includes {@link #selection}. */
	public final List<TextRange> occurrences;
	public final boolean lexerRule;
	public final Set<String> existingNames;
	public final String suggestedName;
	private final String ruleBody;
	private final int ruleStopOffset;
	private final String grammarText;

	private ExtractRulePlan(String grammarText, TextRange selection, List<TextRange> occurrences,
	                        boolean lexerRule, Set<String> existingNames, String suggestedName,
	                        int ruleStopOffset) {
		this.grammarText = grammarText;
		this.selection = selection;
		this.occurrences = occurrences;
		this.lexerRule = lexerRule;
		this.existingNames = existingNames;
		this.suggestedName = suggestedName;
		this.ruleBody = selection.substring(grammarText);
		this.ruleStopOffset = ruleStopOffset;
	}

	/**
	 * @return the plan, or null if the selection holds no grammar tokens or is not inside a rule body
	 */
	@Nullable
	public static ExtractRulePlan create(String grammarText, int selStart, int selEndExclusive) {
		if ( selEndExclusive<=selStart ) return null;
		ParsingResult parsed = ParsingUtils.parseANTLRGrammar(grammarText);
		ParserRuleContext tree = (ParserRuleContext) parsed.tree;
		TokenStream tokens = parsed.parser.getTokenStream();

		Token first = RefactorUtils.getTokenForCharIndex(tokens, selStart);
		Token last = RefactorUtils.getTokenForCharIndex(tokens, selEndExclusive - 1);
		if ( first==null || last==null ) return null;
		int start = first.getTokenIndex();
		int stop = last.getTokenIndex();
		while ( start<=stop && tokens.get(start).getChannel()!=Token.DEFAULT_CHANNEL ) start++;
		while ( stop>=start && tokens.get(stop).getChannel()!=Token.DEFAULT_CHANNEL ) stop--;
		if ( start>stop ) return null;

		Boolean lexerRule = ExtractRuleOccurrences.isInLexerRule(tree, start);
		ParserRuleContext ruleSpec = enclosingRuleSpec(tree, start);
		if ( lexerRule==null || ruleSpec==null ) return null;

		List<TextRange> occurrences = new ArrayList<>();
		for (Interval occ : ExtractRuleOccurrences.find(tree, tokens, start, stop)) {
			occurrences.add(range(tokens, occ.a, occ.b));
		}
		Set<String> existing = ExtractRuleNameSuggester.existingNames(tree);
		String name = ExtractRuleNameSuggester.suggest(tokens, start, stop, lexerRule, existing);
		return new ExtractRulePlan(grammarText, range(tokens, start, stop), occurrences, lexerRule,
		                           existing, name, ruleSpec.getStop().getStopIndex());
	}

	/** The edits, last offset first, so they can be applied in order without shifting offsets. */
	public List<Edit> edits(@NotNull String ruleName, boolean replaceAll) {
		List<Edit> edits = new ArrayList<>();
		edits.add(newRuleInsertion(ruleName));
		for (TextRange r : replaceAll ? occurrences : Collections.singletonList(selection)) {
			edits.add(new Edit(r, ruleName));
		}
		edits.sort(Comparator.comparingInt((Edit e) -> e.range.getStartOffset()).reversed());
		return edits;
	}

	/** Applies {@link #edits} to the grammar text this plan was made from. */
	public String apply(@NotNull String ruleName, boolean replaceAll) {
		StringBuilder sb = new StringBuilder(grammarText);
		for (Edit e : edits(ruleName, replaceAll)) {
			sb.replace(e.range.getStartOffset(), e.range.getEndOffset(), e.text);
		}
		return sb.toString();
	}

	/** Puts the new rule after the rule holding the selection, separated by a blank line. */
	private Edit newRuleInsertion(String ruleName) {
		String rule = ruleName + " : " + ruleBody + " ;";
		int i = ruleStopOffset + 1;
		while ( i<grammarText.length() && (grammarText.charAt(i)==' ' || grammarText.charAt(i)=='\t'
			|| grammarText.charAt(i)=='\r') ) {
			i++;
		}
		if ( i<grammarText.length() && grammarText.charAt(i)=='\n' ) {
			return new Edit(TextRange.from(i + 1, 0), "\n" + rule + "\n");
		}
		// end of file, or more text on the same line as the rule's ';'
		return new Edit(TextRange.from(ruleStopOffset + 1, 0), "\n\n" + rule + "\n");
	}

	private static TextRange range(TokenStream tokens, int startTokenIndex, int stopTokenIndex) {
		return new TextRange(tokens.get(startTokenIndex).getStartIndex(),
		                     tokens.get(stopTokenIndex).getStopIndex() + 1);
	}

	private static ParserRuleContext enclosingRuleSpec(ParserRuleContext tree, int tokenIndex) {
		Tree node = Trees.getRootOfSubtreeEnclosingRegion(tree, tokenIndex, tokenIndex);
		while ( node!=null && !(node instanceof ANTLRv4Parser.RuleSpecContext) ) {
			node = node.getParent();
		}
		return (ParserRuleContext) node;
	}
}
