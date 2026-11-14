package org.antlr.intellij.plugin.refactor;

import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

		ParserRuleContext rule = enclosingRule(tree, startTokenIndex);
		if ( !wanted.isEmpty() && rule!=null ) {
			Set<Interval> candidates = new LinkedHashSet<>();
			collect(tree, isLexerRule(rule), null, tokens, wanted, candidates);
			candidates.remove(selection);
			for (Interval candidate : candidates) {
				ParserRuleContext candidateRule = enclosingRule(tree, candidate.a);
				if ( candidateRule!=null && problem(candidateRule, tokens, candidate)==null ) {
					found.add(candidate);
				}
			}
		}

		return removeOverlaps(found, selection);
	}

	/**
	 * Why replacing tokens {@code occ} of {@code rule} by a reference to a new rule holding
	 * them would break the grammar, or null if it wouldn't. Assumes {@code occ} is a unit run
	 * ({@link #isUnitRun}). Checks:
	 * <ul>
	 *   <li>left recursion: {@code e : e '*' e | INT ;} becomes {@code e : n | INT ; n : e '*' e ;},
	 *       mutually left-recursive rules, which ANTLR rejects;</li>
	 *   <li>alternative labels: moving some but not all {@code # label}ed alternatives leaves
	 *       the rule with labelled and unlabelled alternatives, which ANTLR rejects;</li>
	 *   <li>actions: a {@code $x} outside the run that names a label or element inside it, or a
	 *       {@code $x} inside it that names anything else (labels, elements, rule attributes
	 *       such as {@code $text}, arguments), would resolve differently or not at all.</li>
	 * </ul>
	 */
	@Nullable
	static String problem(ParserRuleContext rule, TokenStream tokens, Interval occ) {
		if ( rule instanceof ANTLRv4Parser.ParserRuleSpecContext ) {
			ANTLRv4Parser.ParserRuleSpecContext parserRule = (ANTLRv4Parser.ParserRuleSpecContext) rule;
			List<ANTLRv4Parser.LabeledAltContext> alts = parserRule.ruleBlock().ruleAltList().labeledAlt();
			Token first = tokens.get(occ.a);
			if ( first.getType()==ANTLRv4Parser.RULE_REF && first.getText().equals(parserRule.RULE_REF().getText()) ) {
				for (ANTLRv4Parser.LabeledAltContext alt : alts) {
					if ( !isEmpty(alt) && alt.getStart().getTokenIndex()==occ.a ) {
						return "It starts with a reference to its own rule '" + first.getText() +
							"', so the new rule and '" + first.getText() + "' would be mutually " +
							"left-recursive, which ANTLR doesn't allow.";
					}
				}
			}
			boolean labelInside = false;
			for (ANTLRv4Parser.LabeledAltContext alt : alts) {
				if ( alt.POUND()!=null && occ.properlyContains(Interval.of(alt.POUND().getSymbol().getTokenIndex(),
				                                                           alt.POUND().getSymbol().getTokenIndex())) ) {
					labelInside = true;
				}
			}
			boolean allAlts = !alts.isEmpty() && alts.get(0).getStart().getTokenIndex()==occ.a
				&& alts.get(alts.size() - 1).getStop().getTokenIndex()==occ.b;
			if ( labelInside && !allAlts ) {
				return "It includes '# label'ed alternatives but not all of the rule's alternatives, " +
					"so the rule would mix labelled and unlabelled alternatives, which ANTLR doesn't allow.";
			}
		}

		Set<String> namesInside = new HashSet<>();   // labels and rule/token references
		Set<String> namesOutside = new HashSet<>();
		StringBuilder actionsInside = new StringBuilder();
		StringBuilder actionsOutside = new StringBuilder();
		for (int i = rule.getStart().getTokenIndex(); i<=rule.getStop().getTokenIndex(); i++) {
			Token t = tokens.get(i);
			boolean inside = occ.a<=i && i<=occ.b;
			int type = t.getType();
			if ( type==ANTLRv4Parser.ACTION_CONTENT || type==ANTLRv4Parser.ARGUMENT_CONTENT ) {
				(inside ? actionsInside : actionsOutside).append(t.getText());
				continue;
			}
			(inside ? actionsInside : actionsOutside).append(' '); // separates actions
			if ( type==ANTLRv4Parser.RULE_REF || type==ANTLRv4Parser.TOKEN_REF ) {
				(inside ? namesInside : namesOutside).add(t.getText());
			}
		}
		Set<String> onlyInside = new HashSet<>(namesInside);
		onlyInside.removeAll(namesOutside);
		for (String ref : attributeRefs(actionsOutside)) {
			if ( onlyInside.contains(ref) ) {
				return "An action elsewhere in the rule uses $" + ref + ", which would move to the new rule.";
			}
		}
		for (String ref : attributeRefs(actionsInside)) {
			if ( !namesInside.contains(ref) ) {
				return "An action in it uses $" + ref + ", which would mean something else in the new rule.";
			}
		}
		return null;
	}

	private static final Pattern ATTRIBUTE_REF = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

	private static Set<String> attributeRefs(CharSequence actionText) {
		Set<String> refs = new LinkedHashSet<>();
		Matcher m = ATTRIBUTE_REF.matcher(actionText);
		while ( m.find() ) refs.add(m.group(1));
		return refs;
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

	/** Adds each run of units whose significant tokens equal {@code wanted}; linear per start unit. */
	private static void matchRuns(List<? extends ParserRuleContext> units, TokenStream tokens,
	                              List<String> wanted, Set<Interval> out) {
		for (int i = 0; i<units.size(); i++) {
			Token first = units.get(i).getStart();
			if ( isEmpty(units.get(i)) || !first.getText().equals(wanted.get(0)) ) continue;
			int matched = 0; // tokens of wanted matched so far
			for (int j = i; j<units.size(); j++) {
				ParserRuleContext unit = units.get(j);
				if ( isEmpty(unit) ) break;
				int stop = unit.getStop().getTokenIndex();
				// significant tokens between units (e.g. '|' in an alt list) belong to the run too
				int from = j==i ? first.getTokenIndex() : units.get(j - 1).getStop().getTokenIndex() + 1;
				boolean ok = true;
				for (String text : significantTokens(tokens, from, stop)) {
					if ( matched>=wanted.size() || !text.equals(wanted.get(matched)) ) {
						ok = false;
						break;
					}
					matched++;
				}
				if ( !ok ) break;
				if ( matched==wanted.size() ) {
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

	/** The parser rule ({@code parserRuleSpec}) or lexer rule ({@code lexerRule}) holding the token, or null. */
	@Nullable
	static ParserRuleContext enclosingRule(ParserRuleContext tree, int tokenIndex) {
		Tree node = Trees.getRootOfSubtreeEnclosingRegion(tree, tokenIndex, tokenIndex);
		while ( node!=null ) {
			if ( node instanceof ANTLRv4Parser.ParserRuleSpecContext || node instanceof ANTLRv4Parser.LexerRuleContext ) {
				return (ParserRuleContext) node;
			}
			node = node.getParent();
		}
		return null;
	}

	static boolean isLexerRule(ParserRuleContext rule) {
		return rule instanceof ANTLRv4Parser.LexerRuleContext;
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
