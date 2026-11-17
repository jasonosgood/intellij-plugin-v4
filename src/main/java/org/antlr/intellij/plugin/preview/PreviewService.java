package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AppUIExecutor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.intellij.plugin.parsing.GrammarCache;
import org.antlr.intellij.plugin.parsing.LexingResult;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.concurrency.CancellablePromise;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** The preview's model: per grammar file, the loaded grammars, the start rule and the last
 *  parse ({@link PreviewState}); grammar loads (through {@link GrammarCache}) and parse jobs.
 *  It publishes what happens on {@link PreviewListener#TOPIC} and never calls the UI; the
 *  preview panel subscribes, and keeps the input text itself. Editor events arrive through
 *  {@link org.antlr.intellij.plugin.GrammarEditorTabListener}.
 *  <p>
 *  Prior art: the platform's services + message bus topics (the "migrating components to
 *  services" guide; {@code FileEditorManagerListener}). A project service, so one per project,
 *  disposed when the project closes; its listeners and jobs are registered against it.
 *  Deviation from the plan in doc/IN_PROGRESS.md: input text stays with the panel, which owns
 *  the input editor (step 4); a parse is given its text.
 */
@Service(Service.Level.PROJECT)
public final class PreviewService implements Disposable {
	private static final Logger LOG = Logger.getInstance(PreviewService.class);

	/** Delay before offering to cancel a slow parse (the platform's progress postpone time). */
	private static final int SLOW_PARSE_NOTICE_DELAY_MS = 300;

	private final Project project;

	/** Disposed with this service: grammar loads, parses and their UI follow-ups expire with it. */
	private final CheckedDisposable lifetime = Disposer.newCheckedDisposable(this, "ANTLR preview service");

	/** Preview state per grammar file; keyed by file, not path, so state follows renames and
	 *  moves. Written on the EDT only, by replacing a grammar's immutable snapshot; read from any
	 *  thread. Prior art: one owning thread per piece of mutable state (the platform's threading
	 *  model); the concurrent map only publishes snapshots safely to background readers.
	 */
	private final Map<VirtualFile, PreviewState> previewStates = new ConcurrentHashMap<>();

	/** The running or scheduled parse; EDT only. */
	private CancellablePromise<?> parsing;

	/** The latest grammar load, through its EDT follow-up. */
	private volatile CompletableFuture<?> lastGrammarLoad = CompletableFuture.completedFuture(null);

	/** Reload a grammar after each highlighting pass, not only on save (toolbar toggle). */
	private volatile boolean autoRefresh = true;

	public PreviewService(Project project) {
		this.project = project;
		// VFS events are application-wide; disconnected when this service is disposed
		ApplicationManager.getApplication().getMessageBus().connect(this)
			.subscribe(VirtualFileManager.VFS_CHANGES, new GrammarFileListener());
	}

	public static PreviewService getInstance(@NotNull Project project) {
		return project.getService(PreviewService.class);
	}

	@Override
	public void dispose() {
		LOG.debug("dispose " + project.getName()); // lifetime, a child, is already disposed
	}

	// ------------------------------ state

	/** The preview state of {@code grammarFile}, empty if it isn't loaded. Any thread. */
	public @NotNull PreviewState getPreviewState(@NotNull VirtualFile grammarFile) {
		PreviewState state = previewStates.get(grammarFile);
		return state!=null ? state : PreviewState.empty(grammarFile);
	}

	/** Whether the preview holds state for {@code grammarFile}; closing a grammar drops it. */
	@TestOnly
	public boolean hasPreviewState(@NotNull VirtualFile grammarFile) {
		return previewStates.containsKey(grammarFile);
	}

	/** Replaces the preview state of {@code grammarFile}. EDT only. */
	private void updatePreviewState(@NotNull VirtualFile grammarFile, UnaryOperator<PreviewState> update) {
		ApplicationManager.getApplication().assertIsDispatchThread();
		previewStates.put(grammarFile, update.apply(getPreviewState(grammarFile)));
	}

	private PreviewListener publisher() {
		return project.getMessageBus().syncPublisher(PreviewListener.TOPIC);
	}

	public boolean isAutoRefresh() {
		return autoRefresh;
	}

	public void setAutoRefresh(boolean autoRefresh) {
		this.autoRefresh = autoRefresh;
	}

	// ------------------------------ events

	/** The user chose {@code startRule} for {@code grammarFile} (Test Rule). EDT only. */
	public void setStartRule(@NotNull VirtualFile grammarFile, String startRule) {
		LOG.debug("setStartRule " + startRule + " " + project.getName());
		updatePreviewState(grammarFile, s -> s.withStartRule(startRule));
		publisher().startRuleChanged(grammarFile);
	}

	/** The start rule of {@code grammarFile} was renamed in its text; the preview uses the new
	 *  name once the grammar is saved and reloaded. Called by {@link
	 *  StartRuleRenameListenerProvider}. EDT only.
	 */
	public void startRuleRenamed(@NotNull VirtualFile grammarFile, String newName) {
		if ( !previewStates.containsKey(grammarFile) ) return; // closed since
		LOG.debug("startRuleRenamed " + newName + " " + project.getName());
		updatePreviewState(grammarFile, s -> s.withRenamedStartRule(newName));
	}

	/** {@code grammarFile} became the selected grammar: loads it unless its cached grammars are
	 *  fresh, then publishes {@link PreviewListener#grammarSelected}.
	 */
	public void grammarSelected(@NotNull VirtualFile grammarFile) {
		LOG.debug("grammarSelected " + grammarFile.getPath() + " " + project.getName());
		runGrammarLoad(
			() -> Map.of(grammarFile, GrammarCache.getInstance(project).get(grammarFile)),
			() -> publisher().grammarSelected(grammarFile));
	}

	/** {@code grammarFile}'s tab closed: drops its state and cached grammars. EDT only. */
	public void grammarClosed(@NotNull VirtualFile grammarFile) {
		LOG.debug("grammarClosed " + grammarFile.getPath() + " " + project.getName());
		if ( previewStates.remove(grammarFile)!=null ) {
			publisher().grammarClosed(grammarFile);
			GrammarCache.getInstance(project).remove(grammarFile);
		}
		publisher().grammarDeselected();
	}

	/** No grammar is selected any more. EDT only. */
	public void grammarDeselected() {
		publisher().grammarDeselected();
	}

	/** Highlighting finished for {@code grammarFile}: with auto-refresh on, treat it like a save
	 *  (which reloads only if a saved text changed).
	 */
	public void grammarHighlighted(@NotNull VirtualFile grammarFile) {
		PreviewState state = getPreviewState(grammarFile);
		if ( autoRefresh && (state.startRule()!=null || state.isLexerOnly()) ) {
			ApplicationManager.getApplication().invokeLater(() -> grammarSaved(grammarFile), project.getDisposed());
		}
	}

	/** Grammar files changed on disk: saved in the IDE, created, or changed outside it (VCS
	 *  checkout, another editor). Reloads those this preview uses: a previewed grammar, or a
	 *  grammar a previewed one read when it loaded ({@code tokenVocab} lexer, imports), whether
	 *  or not it is open in an editor. Others, including other projects' grammars, are ignored.
	 *  Prior art: VFS listeners see every change to a file, whatever caused it.
	 */
	public void grammarFilesChanged(@NotNull Collection<VirtualFile> files) {
		for (VirtualFile file : files) {
			if ( isPreviewInput(file) ) grammarSaved(file);
		}
	}

	/** Whether {@code file} is a previewed grammar or one a previewed grammar's load read. */
	private boolean isPreviewInput(@NotNull VirtualFile file) {
		if ( previewStates.containsKey(file) ) return true;
		GrammarCache cache = GrammarCache.getInstance(project);
		return previewStates.keySet().stream().anyMatch(f -> {
			GrammarCache.Loaded loaded = cache.peek(f);
			return loaded!=null && loaded.dependsOn(file);
		});
	}

	/** A grammar was saved (or may have been: auto-refresh calls this after each highlighting
	 *  pass). Reloads every previewed grammar whose inputs changed, the saved one first (if it is
	 *  previewed itself); does nothing if none did. Superseded saves need no bookkeeping: a
	 *  queued load finds the cache fresh.
	 */
	public void grammarSaved(@NotNull VirtualFile grammarFile) {
		runGrammarLoad(
			() -> {
				GrammarCache cache = GrammarCache.getInstance(project);
				// a lexer used by a previewed parser needn't be previewed itself, nor open
				boolean previewed = previewStates.containsKey(grammarFile);
				boolean savedStale = previewed && cache.isStale(grammarFile);
				List<VirtualFile> dependents = previewStates.keySet().stream()
					.filter(f -> !f.equals(grammarFile))
					.filter(f -> {
						GrammarCache.Loaded loaded = cache.peek(f);
						return loaded!=null && loaded.dependsOn(grammarFile) && cache.isStale(f);
					})
					.toList();
				if ( !savedStale && dependents.isEmpty() ) {
					LOG.debug("grammarSaved " + grammarFile.getPath() + ": no grammar changed");
					return null;
				}
				LOG.debug("grammarSaved " + grammarFile.getPath() + " " + project.getName());
				Map<VirtualFile, GrammarCache.Loaded> loads = new LinkedHashMap<>();
				if ( previewed ) loads.put(grammarFile, cache.get(grammarFile));
				// no code generation here: the preview and the editor's checks read a lexer's
				// tokens from the lexer grammar itself, so a save writes nothing
				for (VirtualFile f : dependents) {
					loads.put(f, cache.get(f));
				}
				return loads;
			},
			() -> publisher().grammarReloaded(grammarFile));
	}

	/** Grammar files were deleted, moved or renamed away from {@code paths}: reloads the
	 *  previewed grammars that read them (a {@code tokenVocab} lexer, imports). A previewed
	 *  grammar that is itself deleted closes with its tab.
	 */
	public void grammarFilesRemoved(@NotNull Collection<String> paths) {
		List<VirtualFile> reloaded = new ArrayList<>();
		runGrammarLoad(
			() -> {
				GrammarCache cache = GrammarCache.getInstance(project);
				Map<VirtualFile, GrammarCache.Loaded> loads = new LinkedHashMap<>();
				for (VirtualFile f : previewStates.keySet()) {
					GrammarCache.Loaded loaded = cache.peek(f);
					if ( f.isValid() && loaded!=null && paths.stream().anyMatch(loaded.inputs()::containsKey) && cache.isStale(f) ) {
						loads.put(f, cache.get(f));
					}
				}
				if ( loads.isEmpty() ) return null;
				LOG.debug("grammarFilesRemoved " + paths + ": reloading " + loads.keySet() + " " + project.getName());
				reloaded.addAll(loads.keySet());
				return loads;
			},
			() -> reloaded.forEach(f -> publisher().grammarReloaded(f)));
	}

	/** Run {@code load} on the grammar loader thread; unless it returns null, put the grammars
	 *  it loaded into the preview states and run {@code thenOnEdt}, both on the EDT. Skipped once
	 *  this service is disposed.
	 */
	private void runGrammarLoad(Supplier<Map<VirtualFile, GrammarCache.Loaded>> load, Runnable thenOnEdt) {
		if ( lifetime.isDisposed() ) return;
		AppUIExecutor edt = AppUIExecutor.onUiThread(ModalityState.defaultModalityState()).expireWith(lifetime);
		lastGrammarLoad = GrammarCache.getInstance(project)
			.submit(() -> lifetime.isDisposed() ? null : load.get())
			.thenAcceptAsync(loads -> {
				if ( loads==null ) return;
				loads.forEach((file, loaded) ->
					updatePreviewState(file, s -> s.withGrammars(loaded.lexer(), loaded.parser())));
				thenOnEdt.run();
			}, edt::execute)
			.whenComplete((ignored, t) -> {
				if ( t!=null && !lifetime.isDisposed() ) { // teardown can break a load midway; ignore that
					LOG.error("Failed to load grammar", t instanceof CompletionException ? t.getCause() : t);
				}
			});
	}

	/** Completes once every grammar load requested so far, and its EDT follow-up, has finished. */
	@TestOnly
	public Future<?> grammarLoadsDone() {
		return lastGrammarLoad;
	}

	// ------------------------------ parsing

	/** Parses {@code inputText} with the grammar's start rule in the background and publishes
	 *  the result, with the whole token stream; a lexer grammar alone only tokenizes it. A
	 *  newer parse cancels this one. The parse runs in a non-blocking read action:
	 *  {@code PreviewParser} checks for cancellation at every decision, so a write action cancels
	 *  and restarts it rather than waiting for it (lexing doesn't check, but is linear and
	 *  quick). Prior art: the platform's threading guide
	 *  ({@code ReadAction.nonBlocking} with {@code coalesceBy}, {@code expireWith},
	 *  {@code finishOnUiThread}).
	 *  <p>
	 *  The parse uses the grammar's snapshot taken now. Its result is published only if the
	 *  grammars and start rule are still those when it finishes; if they changed, the change
	 *  started another parse. EDT only.
	 */
	public void parse(@NotNull VirtualFile grammarFile, @NotNull String inputText) {
		ApplicationManager.getApplication().assertIsDispatchThread();
		if ( lifetime.isDisposed() ) return;
		// No need to parse empty text during unit tests, yet...
		if ( inputText.isEmpty() && ApplicationManager.getApplication().isUnitTestMode() ) return;

		final PreviewState snapshot = getPreviewState(grammarFile);
		CancellablePromise<Parse> promise = ReadAction
			.nonBlocking(() -> {
				long start = System.nanoTime();
				if ( snapshot.isLexerOnly() ) {
					LexingResult lexed = ParsingUtils.lexText(snapshot.lexerGrammar(), grammarFile, inputText, project);
					return new Parse(null, lexed, System.nanoTime() - start);
				}
				ParsingResult result = ParsingUtils.parseText(snapshot.parserGrammar(), snapshot.lexerGrammar(),
															  snapshot.startRule(), grammarFile, inputText, project);
				long nanos = System.nanoTime() - start; // the parse alone, as the profiler reports it
				LexingResult lexed = result==null ? null
					: LexingResult.of((CommonTokenStream) result.parser.getInputStream(), result.syntaxErrorListener,
									  snapshot.lexerGrammar());
				return new Parse(result, lexed, nanos);
			})
			.coalesceBy(this) // one parse at a time; the newest input wins
			.expireWith(lifetime)
			.finishOnUiThread(ModalityState.defaultModalityState(), parse -> {
				if ( !getPreviewState(grammarFile).sameInputsAs(snapshot) ) return; // superseded
				PreviewState parsed = snapshot.withResults(parse.result(), parse.tokens());
				previewStates.put(grammarFile, parsed);
				publisher().parseCompleted(parsed, parse.nanos());
			})
			.submit(AppExecutorUtil.getAppExecutorService());
		parsing = promise;

		AppUIExecutor edt = AppUIExecutor.onUiThread(ModalityState.defaultModalityState()).expireWith(lifetime);
		AppExecutorUtil.getAppScheduledExecutorService().schedule(
			() -> edt.execute(() -> { if ( !promise.isDone() ) publisher().parseSlow(); }),
			SLOW_PARSE_NOTICE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	private record Parse(ParsingResult result, LexingResult tokens, long nanos) {}

	/** Cancels the running parse, if any. EDT only. */
	public void cancelParse() {
		if ( parsing!=null && !parsing.isDone() ) {
			parsing.cancel();
			parsing = null;
			publisher().parseCancelled();
		}
	}

	// ------------------------------

	/** Turns VFS events for grammar files into {@link #grammarFilesChanged} and
	 *  {@link #grammarFilesRemoved}. A move or rename is both: away from the old path, to the
	 *  new one (where a grammar may have looked for an absent lexer).
	 */
	private class GrammarFileListener implements BulkFileListener {
		@Override
		public void after(@NotNull List<? extends VFileEvent> events) {
			if ( ApplicationManager.getApplication().isUnitTestMode() ) return;
			List<VirtualFile> changed = new ArrayList<>();
			List<String> removed = new ArrayList<>();
			for (VFileEvent event : events) {
				if ( event instanceof VFileContentChangeEvent || event instanceof VFileCreateEvent ) {
					VirtualFile vfile = event.getFile();
					if ( vfile!=null && isGrammar(vfile.getName()) ) changed.add(vfile);
				}
				else if ( event instanceof VFileDeleteEvent && isGrammar(event.getPath()) ) {
					removed.add(event.getPath());
				}
				else if ( event instanceof VFileMoveEvent move && isGrammar(move.getOldPath()) ) {
					removed.add(move.getOldPath());
					changed.add(move.getFile());
				}
				else if ( event instanceof VFilePropertyChangeEvent rename && rename.isRename()
						  && (isGrammar(rename.getOldPath()) || isGrammar(rename.getNewPath())) ) {
					removed.add(rename.getOldPath());
					changed.add(rename.getFile());
				}
			}
			// VFS events are application-wide; both calls ignore other projects' grammars
			if ( !removed.isEmpty() ) grammarFilesRemoved(removed);
			if ( !changed.isEmpty() ) grammarFilesChanged(changed);
		}

		private static boolean isGrammar(String nameOrPath) {
			return nameOrPath.endsWith(".g4");
		}
	}
}
