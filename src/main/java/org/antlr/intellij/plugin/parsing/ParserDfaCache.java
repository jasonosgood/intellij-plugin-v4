package org.antlr.intellij.plugin.parsing;

import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.tool.Grammar;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** The prediction cache (DFA) of a loaded parser grammar, shared by every preview parse with
 *  it. A parser that starts with an empty DFA repeats all prediction work: in three
 *  {@code PreviewPerformanceTest} runs with {@code antlr4-xl/grammar/refactorme.g4}
 *  (declared as {@code oracle_normal}), a one-line query took 362–759 ms cold and
 *  33–55 ms warm. These timings depend on the machine and input.
 *  <p>
 *  Prior art: generated ANTLR parsers share a static {@code _decisionToDFA} and
 *  {@code _sharedContextCache} among all instances and threads; the runtime synchronizes
 *  additions to them, and the ATN is likewise shared. This is the same, one per {@link Grammar}
 *  object instead of per class. {@code ParserInterpreter} gives each instance its own DFA ("not
 *  shared like it is for generated parsers"), so {@link #newParser} replaces its simulator.
 *  <p>
 *  Keyed weakly by the grammar object: a reload makes a new {@code Grammar}, so the cache
 *  never outlives the grammar it was built from and needs no invalidation. The cache must not
 *  reference its grammar, or the weak key would stay reachable.
 *  <p>
 *  The Profiler's numbers describe a warm parser accordingly (DFA states accumulate over the
 *  parses since the grammar loaded; cache misses fall), as in a long-running generated parser.
 */
public final class ParserDfaCache {
	private static final Map<Grammar, ParserDfaCache> CACHES = Collections.synchronizedMap(new WeakHashMap<>());

	private final ATN atn;
	private final DFA[] decisionToDFA;
	private final PredictionContextCache contextCache = new PredictionContextCache();

	private ParserDfaCache(ATN atn) {
		this.atn = atn;
		this.decisionToDFA = new DFA[atn.getNumberOfDecisions()];
		for (int i = 0; i < decisionToDFA.length; i++) {
			decisionToDFA[i] = new DFA(atn.getDecisionState(i), i);
		}
	}

	/** A parser for {@code g} over {@code tokens} that predicts with {@code g}'s shared cache,
	 *  made on first use. Set its prediction mode and profiling afterwards; profiling keeps the
	 *  cache. Any thread.
	 */
	public static @NotNull PreviewParser newParser(@NotNull Grammar g, @NotNull TokenStream tokens) {
		ParserDfaCache cache = of(g);
		PreviewParser parser = new PreviewParser(g, cache.atn, tokens);
		parser.setInterpreter(new ParserATNSimulator(parser, cache.atn, cache.decisionToDFA, cache.contextCache));
		return parser;
	}

	private static ParserDfaCache of(Grammar g) {
		return CACHES.computeIfAbsent(g, grammar -> {
			// as Grammar.createParserInterpreter does: the round trip sets some ATN state flags
			ATN atn = new ATNDeserializer().deserialize(ATNSerializer.getSerialized(grammar.getATN()).toArray());
			return new ParserDfaCache(atn);
		});
	}
}
