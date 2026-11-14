package org.antlr.intellij.plugin.refactor;

import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds every place in a grammar that an extracted rule body could replace.
 * <p>
 * Prior art: IntelliJ's Extract Method / Introduce Variable duplicate search,
 * which compares syntax trees rather than raw text. We do the same on the ANTLR
 * parse tree of the grammar: an occurrence is a contiguous run of sibling
 * elements in one alternative (e.g. {@code A B} in {@code x : A B C}), or a
 * contiguous run of whole alternatives in one alternative list (e.g. {@code A | B}
 * in {@code (A | B | C)}), whose non-hidden tokens equal the selection's.
 * Matching whole elements means {@code A} is not found inside {@code A*},
 * {@code ~A} or {@code x=A}, where replacing it would change the grammar.
 * Whitespace and comments are ignored.
 */
public final class ExtractRuleOccurrences {
	private ExtractRuleOccurrences() {
	}

	/**
	 * @param tree           root of the grammar's parse tree
	 * @param tokens         token stream the tree was parsed from
	 * @param startTokenIndex first token of the selection (inclusive)
	 * @param stopTokenIndex  last token of the selection (inclusive)
	 * @return token intervals of all occurrences, sorted and non-overlapping. Always
	 * contains the selection itself. Occurrences are only searched in rules of the same
	 * kind (parser or lexer) as the one holding the selection, since the new rule can
	 * only be referenced from those.
	 */
	public static List<Interval> find(ParserRuleContext tree, TokenStream tokens,
	                                  int startTokenIndex, int stopTokenIndex) {
		Interval selection = Interval.of(startTokenIndex, stopTokenIndex);
		List<String> wanted = significantTokens(tokens, startTokenIndex, stopTokenIndex);

		List<Interval> found = new ArrayList<>();
		found.add(selection);

		Boolean inLexerRule = isInLexerRule(tree, startTokenIndex);
		if ( !wanted.isEmpty() && inLexerRule!=null ) {
			Set<Interval> candidates = new LinkedHashSet<>();
			collect(tree, inLexerRule, null, tokens, wanted, candidates);
			candidates.remove(selection);
			found.addAll(candidates);
		}

		return removeOverlaps(found, selection);
	}

	/**
	 * True if tokens [startTokenIndex, stopTokenIndex] are exactly a contiguous run of sibling
	 * units (elements of one alternative, or alternatives of one list), the same shape
	 * {@link #find} matches occurrences against. Anything else (a rule name, a {@code ;}, part
	 * of an element such as {@code A} in {@code A*}, part of an alternative, or text spanning
	 * two rules) can't be replaced by a rule reference without changing or breaking the grammar.
	 */
	static boolean isUnitRun(ParserRuleContext tree, int startTokenIndex, int stopTokenIndex) {
		Tree node = Trees.getRootOfSubtreeEnclosingRegion(tree, startTokenIndex, stopTokenIndex);
		while ( node instanceof ParserRuleContext && !(node instanceof ANTLRv4Parser.RuleSpecContext) ) {
			List<? extends ParserRuleContext> units = unitsOf((ParserRuleContext) node);
			if ( units!=null && spansRun(units, startTokenIndex, stopTokenIndex) ) return true;
			node = node.getParent();
		}
		return false;
	}

	private static boolean spansRun(List<? extends ParserRuleContext> units, int start, int stop) {
		boolean started = false;
		for (ParserRuleContext u : units) {
			if ( isEmpty(u) ) {
				if ( started ) return false;
				continue;
			}
			if ( !started && u.getStart().getTokenIndex()==start ) started = true;
			if ( started && u.getStop().getTokenIndex()==stop ) return true;
		}
		return false;
	}

	private static void collect(ParseTree node, boolean wantLexer, Boolean inLexer,
	                            TokenStream tokens, List<String> wanted, Set<Interval> out) {
		if ( !(node instanceof ParserRuleContext) ) return;
		ParserRuleContext ctx = (ParserRuleContext) node;

		if ( ctx instanceof ANTLRv4Parser.ParserRuleSpecContext ) inLexer = false;
		else if ( ctx instanceof ANTLRv4Parser.LexerRuleContext ) inLexer = true;

		if ( inLexer!=null && inLexer==wantLexer ) {
			List<? extends ParserRuleContext> units = unitsOf(ctx);
			if ( units!=null ) {
				matchRuns(units, tokens, wanted, out);
			}
		}

		for (int i = 0; i<ctx.getChildCount(); i++) {
			collect(ctx.getChild(i), wantLexer, inLexer, tokens, wanted, out);
		}
	}

	/** Children of {@code ctx} that a selection may span a contiguous run of, or null. */
	private static List<? extends ParserRuleContext> unitsOf(ParserRuleContext ctx) {
		if ( ctx instanceof ANTLRv4Parser.AlternativeContext ) {
			return ((ANTLRv4Parser.AlternativeContext) ctx).element();
		}
		if ( ctx instanceof ANTLRv4Parser.LexerElementsContext ) {
			return ((ANTLRv4Parser.LexerElementsContext) ctx).lexerElement();
		}
		if ( ctx instanceof ANTLRv4Parser.AltListContext ) {
			return ((ANTLRv4Parser.AltListContext) ctx).alternative();
		}
		if ( ctx instanceof ANTLRv4Parser.LexerAltListContext ) {
			return ((ANTLRv4Parser.LexerAltListContext) ctx).lexerAlt();
		}
		if ( ctx instanceof ANTLRv4Parser.RuleAltListContext ) {
			return ((ANTLRv4Parser.RuleAltListContext) ctx).labeledAlt();
		}
		return null;
	}

	private static void matchRuns(List<? extends ParserRuleContext> units, TokenStream tokens,
	                              List<String> wanted, Set<Interval> out) {
		for (int i = 0; i<units.size(); i++) {
			Token first = units.get(i).getStart();
			if ( isEmpty(units.get(i)) || !first.getText().equals(wanted.get(0)) ) continue;
			for (int j = i; j<units.size(); j++) {
				ParserRuleContext last = units.get(j);
				if ( isEmpty(last) ) break;
				int stop = last.getStop().getTokenIndex();
				List<String> run = significantTokens(tokens, first.getTokenIndex(), stop);
				if ( run.size()>wanted.size() ) break;
				if ( run.equals(wanted) ) {
					out.add(Interval.of(first.getTokenIndex(), stop));
					break;
				}
			}
		}
	}

	private static boolean isEmpty(ParserRuleContext ctx) {
		return ctx.getStart()==null || ctx.getStop()==null
			|| ctx.getStop().getTokenIndex()<ctx.getStart().getTokenIndex();
	}

	/** Text of default-channel tokens in [start, stop]; ignores whitespace and comments. */
	static List<String> significantTokens(TokenStream tokens, int start, int stop) {
		List<String> result = new ArrayList<>();
		for (Token t : significant(tokens, start, stop)) result.add(t.getText());
		return result;
	}

	/** Default-channel tokens in [start, stop]; skips whitespace and comments. */
	static List<Token> significant(TokenStream tokens, int start, int stop) {
		List<Token> result = new ArrayList<>();
		for (int i = start; i<=stop; i++) {
			Token t = tokens.get(i);
			if ( t.getChannel()==Token.DEFAULT_CHANNEL && t.getType()!=Token.EOF ) result.add(t);
		}
		return result;
	}

	/** True if the token is in a lexer rule, false if in a parser rule, null if neither. */
	static Boolean isInLexerRule(ParserRuleContext tree, int tokenIndex) {
		ParseTree node = deepestEnclosing(tree, tokenIndex);
		while ( node!=null ) {
			if ( node instanceof ANTLRv4Parser.ParserRuleSpecContext ) return false;
			if ( node instanceof ANTLRv4Parser.LexerRuleContext ) return true;
			node = node.getParent();
		}
		return null;
	}

	private static ParseTree deepestEnclosing(ParserRuleContext ctx, int tokenIndex) {
		for (int i = 0; i<ctx.getChildCount(); i++) {
			ParseTree child = ctx.getChild(i);
			if ( child instanceof ParserRuleContext ) {
				ParserRuleContext c = (ParserRuleContext) child;
				if ( !isEmpty(c) && c.getStart().getTokenIndex()<=tokenIndex
					&& tokenIndex<=c.getStop().getTokenIndex() ) {
					return deepestEnclosing(c, tokenIndex);
				}
			}
		}
		return ctx;
	}

	/** Keeps the selection, then greedily keeps earlier occurrences over later overlapping ones. */
	private static List<Interval> removeOverlaps(List<Interval> found, Interval selection) {
		List<Interval> sorted = new ArrayList<>();
		for (Interval candidate : found) {
			if ( candidate.equals(selection) || candidate.disjoint(selection) ) sorted.add(candidate);
		}
		sorted.sort(Comparator.comparingInt(i -> i.a));
		List<Interval> result = new ArrayList<>();
		for (Interval candidate : sorted) {
			if ( !result.isEmpty() && !result.get(result.size() - 1).disjoint(candidate) ) continue;
			result.add(candidate);
		}
		return result;
	}
}
