package org.antlr.intellij.plugin.adaptors;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.antlr.intellij.plugin.ANTLRv4TokenTypes;
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Interval;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import xl.ANTLR4Lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/** The editor's highlighting lexer: antlr4-xl's {@code ANTLR4Lexer}, whose tokens it reports as
 *  the plugin's token types, so the highlighter, the brace matcher and the comment tokens of
 *  {@code ANTLRv4ParserDefinition} work on them unchanged. The PSI keeps the plugin's own lexer.
 *  <p>
 *  Why: the editor restarts its highlighting lexer at an earlier token after each edit, with
 *  that token's saved state, and lexes on until a token's offset and state match the old ones.
 *  The plugin's lexer has modes and fields its saved state didn't fully capture, so restarts in
 *  {@code options { }} could lex the rest of the file in the wrong mode. xl's lexer has no modes,
 *  members or predicates: its state is always 0 and it restarts at any token.
 *  <p>
 *  Prior art: the platform keeps the highlighting lexer ({@code SyntaxHighlighter}) apart from
 *  the parsing lexer ({@code ParserDefinition}), and a stateless lexer is the easiest kind to
 *  restart. Actions, predicates and {@code [...]} are target code, one uncolored token each; to
 *  highlight that code one day, layer a lexer over those tokens ({@code LayeredLexer}).
 *  <p>
 *  Text xl's lexer can't match becomes one {@code BAD_TOKEN_TYPE} character, and lexing resumes
 *  after it, as IntelliJ lexers recover: an unclosed brace or comment marks one character, not
 *  the rest of the file.
 */
public final class ANTLRv4HighlightingLexer extends LexerBase {
	/** xl token (literal or symbolic name) to plugin token, where they differ. xl's keywords map
	 *  by their literal to the plugin's keywords; its other words to {@code RULE_REF}, as the
	 *  plugin's lexer reads them ({@code skip}, {@code channel}, ...).
	 */
	private static final Map<String, Integer> XL_TO_PLUGIN = Map.ofEntries(
		entry("';'", ANTLRv4Lexer.SEMI),
		entry("'}'", ANTLRv4Lexer.RBRACE),
		entry("'='", ANTLRv4Lexer.ASSIGN),
		entry("','", ANTLRv4Lexer.COMMA),
		entry("'@'", ANTLRv4Lexer.AT),
		entry("'::'", ANTLRv4Lexer.COLONCOLON),
		entry("':'", ANTLRv4Lexer.COLON),
		entry("'|'", ANTLRv4Lexer.OR),
		entry("'#'", ANTLRv4Lexer.POUND),
		entry("'('", ANTLRv4Lexer.LPAREN),
		entry("')'", ANTLRv4Lexer.RPAREN),
		entry("'~'", ANTLRv4Lexer.NOT),
		entry("'<'", ANTLRv4Lexer.LT),
		entry("'>'", ANTLRv4Lexer.GT),
		entry("'+='", ANTLRv4Lexer.PLUS_ASSIGN),
		entry("'.'", ANTLRv4Lexer.DOT),
		entry("'->'", ANTLRv4Lexer.RARROW),
		entry("'..'", ANTLRv4Lexer.RANGE),
		entry("PISH", ANTLRv4Lexer.OPTIONS),   // 'options' '{'
		entry("BPTH", ANTLRv4Lexer.TOKENS),    // 'tokens' '{'
		entry("OINK", ANTLRv4Lexer.CHANNELS),  // 'channels' '{'
		entry("KLEENE", ANTLRv4Lexer.STAR),    // ? * + and their non-greedy forms; uncolored
		entry("RULE", ANTLRv4Lexer.RULE_REF),
		entry("TOKEN", ANTLRv4Lexer.TOKEN_REF),
		entry("SYMBOL", ANTLRv4Lexer.STRING_LITERAL),
		entry("INT", ANTLRv4Lexer.INT),
		entry("SQUARE", ANTLRv4Lexer.ARGUMENT_CONTENT),  // arguments, returns, locals, char sets
		entry("SEMPRED", ANTLRv4Lexer.ACTION_CONTENT),
		entry("ACTION", ANTLRv4Lexer.ACTION_CONTENT),
		entry("JAVADOC", ANTLRv4Lexer.DOC_COMMENT),
		entry("BLOCK", ANTLRv4Lexer.BLOCK_COMMENT),
		entry("COMMENT", ANTLRv4Lexer.LINE_COMMENT),
		entry("WS", ANTLRv4Lexer.WS));

	/** Plugin token type per xl token type; null where unmapped (a test checks there is none). */
	private static final IElementType[] TYPES = mapTypes();

	private CharSequence buffer;
	private int endOffset;
	private ANTLR4Lexer lexer;
	/** The next xl token, read ahead past text it couldn't match; null if none read. */
	private @Nullable Token next;
	private int tokenStart;
	private int tokenEnd;
	private @Nullable IElementType tokenType;

	@Override
	public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
		this.buffer = buffer;
		this.endOffset = endOffset;
		lexFrom(startOffset);
		tokenEnd = startOffset;
		advance();
	}

	private void lexFrom(int offset) {
		lexer = new ANTLR4Lexer(new CharSequenceStream(buffer, offset, endOffset));
		lexer.removeErrorListeners(); // errors become BAD_TOKEN_TYPE characters; nothing is printed
		next = null;
	}

	@Override
	public void advance() {
		tokenStart = tokenEnd;
		if ( tokenStart>=endOffset ) {
			tokenType = null;
			return;
		}
		if ( next==null ) next = lexer.nextToken();
		boolean gap = next.getType()==Token.EOF || next.getStartIndex()>tokenStart;
		if ( gap ) { // xl's lexer dropped text it couldn't match: mark one character, resume after it
			tokenType = ANTLRv4TokenTypes.BAD_TOKEN_TYPE;
			tokenEnd = tokenStart + 1;
			lexFrom(tokenEnd);
			return;
		}
		tokenType = typeOf(next.getType());
		tokenEnd = next.getStopIndex() + 1;
		next = null;
	}

	@Override
	public int getState() {
		return 0; // xl's lexer has no state
	}

	@Override
	public @Nullable IElementType getTokenType() {
		return tokenType;
	}

	@Override
	public int getTokenStart() {
		return tokenStart;
	}

	@Override
	public int getTokenEnd() {
		return tokenEnd;
	}

	@Override
	public @NotNull CharSequence getBufferSequence() {
		return buffer;
	}

	@Override
	public int getBufferEnd() {
		return endOffset;
	}

	private static IElementType typeOf(int xlType) {
		IElementType type = xlType>=0 && xlType<TYPES.length ? TYPES[xlType] : null;
		return type!=null ? type : ANTLRv4TokenTypes.BAD_TOKEN_TYPE;
	}

	/** The plugin token type for each xl token type, by name: {@link #XL_TO_PLUGIN}, else a
	 *  keyword with the same literal, else {@code RULE_REF} for any other word.
	 */
	private static IElementType[] mapTypes() {
		Vocabulary xl = ANTLR4Lexer.VOCABULARY;
		Vocabulary plugin = ANTLRv4Lexer.VOCABULARY;
		IElementType[] types = new IElementType[xl.getMaxTokenType() + 1];
		for (int t = 1; t<=xl.getMaxTokenType(); t++) {
			String literal = xl.getLiteralName(t);
			Integer pluginType = XL_TO_PLUGIN.get(literal!=null ? literal : xl.getSymbolicName(t));
			if ( pluginType==null && literal!=null ) {
				pluginType = pluginTypeWithLiteral(plugin, literal);
				if ( pluginType==null && isWord(literal) ) pluginType = ANTLRv4Lexer.RULE_REF;
			}
			if ( pluginType!=null ) types[t] = ANTLRv4TokenTypes.TOKEN_ELEMENT_TYPES.get(pluginType);
		}
		return types;
	}

	private static @Nullable Integer pluginTypeWithLiteral(Vocabulary plugin, String literal) {
		for (int t = 1; t<=plugin.getMaxTokenType(); t++) {
			if ( literal.equals(plugin.getLiteralName(t)) ) return t;
		}
		return null;
	}

	/** Whether {@code literal} (quoted, as in a vocabulary) is a word such as {@code 'skip'}. */
	private static boolean isWord(String literal) {
		return literal.length()>2 && literal.chars().skip(1).limit(literal.length() - 2).allMatch(Character::isLetter);
	}

	/** xl token types with no plugin token type, by name; empty when the mapping is complete. */
	@TestOnly
	static List<String> unmappedXlTokens() {
		List<String> unmapped = new ArrayList<>();
		for (int t = 1; t<TYPES.length; t++) {
			if ( TYPES[t]==null ) unmapped.add(ANTLR4Lexer.VOCABULARY.getDisplayName(t));
		}
		return unmapped;
	}

	/** The editor's text up to {@code end}, read in place, indexed in UTF-16 units as editor
	 *  offsets are ({@code CharStreams} index code points).
	 */
	private static final class CharSequenceStream implements CharStream {
		private final CharSequence text;
		private final int end;
		private int index;

		CharSequenceStream(CharSequence text, int start, int end) {
			this.text = text;
			this.end = end;
			this.index = start;
		}

		@Override
		public void consume() {
			if ( index>=end ) throw new IllegalStateException("cannot consume EOF");
			index++;
		}

		@Override
		public int LA(int i) {
			if ( i==0 ) return 0; // undefined
			int at = i>0 ? index + i - 1 : index + i;
			return at<0 || at>=end ? IntStream.EOF : text.charAt(at);
		}

		@Override
		public int mark() {
			return -1; // the whole text stays available
		}

		@Override
		public void release(int marker) {
		}

		@Override
		public int index() {
			return index;
		}

		@Override
		public void seek(int index) {
			this.index = Math.min(index, end);
		}

		@Override
		public int size() {
			return end;
		}

		@Override
		public String getSourceName() {
			return IntStream.UNKNOWN_SOURCE_NAME;
		}

		@Override
		public String getText(Interval interval) {
			int from = Math.max(interval.a, 0);
			int to = Math.min(interval.b + 1, end);
			return from>=to ? "" : text.subSequence(from, to).toString();
		}
	}
}
