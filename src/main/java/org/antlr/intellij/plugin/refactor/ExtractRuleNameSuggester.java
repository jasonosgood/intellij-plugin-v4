package org.antlr.intellij.plugin.refactor;

import org.antlr.intellij.plugin.parser.ANTLRv4Lexer;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses a name for a rule extracted from a selection, in the spirit of IntelliJ's
 * Introduce Variable name suggester: build it from the words the selection references.
 * <ul>
 *   <li>{@code expr ',' type} gives {@code exprCommaType} (parser) or {@code EXPR_COMMA_TYPE} (lexer)</li>
 *   <li>{@code expr (',' expr)*} gives {@code exprList}</li>
 *   <li>{@code '(' expr ')'} gives {@code parenExpr}</li>
 *   <li>names already used by a rule or token get a numeric suffix</li>
 * </ul>
 */
public final class ExtractRuleNameSuggester {
	private static final int MAX_WORDS = 3;

	/** Words ANTLR doesn't accept as rule names. */
	private static final Set<String> ANTLR_RESERVED = new HashSet<>(Arrays.asList(
		"options", "tokens", "channels", "import", "fragment", "lexer", "parser", "grammar",
		"returns", "locals", "throws", "catch", "finally", "mode", "rule", "EOF"));

	/**
	 * True if {@code name} can't be a rule name: an ANTLR keyword, or a keyword or literal of
	 * Java, the default target, where rule names become method and class names.
	 */
	public static boolean isReserved(String name) {
		return ANTLR_RESERVED.contains(name) || SourceVersion.isKeyword(name);
	}

	private ExtractRuleNameSuggester() {
	}

	public static String suggest(TokenStream tokens, int startTokenIndex, int stopTokenIndex,
	                             boolean lexerRule, Set<String> existingNames) {
		List<String> words = words(ExtractRuleOccurrences.significant(tokens, startTokenIndex, stopTokenIndex));
		String base;
		if ( words.isEmpty() ) {
			base = lexerRule ? "NEW_RULE" : "newRule";
		}
		else {
			base = lexerRule ? toUpperSnake(words) : toCamel(words);
		}
		if ( isReserved(base) ) {
			base += lexerRule ? "_RULE" : "Rule";
		}
		return unique(base, existingNames);
	}

	/** Names of all rules and declared tokens/channels, so the suggestion and the dialog can avoid them. */
	public static Set<String> existingNames(ParseTree tree) {
		Set<String> names = new HashSet<>();
		for (ParseTree n : Trees.findAllRuleNodes(tree, ANTLRv4Parser.RULE_parserRuleSpec)) {
			names.add(((ANTLRv4Parser.ParserRuleSpecContext) n).RULE_REF().getText());
		}
		for (ParseTree n : Trees.findAllRuleNodes(tree, ANTLRv4Parser.RULE_lexerRule)) {
			names.add(((ANTLRv4Parser.LexerRuleContext) n).TOKEN_REF().getText());
		}
		for (ParseTree n : Trees.findAllRuleNodes(tree, ANTLRv4Parser.RULE_idList)) {
			for (ANTLRv4Parser.IdentifierContext id : ((ANTLRv4Parser.IdListContext) n).identifier()) {
				names.add(id.getText());
			}
		}
		return names;
	}

	private static List<String> words(List<Token> sig) {
		String wrapper = null;
		if ( sig.size()>=3 ) {
			wrapper = wrapperWord(sig.get(0).getText(), sig.get(sig.size() - 1).getText());
			if ( wrapper!=null ) sig = sig.subList(1, sig.size() - 1);
		}

		List<String> words = new ArrayList<>();
		int angleDepth = 0; // skip element options like <assoc=right>
		for (int i = 0; i<sig.size(); i++) {
			Token t = sig.get(i);
			if ( t.getType()==ANTLRv4Lexer.LT ) angleDepth++;
			if ( t.getType()==ANTLRv4Lexer.GT && angleDepth>0 ) angleDepth--;
			if ( angleDepth>0 ) continue;

			boolean isLabel = i + 1<sig.size()
				&& (sig.get(i + 1).getType()==ANTLRv4Lexer.ASSIGN || sig.get(i + 1).getType()==ANTLRv4Lexer.PLUS_ASSIGN);
			String word = null;
			switch ( t.getType() ) {
				case ANTLRv4Lexer.RULE_REF:
				case ANTLRv4Lexer.TOKEN_REF:
					if ( !isLabel ) word = t.getText();
					break;
				case ANTLRv4Lexer.STRING_LITERAL:
					word = literalWord(t.getText());
					break;
				default:
					break;
			}
			if ( word!=null && (words.isEmpty() || !words.get(words.size() - 1).equalsIgnoreCase(word)) ) {
				words.add(word);
			}
		}

		// x sep x  ->  xList
		if ( words.size()==3 && words.get(0).equals(words.get(2)) ) {
			words = new ArrayList<>(Arrays.asList(words.get(0), "list"));
		}
		if ( words.size()>MAX_WORDS ) {
			words = new ArrayList<>(words.subList(0, MAX_WORDS));
		}
		if ( wrapper!=null && !words.isEmpty() ) {
			words.add(0, wrapper);
		}
		return words;
	}

	private static String wrapperWord(String open, String close) {
		if ( open.equals("'('") && close.equals("')'") ) return "paren";
		if ( open.equals("'['") && close.equals("']'") ) return "bracket";
		if ( open.equals("'{'") && close.equals("'}'") ) return "brace";
		if ( open.equals("'<'") && close.equals("'>'") ) return "angle";
		return null;
	}

	/** {@code 'while'} gives {@code while}, {@code ','} gives {@code COMMA}, others give null. */
	private static String literalWord(String literal) {
		String mapped = RefactorUtils.literalToRuleNameMap.get(literal);
		if ( mapped!=null ) return mapped;
		if ( literal.length()<3 ) return null;
		String body = literal.substring(1, literal.length() - 1);
		return body.matches("[A-Za-z][A-Za-z0-9_]*") ? body : null;
	}

	/** Splits camelCase and UPPER_SNAKE words into lower-case parts. */
	private static List<String> parts(String word) {
		List<String> parts = new ArrayList<>();
		for (String piece : word.split("_")) {
			for (String p : piece.split("(?<=[a-z0-9])(?=[A-Z])")) {
				if ( !p.isEmpty() ) parts.add(p.toLowerCase(Locale.ROOT));
			}
		}
		return parts;
	}

	private static String toCamel(List<String> words) {
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			for (String p : parts(w)) {
				sb.append(sb.length()==0 ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
			}
		}
		return sb.toString();
	}

	private static String toUpperSnake(List<String> words) {
		List<String> all = new ArrayList<>();
		for (String w : words) all.addAll(parts(w));
		return String.join("_", all).toUpperCase(Locale.ROOT);
	}

	private static String unique(String base, Set<String> existing) {
		if ( !existing.contains(base) ) return base;
		String sep = Character.isDigit(base.charAt(base.length() - 1)) ? "_" : "";
		for (int n = 2; ; n++) {
			String candidate = base + sep + n;
			if ( !existing.contains(candidate) ) return candidate;
		}
	}
}
