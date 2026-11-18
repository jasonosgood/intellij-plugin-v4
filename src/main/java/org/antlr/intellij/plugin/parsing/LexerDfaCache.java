package org.antlr.intellij.plugin.parsing;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** The lexer side of {@link ParserDfaCache}: one ATN, DFA and context cache per loaded lexer
 *  grammar, shared by every preview lex with it. {@code Grammar.createLexerInterpreter}
 *  round-trips the ATN through the serializer on every call. In three
 *  {@code PreviewPerformanceTest} runs with {@code antlr4-xl/grammar/refactorme.g4}
 *  (declared as {@code oracle_normal}), constructing an empty-input lexer averaged
 *  10–16 ms without this cache and 0.04–0.12 ms with it. Timings depend on the machine.
 *  <p>
 *  Prior art: generated lexers share a static {@code _decisionToDFA} and ATN, as generated
 *  parsers do. Keyed weakly by the grammar object, as {@link ParserDfaCache} is, for the same
 *  reasons; the cache must not reference its grammar.
 */
public final class LexerDfaCache {
	private static final Map<LexerGrammar, LexerDfaCache> CACHES = Collections.synchronizedMap(new WeakHashMap<>());

	private final String fileName;
	private final ATN atn;
	private final DFA[] decisionToDFA;
	private final PredictionContextCache contextCache = new PredictionContextCache();
	private final Vocabulary vocabulary;
	private final List<String> ruleNames, channelNames, modeNames;

	private LexerDfaCache(LexerGrammar lg) {
		fileName = lg.fileName;
		// as Grammar.createLexerInterpreter does: the round trip sets some ATN state flags
		atn = new ATNDeserializer().deserialize(ATNSerializer.getSerialized(lg.getATN()).toArray());
		decisionToDFA = new DFA[atn.getNumberOfDecisions()];
		for (int i = 0; i < decisionToDFA.length; i++) {
			decisionToDFA[i] = new DFA(atn.getDecisionState(i), i);
		}
		vocabulary = lg.getVocabulary();
		ruleNames = List.of(lg.getRuleNames());
		// by channel number, unlike createLexerInterpreter's (see LexingResult.channelNames)
		channelNames = Arrays.asList(LexingResult.channelNames(lg).toArray(new String[0]));
		modeNames = List.copyOf(lg.modes.keySet());
	}

	/** A lexer interpreter for {@code lg} over {@code input} that lexes with {@code lg}'s shared
	 *  cache, made on first use. Any thread.
	 */
	public static @NotNull LexerInterpreter newLexer(@NotNull LexerGrammar lg, @NotNull CharStream input) {
		LexerDfaCache cache = CACHES.computeIfAbsent(lg, LexerDfaCache::new);
		LexerInterpreter lexer = new LexerInterpreter(cache.fileName, cache.vocabulary, cache.ruleNames,
			cache.channelNames, cache.modeNames, cache.atn, input);
		lexer.setInterpreter(new LexerATNSimulator(lexer, cache.atn, cache.decisionToDFA, cache.contextCache));
		return lexer;
	}
}
