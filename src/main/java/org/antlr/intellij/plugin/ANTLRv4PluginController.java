package org.antlr.intellij.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AppUIExecutor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.antlr.intellij.plugin.parsing.GrammarCache;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.antlr.intellij.plugin.preview.PreviewState;
import org.antlr.intellij.plugin.profiler.ProfilerPanel;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** This object is the controller for the ANTLR plug-in. It receives
 *  events and can send them on to its contained components. For example,
 *  saving the grammar editor or flipping to a new grammar sends an event
 *  to this object, which forwards on update events to the preview tool window.
 *
 *  The main components are related to the console tool window forever output and
 *  the main panel of the preview tool window.
 *
 *  This controller also manages the cache of grammar/editor combinations
 *  needed for the preview window. Updates must be made atomically so that
 *  the grammars and editors are consistently associated with the same window.
 *  <p>
 *  Lifecycle (prior art: the platform's "migrating components to services" guide): a
 *  project service, so there is exactly one per project and the platform disposes it when
 *  the project closes. It holds model state only (grammars, preview state, buffered tool
 *  output); listeners are registered against it as parent {@link Disposable}, so there is
 *  no manual uninstall. The UI (preview panel, input editors, console) belongs to the tool
 *  windows: {@link ANTLRv4ToolWindowFactory} creates it, disposes it with the tool window
 *  content, and attaches it here ({@link #attachPreviewPanel}, {@link ToolOutput#attach}).
 *  Editor tab events arrive through {@link GrammarEditorTabListener}, declared in plugin.xml.
 */
@Service(Service.Level.PROJECT)
public final class ANTLRv4PluginController implements Disposable {
	/** Delay before telling the user that parsing is slow (the platform's progress postpone time). */
	private static final int SLOW_PARSE_NOTICE_DELAY_MS = 300;

	public static final String PLUGIN_ID = "org.antlr.intellij.plugin";

	public static final Key<GrammarEditorMouseListener> EDITOR_MOUSE_LISTENER_KEY = Key.create("EDITOR_MOUSE_LISTENER_KEY");
	public static final Logger LOG = Logger.getInstance("ANTLRv4PluginController");

	public static final String PREVIEW_WINDOW_ID = "ANTLR Preview";
	public static final String CONSOLE_WINDOW_ID = "Tool Output";

	/** Disposed with this service: grammar loads and their UI follow-ups expire with it. */
	private final CheckedDisposable lifetime = Disposer.newCheckedDisposable(this, "ANTLR plugin controller");

	public final Project project;
	private final ToolOutput console = new ToolOutput();
	private boolean opened; // projectOpened() has run; EDT only
	/** Grammar attachPreviewPanel() replayed a selection for; the platform's own event for it is skipped. EDT only. */
	private VirtualFile replayedStartupGrammar;

	/** Preview state per grammar file; keyed by file, not path, so state follows renames and moves. */
	public final Map<VirtualFile, PreviewState> grammarToPreviewState =
		Collections.synchronizedMap(new HashMap<>());
	/** The preview tool window's panel, while it exists; EDT only. */
	private PreviewPanel previewPanel;

	/** Tests can substitute the preview tool window; null means look it up. */
	@TestOnly
	public ToolWindow previewWindowForTests;

	/** The running or scheduled preview parse; EDT only. */
	private CancellablePromise<?> parsing;

	/** Loading a grammar runs ANTLR's full analysis pipeline, which can take seconds on large
	 *  grammars, so it must never run on the EDT. Loads run in order on this one background
	 *  thread, so an earlier load never overwrites a later one's grammars.
	 *  <p>
	 *  Prior art: the platform's threading guide recommends {@code ReadAction.nonBlocking} for
	 *  background work; it is not used here because it holds the read lock, and ANTLR's Tool
	 *  can't be cancelled, so a write action (typing) would wait for the whole load. See
	 *  {@link GrammarCache}. Parses, which check for cancellation, do use it ({@link #parseText}).
	 */
	private final ExecutorService grammarLoader =
		AppExecutorUtil.createBoundedApplicationPoolExecutor("ANTLR grammar loader", 1);

	/** The latest grammar load, through its UI follow-up. */
	private volatile CompletableFuture<?> lastGrammarLoad = CompletableFuture.completedFuture(null);

	public ANTLRv4PluginController(Project project) {
		this.project = project;
	}

	public static ANTLRv4PluginController getInstance(Project project) {
		if ( project==null ) {
			LOG.error("getInstance: project is null");
			return null;
		}
		if ( project.isDisposed() ) {
			LOG.error("getInstance: project is already disposed");
			return null;
		}
		return project.getService(ANTLRv4PluginController.class);
	}

	/** Installs listeners. Runs once, on the EDT, when the project opens. */
	public void projectOpened() {
		ApplicationManager.getApplication().assertIsDispatchThread();
		if ( opened || lifetime.isDisposed() ) return;
		opened = true;
		LOG.info("ANTLR 4 Plugin opened for "+project.getName()+", Java version "+ SystemInfo.JAVA_VERSION);
		installListeners();
	}

	/** The preview tool window's panel now exists; it detaches itself when disposed. EDT only. */
	public void attachPreviewPanel(@NotNull PreviewPanel panel) {
		ApplicationManager.getApplication().assertIsDispatchThread();
		if ( lifetime.isDisposed() ) return;
		previewPanel = panel;
		// The current grammar was selected before the panel existed. Replay its selection; if
		// the platform's own selection event for it is still on its way (startup),
		// currentEditorFileChangedEvent skips that one.
		VirtualFile current = getCurrentGrammarFile();
		if ( current!=null ) {
			currentEditorFileChangedEvent(null, current);
			replayedStartupGrammar = current;
		}
	}

	/** The panel is being disposed with its tool window. EDT only. */
	public void detachPreviewPanel(@NotNull PreviewPanel panel) {
		if ( previewPanel==panel ) previewPanel = null;
	}

	@Override
	public void dispose() {
		LOG.info("dispose " + project.getName());
		grammarLoader.shutdownNow(); // lifetime, a child, is already disposed
	}

	// ------------------------------

	private void installListeners() {
		LOG.info("installListeners "+project.getName());
		// Listen for .g4 file saves; disconnected when this service is disposed
		ApplicationManager.getApplication().getMessageBus().connect(this)
			.subscribe(VirtualFileManager.VFS_CHANGES, new GrammarSaveListener());

		EditorFactory.getInstance().addEditorFactoryListener(
			new EditorFactoryListener() {
				@Override
				public void editorCreated(@NotNull EditorFactoryEvent event) {
					final Editor editor = event.getEditor();
					// EditorFactory is application-wide: every open project's controller hears this
					// event, so attach only to this project's editors.
					if ( editor.getProject()!=project ) {
						return;
					}
					final Document doc = editor.getDocument();
					VirtualFile vfile = FileDocumentManager.getInstance().getFile(doc);
					if ( vfile!=null && vfile.getName().endsWith(".g4") ) {
						GrammarEditorMouseListener listener = new GrammarEditorMouseListener();
						editor.putUserData(EDITOR_MOUSE_LISTENER_KEY, listener);
						editor.addEditorMouseListener(listener);
					}
				}

				@Override
				public void editorReleased(@NotNull EditorFactoryEvent event) {
					Editor editor = event.getEditor();
					if (editor.getProject() != null && editor.getProject() != project) {
						return;
					}
					GrammarEditorMouseListener listener = editor.getUserData(EDITOR_MOUSE_LISTENER_KEY);
					if (listener != null) {
						editor.removeEditorMouseListener(listener);
						editor.putUserData(EDITOR_MOUSE_LISTENER_KEY, null);
					}
				}
			},
			this // removed when this service is disposed
		);
	}

	/** The test ANTLR rule action triggers this event. This can occur
	 *  only occur when the current editor is showing a grammar, because
	 *  that is the only time that the action is enabled. We will see
	 *  a file changed event when the project loads the first grammar file.
	 */
	public void setStartRuleNameEvent(VirtualFile grammarFile, String startRuleName) {
		LOG.info("setStartRuleNameEvent " + startRuleName+" "+project.getName());
		PreviewState previewState = getPreviewState(grammarFile);
		previewState.startRuleName = startRuleName;
		if ( previewPanel!=null ) {
			previewPanel.getInputPanel().setStartRuleName(grammarFile, startRuleName); // notify the view
			previewPanel.updateParseTreeFromDoc(grammarFile);
		} // else the panel picks the start rule up from the state when it's created
	}

	/** A grammar was saved (or may have been: auto-refresh calls this after each highlighting
	 *  pass). Reloads every open grammar whose inputs changed, the saved one first; does nothing
	 *  if none did. Superseded saves need no bookkeeping: a queued load finds the cache fresh.
	 */
	public void grammarFileSavedEvent(VirtualFile grammarFile) {
		runGrammarLoad(
			() -> {
				GrammarCache cache = GrammarCache.getInstance(project);
				boolean savedStale = cache.isStale(grammarFile);
				List<PreviewState> dependents = new ArrayList<>();
				for (PreviewState s : grammarToPreviewState.values().toArray(new PreviewState[0])) {
					if ( s.grammarFile.equals(grammarFile) ) continue;
					GrammarCache.Loaded loaded = cache.peek(s.grammarFile);
					if ( loaded!=null && loaded.dependsOn(grammarFile) && cache.isStale(s.grammarFile) ) {
						dependents.add(s);
					}
				}
				if ( !savedStale && dependents.isEmpty() ) {
					LOG.debug("grammarFileSavedEvent "+grammarFile.getPath()+": no grammar changed");
					return false;
				}
				LOG.info("grammarFileSavedEvent "+grammarFile.getPath()+" "+project.getName());
				loadGrammar(grammarFile);
				if ( !dependents.isEmpty() ) {
					// a lexer or imported grammar: regenerate its .tokens file for the grammars using it
					runANTLRTool(grammarFile);
				}
				for (PreviewState s : dependents) {
					loadGrammar(s.grammarFile);
				}
				return true;
			},
			() -> {
				if ( previewPanel!=null ) {
					previewPanel.grammarFileSaved(grammarFile);
				}
			});
	}

	public void currentEditorFileChangedEvent(VirtualFile oldFile, VirtualFile newFile) {
		LOG.info("currentEditorFileChangedEvent "+(oldFile!=null?oldFile.getPath():"none")+
				 " -> "+(newFile!=null?newFile.getPath():"none")+" "+project.getName());
		VirtualFile replayed = replayedStartupGrammar;
		replayedStartupGrammar = null; // only the first selection event after the replay can repeat it
		if ( oldFile==null && newFile!=null && newFile.equals(replayed) ) {
			return; // projectOpened() already showed this grammar
		}
		if ( newFile==null ) { // all files must be closed I guess
			return;
		}

		String newFileExt = newFile.getExtension();

		if (newFileExt == null) {
			return;
		}

		if (newFileExt.equals("g")) {
			LOG.info("currentEditorFileChangedEvent ANTLR 4 cannot handle .g files, only .g4");
			hidePreview();
			return;
		}

		if ( !newFileExt.equals("g4") ) {
			return;
		}

		// Loads newFile unless its cached grammars are fresh; if the grammar switched away from
		// was edited, editorSelectionChanged saved it, which makes grammars using it stale.
		runGrammarLoad(
			() -> {
				loadGrammar(newFile);
				return true;
			},
			() -> {
				if ( previewPanel!=null ) {
					previewPanel.grammarFileChanged(newFile);
				}
			});
	}

	/** Run {@code load} on the grammar loader thread; if it returns true, run
	 *  {@code thenOnEdt} on the EDT afterwards. Both are skipped once this service is disposed.
	 */
	private void runGrammarLoad(BooleanSupplier load, Runnable thenOnEdt) {
		if ( lifetime.isDisposed() ) return; // the loader is shut down
		AppUIExecutor edt = AppUIExecutor.onUiThread(ModalityState.defaultModalityState()).expireWith(lifetime);
		lastGrammarLoad = CompletableFuture
			.supplyAsync(() -> !lifetime.isDisposed() && load.getAsBoolean(), grammarLoader)
			.thenAcceptAsync(loaded -> { if ( loaded ) thenOnEdt.run(); }, edt::execute)
			.whenComplete((ignored, t) -> {
				if ( t!=null && !lifetime.isDisposed() ) { // teardown can break a load midway; ignore that
					LOG.error("Failed to load grammar", t instanceof CompletionException ? t.getCause() : t);
				}
			});
	}

	/** Completes once every grammar load requested so far, and its UI follow-up, has finished. */
	@TestOnly
	public Future<?> grammarLoadsDone() {
		return lastGrammarLoad;
	}

	public void mouseEnteredGrammarEditorEvent(VirtualFile vfile, EditorMouseEvent e) {
		if ( previewPanel!=null ) {
			ProfilerPanel profilerPanel = previewPanel.getProfilerPanel();
			if ( profilerPanel!=null ) {
				profilerPanel.mouseEnteredGrammarEditorEvent(vfile, e);
			}
		}
	}

	public void editorFileClosedEvent(VirtualFile vfile) {
		// hopefully called only from swing EDT
		String grammarFileName = vfile.getPath();
		LOG.info("editorFileClosedEvent "+ grammarFileName+" "+project.getName());
		if ( !vfile.getName().endsWith(".g4") ) {
			hidePreview();
			return;
		}

		// Dispose of state, editor, and such for this file
		PreviewState previewState = grammarToPreviewState.get(vfile);
		if ( previewState==null ) { // project closing must have done already
			return;
		}

		previewState.g = null; // wack old ref to the Grammar for text in editor
		previewState.lg = null;

		if ( previewPanel!=null ) {
			previewPanel.closeGrammar(vfile);
		}

		grammarToPreviewState.remove(vfile);
		GrammarCache.getInstance(project).remove(vfile);

		// close tool window
		hidePreview();
	}

	private void hidePreview() {
		if (previewPanel != null) {
			previewPanel.setEnabled(false);
		}
		ToolWindow previewWindow = getPreviewWindow();
		if (previewWindow != null) {
			previewWindow.hide(null);
		}
	}

	/** Make sure to run after updating grammars in previewState */
	public void runANTLRTool(final VirtualFile grammarFile) {
		String title = "ANTLR Code Generation";
		boolean canBeCancelled = true;
		boolean forceGeneration = false;
		Task gen =
			new RunANTLROnGrammarFile(grammarFile,
									  project,
									  title,
									  canBeCancelled,
									  forceGeneration);
		ProgressManager.getInstance().run(gen);
	}

	/** Puts the grammars loaded from {@code grammarFile} (cached if fresh) into its preview
	 *  state. Grammar loader thread only.
	 */
	private void loadGrammar(VirtualFile grammarFile) {
		GrammarCache.Loaded loaded = GrammarCache.getInstance(project).get(grammarFile);
		PreviewState previewState = getPreviewState(grammarFile);
		synchronized (previewState) { // build atomically
			previewState.lg = loaded.lexer();
			previewState.g = loaded.parser();
		}
	}

	/** Parses {@code inputText} with the grammar's start rule in the background and shows the
	 *  result in the panel. A newer parse cancels this one; disposing the panel cancels it.
	 *  The parse runs in a non-blocking read action: {@code PreviewParser} checks for
	 *  cancellation at every decision, so a write action cancels and restarts it rather than
	 *  waiting for it. Prior art: the platform's threading guide ({@code ReadAction.nonBlocking}
	 *  with {@code coalesceBy}, {@code expireWith}, {@code finishOnUiThread}).
	 */
	public void parseText(final VirtualFile grammarFile, String inputText) {
		final PreviewState previewState = getPreviewState(grammarFile);
		final PreviewPanel panel = previewPanel;
		if ( panel==null ) return; // nothing to show the result in

		// No need to parse empty text during unit tests, yet...
		if (inputText.isEmpty() && ApplicationManager.getApplication().isUnitTestMode()) return;

		final Grammar g;
		final LexerGrammar lg;
		synchronized (previewState) { // a consistent pair; a load may be replacing them
			g = previewState.g;
			lg = previewState.lg;
		}
		final String startRuleName = previewState.startRuleName;
		CancellablePromise<Parse> promise = ReadAction
			.nonBlocking(() -> {
				long start = System.nanoTime();
				ParsingResult result = ParsingUtils.parseText(g, lg, startRuleName, grammarFile, inputText, project);
				return new Parse(result, System.nanoTime() - start);
			})
			.coalesceBy(this, panel) // one parse at a time per panel; the newest input wins
			.expireWith(panel)
			.finishOnUiThread(ModalityState.defaultModalityState(), parse -> {
				previewState.parsingResult = parse.result();
				panel.onParsingCompleted(previewState, parse.nanos());
			})
			.submit(AppExecutorUtil.getAppExecutorService());
		parsing = promise;

		// Offer the cancel button once the parse is slow (the platform's progress postpone time)
		AppUIExecutor edt = AppUIExecutor.onUiThread(ModalityState.defaultModalityState()).expireWith(panel);
		AppExecutorUtil.getAppScheduledExecutorService().schedule(
			() -> edt.execute(() -> { if ( !promise.isDone() ) panel.notifySlowParsing(); }),
			SLOW_PARSE_NOTICE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	private record Parse(ParsingResult result, long nanos) {}

	public void abortCurrentParsing() {
		if ( parsing!=null && !parsing.isDone() ) {
			parsing.cancel();
			parsing = null;
			if ( previewPanel!=null ) previewPanel.onParsingCancelled();
		}
	}

	public void startParsing() {
		if (previewPanel == null) return;
		previewPanel.inputPanel.clearParseErrors(); // Wipes out the console and also any error annotations
		previewPanel.startParsing();
	}

	/** The preview tool window's panel; null until that tool window's content is created. */
	public PreviewPanel getPreviewPanel() {
		return previewPanel;
	}

	/** Output for the Tool Output tool window; buffered until its console exists. */
	public @NotNull ToolOutput getConsole() {
		return console;
	}

	public ToolWindow getConsoleWindow() {
		return ToolWindowManager.getInstance(project).getToolWindow(CONSOLE_WINDOW_ID);
	}

	public static void showConsoleWindow(final Project project) {
		ApplicationManager.getApplication().invokeLater(() -> {
			ToolWindow window = ANTLRv4PluginController.getInstance(project).getConsoleWindow();
			if ( window!=null ) window.show(null);
		}, project.getDisposed());
	}

	public ToolWindow getPreviewWindow() {
		if ( previewWindowForTests!=null ) return previewWindowForTests;
		return ToolWindowManager.getInstance(project).getToolWindow(PREVIEW_WINDOW_ID);
	}

	public @NotNull PreviewState getPreviewState(VirtualFile grammarFile) {
		// atomic because the EDT and the grammar loader both get here
		return grammarToPreviewState.computeIfAbsent(grammarFile, k -> new PreviewState(project, grammarFile));
	}

	public Editor getEditor(VirtualFile vfile) {
		final FileDocumentManager fdm = FileDocumentManager.getInstance();
		final Document doc = fdm.getDocument(vfile);
		if (doc == null) return null;

		EditorFactory factory = EditorFactory.getInstance();
		final Editor[] editors = factory.getEditors(doc, project);
		if ( editors.length==0 ) {
			// no editor found for this file. likely an out-of-sequence issue
			// where Intellij is opening a project and doesn't fire events
			// in order we'd expect.
			return null;
		}
		return editors[0]; // hope just one
	}


	/** Get the state information associated with the grammar in the current
	 *  editor window. If there is no grammar in the editor window, return null.
	 *  If there is a grammar, return any existing preview state else
	 *  create a new one in store in the map.
	 *
	 *  Too dangerous; turning off but might be useful later.
	public @org.jetbrains.annotations.Nullable PreviewState getPreviewState() {
		VirtualFile currentGrammarFile = getCurrentGrammarFile();
		if ( currentGrammarFile==null ) {
			return null;
		}
		String currentGrammarFileName = currentGrammarFile.getPath();
		if ( currentGrammarFileName==null ) {
			return null; // we are not looking at a grammar file
		}
		return getPreviewState(currentGrammarFile);
	}
	 */

	// These "get current editor file" routines should only be used
	// when you are sure the user is in control and is viewing the
	// right file (i.e., don't use these during project loading etc...)

	public static VirtualFile getCurrentEditorFile(Project project) {
		FileEditorManager fmgr = FileEditorManager.getInstance(project);
		// "If more than one file is selected (split), the file with most recent focused editor is returned first." from IDE doc on method
		VirtualFile[] files = fmgr.getSelectedFiles();
		if ( files.length == 0 ) {
			return null;
		}
		return files[0];
	}

	public VirtualFile getCurrentGrammarFile() {
		return getCurrentGrammarFile(project);
	}

	public static VirtualFile getCurrentGrammarFile(Project project) {
		VirtualFile f = getCurrentEditorFile(project);
		if ( f==null ) {
			return null;
		}
		if ( f.getName().endsWith(".g4") ) return f;
		return null;
	}

	private class GrammarEditorMouseListener implements EditorMouseListener {
		@Override
		public void mouseClicked(@NotNull EditorMouseEvent e) {
			Document doc = e.getEditor().getDocument();
			VirtualFile vfile = FileDocumentManager.getInstance().getFile(doc);
			if ( vfile!=null && vfile.getName().endsWith(".g4") ) {
				mouseEnteredGrammarEditorEvent(vfile, e);
			}
		}
	}

	private class GrammarSaveListener implements BulkFileListener {
		@Override
		public void after(@NotNull List<? extends VFileEvent> events) {
			if ( ApplicationManager.getApplication().isUnitTestMode() ) return;
			for (VFileEvent event : events) {
				if ( !(event instanceof VFileContentChangeEvent) ) continue;
				final VirtualFile vfile = ((VFileContentChangeEvent) event).getFile();
				if ( !vfile.getName().endsWith(".g4") ) continue;
				// VFS events are application-wide: ignore saves of grammars that aren't open in
				// this project, or another project's save would repaint our preview.
				if ( !FileEditorManager.getInstance(project).isFileOpen(vfile) ) continue;
				grammarFileSavedEvent(vfile);
			}
		}
	}

	/** Editor tab selection changed in this project; called by {@link GrammarEditorTabListener}. */
	public void editorSelectionChanged(@NotNull FileEditorManagerEvent event) {
		if ( lifetime.isDisposed() ) return;
		// Switching away from an edited grammar saves it, so grammars using it see the edit.
		VirtualFile oldFile = event.getOldFile();
		if ( oldFile!=null && oldFile.isValid() && "g4".equals(oldFile.getExtension()) ) {
			PsiDocumentManager psiMgr = PsiDocumentManager.getInstance(project);
			FileDocumentManager docMgr = FileDocumentManager.getInstance();
			Document doc = docMgr.getDocument(oldFile);
			if ( doc!=null && (!psiMgr.isCommitted(doc) || docMgr.isDocumentUnsaved(doc)) ) {
				psiMgr.commitDocument(doc);
				docMgr.saveDocument(doc);
			}
		}
		currentEditorFileChangedEvent(oldFile, event.getNewFile());
	}

	/** An editor tab closed in this project; called by {@link GrammarEditorTabListener}. */
	public void editorFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
		if ( !lifetime.isDisposed() && source.getSelectedEditor() != null && source.getSelectedEditor().getFile().equals(file) ) {
			editorFileClosedEvent(file);
		}
	}
}
