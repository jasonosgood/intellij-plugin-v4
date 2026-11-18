package org.antlr.intellij.plugin.parsing;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** The grammar service: loads grammars, in order, on one background thread, and caches the
 *  loaded grammars per grammar file, valid while the saved text of every file the load read
 *  is unchanged: the grammar, its lexer (by {@code tokenVocab} or by name, including one looked
 *  for but absent) and its imports.
 *  <p>
 *  Prior art: content-keyed caches, as in Bazel's and Gradle's build caches (inputs compared by
 *  content, not timestamps or change events), and {@code ExternalAnnotator}'s split between a
 *  short read-locked collection and lock-free heavy work. Deviation from the platform's usual
 *  answer, {@code CachedValuesManager} with PSI dependencies: that computes inside a read
 *  action, and loading a large grammar (ANTLR's full analysis, 3.3–4.8 s in three
 *  {@code PreviewPerformanceTest} runs with {@code antlr4-xl/grammar/refactorme.g4}, declared
 *  as {@code oracle_normal}) can't be cancelled, so it would block write actions (typing) for
 *  the whole load. Comparing content also ignores the content-change event that arrives after a
 *  project opens with nothing edited.
 *  <p>
 *  Texts are saved texts, not editor text: the preview reloads on save only
 *  (doc/IN_PROGRESS.md), and the ANTLR Tool reads imports from disk anyway.
 *  <p>
 *  Threads: {@link #get} loads, so call it off the EDT, normally through {@link #submit}. The
 *  map is concurrent; a grammar loaded twice at once just stores the later result.
 *  <p>
 *  Loading runs ANTLR's full analysis, seconds on large grammars, so it never runs on the EDT.
 *  Loads run in order on one thread, so an earlier load never overwrites a later one's result.
 *  Prior art: the platform's threading guide recommends {@code ReadAction.nonBlocking} for
 *  background work; not used here because it holds the read lock and ANTLR's Tool can't be
 *  cancelled, so a write action (typing) would wait for the whole load (see DECISIONS.md).
 */
@Service(Service.Level.PROJECT)
public final class GrammarCache implements Disposable {
	/** The grammars loaded from a file; either may be null (lexer alone, or a failed load).
	 *  {@code inputs}: path of every file read to saved text, null for a file that was absent.
	 *  {@code problem}: why the grammars can't be previewed, or null if they can.
	 */
	public record Loaded(@Nullable LexerGrammar lexer, @Nullable Grammar parser, Map<String, String> inputs,
						 @Nullable LoadProblem problem) {
		public boolean dependsOn(VirtualFile file) {
			return inputs.containsKey(file.getPath());
		}
	}

	private final Project project;
	private final Map<VirtualFile, Loaded> entries = new ConcurrentHashMap<>();
	private final ExecutorService loader =
		AppExecutorUtil.createBoundedApplicationPoolExecutor("ANTLR grammar loader", 1);

	public GrammarCache(Project project) {
		this.project = project;
	}

	public static GrammarCache getInstance(Project project) {
		return project.getService(GrammarCache.class);
	}

	/** Runs {@code work} (which loads grammars with {@link #get}) on the grammar loader thread,
	 *  after all work submitted before it. Completes with null if the project is closing.
	 */
	public <T> CompletableFuture<T> submit(@NotNull Supplier<T> work) {
		try {
			return CompletableFuture.supplyAsync(work, loader);
		}
		catch (RejectedExecutionException closing) {
			return CompletableFuture.completedFuture(null);
		}
	}

	@Override
	public void dispose() {
		loader.shutdownNow();
	}

	/** The grammars loaded from {@code file}: the cached ones if still fresh, else loaded now. */
	public @NotNull Loaded get(@NotNull VirtualFile file) {
		Loaded cached = entries.get(file);
		if ( cached!=null && isFresh(cached) ) return cached;
		ApplicationManager.getApplication().assertIsNonDispatchThread();
		Map<String, String> inputs = new HashMap<>();
		List<LoadProblem> problems = new ArrayList<>();
		Grammar[] grammars = ParsingUtils.loadGrammars(file, project, inputs, problems);
		// the first problem is the cause; a failed load always has one
		LoadProblem problem = !problems.isEmpty() ? problems.getFirst()
			: grammars==null ? LoadProblem.errorsIn(file) : null;
		Loaded loaded = grammars==null
			? new Loaded(null, null, Collections.unmodifiableMap(inputs), problem)
			: new Loaded((LexerGrammar) grammars[0], grammars[1], Collections.unmodifiableMap(inputs), problem);
		entries.put(file, loaded);
		return loaded;
	}

	/** The cached grammars for {@code file}, fresh or not, without loading; null if none. */
	public @Nullable Loaded peek(@NotNull VirtualFile file) {
		return entries.get(file);
	}

	/** True if {@code file} has no cached grammars or an input changed since they were loaded. */
	public boolean isStale(@NotNull VirtualFile file) {
		Loaded cached = entries.get(file);
		return cached==null || !isFresh(cached);
	}

	public void remove(@NotNull VirtualFile file) {
		entries.remove(file);
	}

	private static boolean isFresh(Loaded loaded) {
		for ( Map.Entry<String, String> input : loaded.inputs.entrySet() ) {
			if ( !Objects.equals(input.getValue(), ParsingUtils.savedText(input.getKey())) ) return false;
		}
		return true;
	}
}
