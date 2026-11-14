package org.antlr.intellij.plugin;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
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
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.util.BackgroundTaskUtil;
import com.intellij.openapi.project.Project;
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
import org.antlr.intellij.plugin.parsing.ParsingUtils;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.antlr.intellij.plugin.preview.PreviewState;
import org.antlr.intellij.plugin.profiler.ProfilerPanel;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
 *  the project closes. Everything it owns (listeners, the console, the reparse queue,
 *  preview editors) is registered against it as parent {@link Disposable}, so nothing
 *  outlives the project and there is no manual uninstall. Editor tab events arrive through
 *  {@link GrammarEditorTabListener}, declared in plugin.xml; the tool windows are declared
 *  there too and get their content from {@link ANTLRv4ToolWindowFactory}. UI parts are
 *  created on the EDT by {@link #projectOpened()}, run from {@link ANTLRv4StartupActivity}.
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

	/** Set when the project closes; background work and late EDT callbacks check it. */
	public volatile boolean projectIsClosed = false;

	public final Project project;
	private ConsoleView console;
	private boolean opened; // projectOpened() has run; EDT only
	/** Grammar projectOpened() replayed a selection for; the platform's own event for it is skipped. EDT only. */
	private VirtualFile replayedStartupGrammar;

	public final Map<String, PreviewState> grammarToPreviewState =
		Collections.synchronizedMap(new HashMap<>());
	private PreviewPanel previewPanel;	// same for all grammar editor

	/** Tests can substitute the preview tool window; null means look it up. */
	@TestOnly
	public ToolWindow previewWindowForTests;

	private ProgressIndicator parsingProgressIndicator;

	private final Map<String, Long> grammarFileMods = new HashMap<>();
	/** Text of each grammar when it was last loaded, by path; see {@link #grammarFileSavedEvent}. */
	private final Map<String, String> loadedGrammarText = new ConcurrentHashMap<>();

	/** Loading a grammar runs ANTLR's full analysis pipeline, which can take seconds
	 *  on large grammars, so it must never run on the EDT. All loads run in order on
	 *  this single background thread; UI follow-ups are posted back to the EDT.
	 */
	private final ExecutorService grammarLoader =
		AppExecutorUtil.createBoundedApplicationPoolExecutor("ANTLR grammar loader", 1);

	/** Latest save-triggered load requested per grammar path; superseded queued loads are skipped. */
	private final Map<String, Integer> grammarLoadGeneration = new ConcurrentHashMap<>();

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

	/** Creates the UI parts and installs listeners. Runs once, on the EDT, when the
	 *  project opens (or earlier, if a tool window or test asks for the preview panel first).
	 */
	public void projectOpened() {
		ApplicationManager.getApplication().assertIsDispatchThread();
		if ( opened || projectIsClosed ) return;
		opened = true;

		LOG.info("ANTLR 4 Plugin opened for "+project.getName()+", Java version "+ SystemInfo.JAVA_VERSION);

		LOG.info("createToolWindows "+project.getName());
		previewPanel = new PreviewPanel(project);
		console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
		Disposer.register(this, console); // not via tool window content, which may never be created

		installListeners();

		// A grammar restored in an editor at startup may have been selected before the panel
		// existed. Replay its selection; if the platform's own selection event for it is still
		// on its way, currentEditorFileChangedEvent skips that one.
		VirtualFile current = getCurrentGrammarFile();
		if ( current!=null ) {
			currentEditorFileChangedEvent(null, current, false);
			replayedStartupGrammar = current;
		}
	}

	@Override
	public void dispose() {
		LOG.info("dispose " + project.getName());
		projectIsClosed = true;
		if ( previewPanel!=null ) {
			for (PreviewState it : grammarToPreviewState.values()) {
				previewPanel.inputPanel.releaseEditor(it);
			}
		}
		grammarLoader.shutdownNow();
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
		}
		else {
			LOG.error("setStartRuleNameEvent called before preview panel created");
		}
	}

	public void grammarFileSavedEvent(VirtualFile grammarFile) {

		Long modCount = grammarFile.getModificationCount();
		String grammarFilePath = grammarFile.getPath();

		if (grammarFileMods.containsKey(grammarFilePath) && grammarFileMods.get(grammarFilePath).equals(modCount)) {
			return;
		}

		grammarFileMods.put(grammarFilePath, modCount);

		// A content-change event also arrives a few seconds after a project opens, with nothing
		// edited. Reload only if the text differs from what was last loaded: comparing content,
		// not trusting change events, is what file watchers such as Gradle's and webpack's do.
		String loadedText = loadedGrammarText.get(grammarFilePath);
		if ( loadedText!=null ) {
			try {
				if ( loadedText.equals(ParsingUtils.grammarText(grammarFile)) ) {
					LOG.info("grammarFileSavedEvent "+grammarFilePath+" unchanged since loaded; not reloading");
					return;
				}
			}
			catch (IOException e) {
				// can't read it; reload, which reports the error
			}
		}

		LOG.info("grammarFileSavedEvent "+grammarFilePath+" "+project.getName());
		final Integer generation = grammarLoadGeneration.merge(grammarFilePath, 1, Integer::sum);
		runGrammarLoad(
			() -> {
				if ( !generation.equals(grammarLoadGeneration.get(grammarFilePath)) ) {
					return false; // a newer save of this file is queued and will reload it
				}
				updateGrammarObjectsFromFile(grammarFile, true); // force reload
				return true;
			},
			() -> {
				if ( previewPanel!=null ) {
					previewPanel.grammarFileSaved(grammarFile);
				}
				else {
					LOG.error("grammarFileSavedEvent called before preview panel created");
				}
			});
	}

	public void currentEditorFileChangedEvent(VirtualFile oldFile, VirtualFile newFile, boolean modified) {
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

		// When switching from a lexer grammar, update its objects in case the grammar was modified.
		// The updated objects might be needed later by another dependant grammar.
		final boolean reloadOldFile = oldFile != null && "g4".equals(oldFile.getExtension()) && modified;
		runGrammarLoad(
			() -> {
				if ( reloadOldFile ) {
					updateGrammarObjectsFromFile(oldFile, true);
				}
				PreviewState previewState = getPreviewState(newFile);
				if ( previewState.g==null && previewState.lg==null ) { // only load grammars if none is there
					updateGrammarObjectsFromFile(newFile, false);
				}
				return true;
			},
			() -> {
				if ( previewPanel!=null ) {
					previewPanel.grammarFileChanged(newFile);
				}
			});
	}

	/** Run {@code load} on the grammar loader thread; if it returns true, run
	 *  {@code thenOnEdt} on the EDT afterwards. Both are skipped once the project
	 *  closes or is disposed.
	 */
	private void runGrammarLoad(BooleanSupplier load, Runnable thenOnEdt) {
		if ( projectIsClosed ) return; // the loader is shut down on dispose
		final Project loadProject = project;
		grammarLoader.execute(() -> {
			if ( projectIsClosed || loadProject.isDisposed() ) return;
			boolean loaded;
			try {
				loaded = load.getAsBoolean();
			}
			catch (Throwable t) {
				if ( !projectIsClosed && !loadProject.isDisposed() ) { // teardown nulls our state mid-load; ignore that
					LOG.error("Failed to load grammar", t);
				}
				return;
			}
			if ( !loaded ) return;
			ApplicationManager.getApplication().invokeLater(() -> {
				if ( !projectIsClosed ) {
					thenOnEdt.run();
				}
			}, ModalityState.defaultModalityState(), loadProject.getDisposed());
		});
	}

	/** Completes once every grammar load queued so far has finished. EDT follow-ups
	 *  may still be pending; tests should dispatch EDT events afterwards.
	 */
	@TestOnly
	public Future<?> grammarLoadsDone() {
		return grammarLoader.submit(() -> {});
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
		PreviewState previewState = grammarToPreviewState.get(grammarFileName);
		if ( previewState==null ) { // project closing must have done already
			return;
		}

		previewState.g = null; // wack old ref to the Grammar for text in editor
		previewState.lg = null;

		previewPanel.closeGrammar(vfile);

		grammarToPreviewState.remove(grammarFileName);

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

	/** Look for state information concerning this grammar file and update
	 *  the Grammar objects.  This does not necessarily update the grammar file
	 *  in the current editor window.  Either we are already looking at
	 *  this grammar or we will have seen a grammar file changed event.
	 *  (I hope!)
	 */
	private void updateGrammarObjectsFromFile(VirtualFile grammarFile, boolean generateTokensFile) {
		updateGrammarObjectsFromFile_(grammarFile);

		// if grammarFileName is a separate lexer, we need to look for
		// its matching parser, if any, that is loaded in an editor
		// (don't go looking on disk).
		PreviewState s = getAssociatedParserIfLexer(grammarFile.getPath());
		if ( s!=null ) {
			if (generateTokensFile) {
				// Run the tool to regenerate the .tokens file, which will be
				// needed in the parser grammar
				runANTLRTool(grammarFile);
			}

			// try to load lexer again and associate with this parser grammar.
			// must update parser too as tokens have changed
			updateGrammarObjectsFromFile_(s.grammarFile);
		}
	}

	private String updateGrammarObjectsFromFile_(VirtualFile grammarFile) {
		String grammarFileName = grammarFile.getPath();
		PreviewState previewState = getPreviewState(grammarFile);
		try { // read before loading, so an edit made during the load still counts as a change
			loadedGrammarText.put(grammarFileName, ParsingUtils.grammarText(grammarFile));
		}
		catch (IOException e) {
			loadedGrammarText.remove(grammarFileName);
		}
		Grammar[] grammars = ParsingUtils.loadGrammars(grammarFile, project);
		if (grammars != null) {
			synchronized (previewState) { // build atomically
				previewState.lg = (LexerGrammar)grammars[0];
				previewState.g = grammars[1];
			}
		}
		else {
			synchronized (previewState) { // build atomically
				previewState.lg = null;
				previewState.g = null;
			}
		}
		return grammarFileName;
	}

	// TODO there could be multiple grammars importing/tokenVocab'ing this lexer grammar
	public PreviewState getAssociatedParserIfLexer(String grammarFileName) {
		for (PreviewState s : grammarToPreviewState.values()) {
			if ( s!=null && s.lg!=null &&
					(sameFile(grammarFileName, s.lg.fileName)||s.lg==ParsingUtils.BAD_LEXER_GRAMMAR) )
			{
				// s has a lexer with same filename, see if there is a parser grammar
				// (not a combined grammar)
				if ( s.g!=null && s.g.getType()==ANTLRParser.PARSER ) {
					return s;
				}
			}

			if ( s!=null && s.g!=null && s.g.importedGrammars!=null ) {
				for ( Grammar importedGrammar : s.g.importedGrammars ) {
					if (grammarFileName.equals(importedGrammar.fileName)) {
						return s;
					}
				}
			}
		}
		return null;
	}

	private boolean sameFile(String pathOne, String pathTwo) {
		// use new File() to support both / and \ in paths
		return new File(pathOne).equals(new File(pathTwo));
	}

	public void parseText(final VirtualFile grammarFile, String inputText) {
		final PreviewState previewState = getPreviewState(grammarFile);
		// projectClosed() nulls these fields; capture them, and check projectIsClosed before
		// touching the panel, since the parse finishes later on the EDT.
		final Project parseProject = project;
		final PreviewPanel panel = previewPanel;

		// No need to parse empty text during unit tests, yet...
		if (inputText.isEmpty() && ApplicationManager.getApplication().isUnitTestMode()) return;

		// Parse text in a background thread to avoid freezing the UI if the grammar is badly written
		// and takes forever to interpret the input.
		parsingProgressIndicator = BackgroundTaskUtil.executeAndTryWait(
				(indicator) -> {
//					System.out.println("PARSE START "+Thread.currentThread().getName());
					long start = System.nanoTime();

					previewState.parsingResult = ParsingUtils.parseText(
							previewState.g, previewState.lg, previewState.startRuleName,
							grammarFile, inputText, parseProject
					);

//					long parseTime_ns = System.nanoTime() - start;
//					double parseTimeMS = parseTime_ns/(1000.0*1000.0);
//					System.out.println("PARSE STOP "+Thread.currentThread().getName()+" "+parseTimeMS+"ms");
					long parseTime_ns = System.nanoTime() - start;
					return () -> {
						if ( !projectIsClosed ) panel.onParsingCompleted(previewState, parseTime_ns);
					};
				},
				() -> {
					if ( !projectIsClosed ) panel.notifySlowParsing();
				},
				SLOW_PARSE_NOTICE_DELAY_MS,
				false
		);
	}

	public void abortCurrentParsing() {
		if ( parsingProgressIndicator!=null ) {
			parsingProgressIndicator.cancel();
			parsingProgressIndicator = null;
			previewPanel.onParsingCancelled();
		}
	}

	public void startParsing() {
		parsingProgressIndicator = null;
		if (previewPanel == null) return;
		previewPanel.inputPanel.clearParseErrors(); // Wipes out the console and also any error annotations
		previewPanel.startParsing();
	}

	/** The preview panel; on the EDT, creates the UI parts first if the startup activity
	 *  hasn't run yet (tool window shown early, or tests). Null off the EDT before that. */
	public PreviewPanel getPreviewPanel() {
		if ( !opened && ApplicationManager.getApplication().isDispatchThread() ) {
			projectOpened();
		}
		return previewPanel;
	}

	/** The Tool Output console; null until {@link #projectOpened()} has run. */
	public ConsoleView getConsole() {
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
		// make sure only one thread tries to add a preview state object for a given file
		String grammarFileName = grammarFile.getPath();
		// Have we seen this grammar before?
		if (grammarToPreviewState != null) {
			PreviewState stateForCurrentGrammar = grammarToPreviewState.get(grammarFileName);
			if ( stateForCurrentGrammar!=null ) {
				return stateForCurrentGrammar; // seen this before
			}
		}
		// not seen, must create state; atomic because the EDT and the grammar loader both get here
		PreviewState stateForCurrentGrammar =
			grammarToPreviewState.computeIfAbsent(grammarFileName, k -> new PreviewState(project, grammarFile));

		return stateForCurrentGrammar;
	}

	public Editor getEditor(VirtualFile vfile) {
		final FileDocumentManager fdm = FileDocumentManager.getInstance();
		final Document doc = fdm.getDocument(vfile);
		if (doc == null) return null;

		EditorFactory factory = EditorFactory.getInstance();
		final Editor[] editors = factory.getEditors(doc, previewPanel.project);
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
			if ( projectIsClosed || ApplicationManager.getApplication().isUnitTestMode() ) return;
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
		if ( projectIsClosed ) return;
		boolean modified = false;

		if (event.getOldEditor() != null) {
			if (event.getOldEditor().isModified()) {
				modified = true;
			} else {
				VirtualFile oldFile = event.getOldEditor().getFile();
				String oldFilePath = oldFile.getPath();
				Long modCount = oldFile.getModificationCount();
				modified = grammarFileMods.containsKey(oldFilePath) &&
						!grammarFileMods.get(oldFilePath).equals(modCount);
			}
		}

		if (modified) {
			PsiDocumentManager psiMgr = PsiDocumentManager.getInstance(project);
			FileDocumentManager docMgr = FileDocumentManager.getInstance();
			Document doc = docMgr.getDocument(event.getOldFile());
			if ( !psiMgr.isCommitted(doc) || docMgr.isDocumentUnsaved(doc) ) {
				psiMgr.commitDocument(doc);
				docMgr.saveDocument(doc);
			}
		}
		currentEditorFileChangedEvent(event.getOldFile(), event.getNewFile(), modified);
	}

	/** An editor tab closed in this project; called by {@link GrammarEditorTabListener}. */
	public void editorFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
		if ( !projectIsClosed && source.getSelectedEditor() != null && source.getSelectedEditor().getFile().equals(file) ) {
			editorFileClosedEvent(file);
		}
	}
}
