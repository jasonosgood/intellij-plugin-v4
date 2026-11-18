package org.antlr.intellij.plugin.preview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.antlr.intellij.plugin.actions.MyActionUtils;
import org.antlr.intellij.plugin.parsing.LexingResult;
import org.antlr.intellij.plugin.parsing.LoadProblem;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.PreviewParser;
import org.antlr.intellij.plugin.profiler.ProfilerPanel;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

import static com.intellij.icons.AllIcons.Actions.Find;
import static com.intellij.icons.AllIcons.General.AutoscrollFromSource;
import static org.antlr.intellij.plugin.ANTLRv4ToolWindowFactory.PREVIEW_WINDOW_ID;

/** The top level contents of the preview tool window, created by
 *  {@link org.antlr.intellij.plugin.ANTLRv4ToolWindowFactory} and disposed with the tool
 *  window content. It owns the input editor and everything registered on it, and the input
 *  text per grammar ({@link InputPanel}); the grammars, start rules and parse results it shows
 *  are immutable {@link PreviewState} snapshots {@link PreviewService} keeps. It subscribes to
 *  the service's {@link PreviewListener#TOPIC} with itself as parent disposable, so it stops
 *  hearing events when its tool window content goes; the service never calls it.
 */
public class PreviewPanel extends JPanel implements ParsingResultSelectionListener, Disposable {
	//com.apple.eawt stuff stopped working correctly in java 7 and was only recently fixed in java 9;
	//perhaps in a few more years they will get around to backporting whatever it was they fixed.
	// until then,  the zoomable tree viewer will only be installed if the user is running java 1.6
	private static final boolean isTrackpadZoomSupported =
		SystemInfo.isMac &&
		(SystemInfo.JAVA_VERSION.startsWith("1.6") || SystemInfo.JAVA_VERSION.startsWith("1.9"));

	public static final Logger LOG = Logger.getInstance("ANTLR PreviewPanel");

	public Project project;

	public InputPanel inputPanel;

	private UberTreeViewer treeViewer;
	public HierarchyViewer hierarchyViewer;

	public ProfilerPanel profilerPanel;

	private JBTabbedPane tabbedPane;
	/** The result tabs, covered by a spinner while a grammar loads or a slow parse runs. */
	private JBLoadingPanel resultsPanel;
	/** Over the result tabs: dims them and takes their clicks while the preview is paused. */
	private final PausedPane pausedPane = new PausedPane();
	/** The banner saying why the preview is paused or out of date, above everything; empty when
	 *  it is neither.
	 */
	private final JPanel bannerHolder = new JPanel(new BorderLayout());
	/** Why the preview is paused, as shown; null when it isn't. */
	private @Nullable LoadProblem shownPause;
	/** The file whose unsaved changes the preview doesn't show, as shown; null when none (or paused). */
	private @Nullable VirtualFile shownUnsaved;
	/** The out-of-date banner's Save link, while it shows. */
	private @Nullable HyperlinkLabel saveLink;
	/** The parse tree tab: the tree, or an empty state saying why there's none. */
	private final CardLayout treeCards = new CardLayout();
	private final JPanel treeCardPanel = new JPanel(treeCards);
	private final JBPanelWithEmptyText treeEmpty = new JBPanelWithEmptyText();
	private static final String TREE_CARD = "tree", EMPTY_CARD = "empty";
	/** What {@link #resultsPanel} was asked to show as busy ("Parsing…"), or null; its own
	 *  {@code isLoading()} turns true only once the spinner appears.
	 */
	private @Nullable String busyWith;
	private TokensPanel tokensPanel;

	private boolean scrollFromSource = false;
	private boolean highlightSource = false;
	private boolean buildTree = true;
	private boolean buildHierarchy = true;

	private ActionToolbar buttonBar;
	private final CancelParserAction cancelParserAction = new CancelParserAction();

	/** Used to avoid reparsing and also updating the parse tree upon each keystroke. Waits the
	 *  IDE's autoreparse delay (Settings > Editor > General, 300 ms by default), as the editor's
	 *  own analysis does after typing; nothing shows during it. A warm parse takes ~14 ms on
	 *  {@code oracle_normal.g4}, so the wait is most of the delay before results update.
	 */
	private final MergingUpdateQueue updateQueue;

	public PreviewPanel(Project project) {
		this.project = project;
		createGUI();
		updateQueue =
				new MergingUpdateQueue("(Re-) Parse Queue",
						DaemonCodeAnalyzerSettings.getInstance().getAutoReparseDelay(),
						true,
						treeViewer
				);
		// If someone is typing, keep resetting timer so parsing doesn't start
		updateQueue.setRestartTimerOnAdd(true);
		// Disposing the queue drops queued reparses, so none run after the panel is gone.
		Disposer.register(this, updateQueue);

		project.getMessageBus().connect(this).subscribe(PreviewListener.TOPIC, new PreviewEvents());
		showBusy(); // a load may have started before this panel existed
		// Clicks in a grammar editor clear the profiler's decision highlights there
		EditorFactory.getInstance().getEventMulticaster().addEditorMouseListener(new EditorMouseListener() {
			@Override
			public void mouseClicked(@NotNull EditorMouseEvent e) {
				if ( e.getEditor().getProject()!=project ) return;
				VirtualFile vfile = FileDocumentManager.getInstance().getFile(e.getEditor().getDocument());
				if ( vfile!=null && vfile.getName().endsWith(".g4") ) {
					profilerPanel.mouseEnteredGrammarEditorEvent(vfile, e);
				}
			}
		}, this);

		// The current grammar was selected before this panel existed
		VirtualFile selected = MyActionUtils.getSelectedGrammarFile(project);
		if ( selected!=null ) {
			PreviewService.getInstance(project).grammarSelected(selected);
		}
	}

	/** Releases the input editor; the service keeps the per-grammar state. */
	@Override
	public void dispose() {
		inputPanel.releaseEditor();
	}

	/** Tests can substitute the preview tool window; null means look it up. */
	@TestOnly
	public ToolWindow previewWindowForTests;

	private @Nullable ToolWindow getPreviewWindow() {
		if ( previewWindowForTests!=null ) return previewWindowForTests;
		return ToolWindowManager.getInstance(project).getToolWindow(PREVIEW_WINDOW_ID);
	}

	/** Events from {@link PreviewService}, on the EDT. */
	private class PreviewEvents implements PreviewListener {
		@Override
		public void grammarSelected(@NotNull VirtualFile grammarFile) {
			switchToGrammar(grammarFile);
		}

		@Override
		public void grammarReloaded(@NotNull VirtualFile grammarFile) {
			// the reload may include the grammar shown (the saved one, or one using it)
			VirtualFile shown = inputPanel.getGrammarFile();
			if ( shown!=null ) refreshGrammar(shown);
		}

		@Override
		public void startRuleChanged(@NotNull VirtualFile grammarFile) {
			if ( !grammarFile.equals(inputPanel.getGrammarFile()) ) return;
			inputPanel.setStartRuleName(grammarFile, PreviewService.getInstance(project).getPreviewState(grammarFile).startRule());
			updateParseTreeFromDoc(grammarFile);
		}

		@Override
		public void grammarClosed(@NotNull VirtualFile grammarFile) {
			closeGrammar(grammarFile);
		}

		@Override
		public void grammarDeselected() {
			setEnabled(false);
			ToolWindow window = getPreviewWindow();
			if ( window!=null ) window.hide(null);
		}

		@Override
		public void parseSlow() {
			cancelParserAction.setEnabled(true);
			buttonBar.updateActionsAsync();
		}

		@Override
		public void parseCompleted(@NotNull PreviewState state, long nanos) {
			onParsingCompleted(state, nanos);
		}

		@Override
		public void parseCancelled() {
			cancelParserAction.setEnabled(false);
			buttonBar.updateActionsAsync();
			clearTabs("Parse cancelled", null);
		}

		@Override
		public void busyChanged() {
			showBusy();
		}

		@Override
		public void pauseChanged() {
			VirtualFile shown = inputPanel.getGrammarFile();
			if ( shown==null ) return;
			boolean wasPaused = shownPause!=null;
			if ( !showBanner(shown) && wasPaused ) {
				reparseOrClear(PreviewService.getInstance(project).getPreviewState(shown)); // resumed
			}
		}

		@Override
		public void unsavedChanged() {
			VirtualFile shown = inputPanel.getGrammarFile();
			if ( shown!=null ) showBanner(shown);
		}
	}

	/** Shows whether the preview is loading a grammar or parsing, over the result tabs. The
	 *  spinner appears only once the work has run {@link PreviewService#SLOW_PARSE_NOTICE_DELAY_MS},
	 *  so quick loads and parses don't flash it. Prior art: the platform's {@code JBLoadingPanel},
	 *  as in the VCS log and the diff viewer.
	 */
	private void showBusy() {
		PreviewService service = PreviewService.getInstance(project);
		busyWith = service.isLoading() ? "Loading grammar…" : service.isParsing() ? "Parsing…" : null;
		if ( busyWith==null ) {
			resultsPanel.stopLoading();
			return;
		}
		resultsPanel.setLoadingText(busyWith);
		resultsPanel.startLoading(); // does nothing if already started
	}

	private void createGUI() {
		this.setLayout(new BorderLayout());

		// Had to set min size / preferred size in InputPanel.form to get slider to allow left shift of divider
		Splitter splitPane = new Splitter();
		inputPanel = getEditorPanel();
		inputPanel.addCaretListener(new CaretListener() {
			@Override
			public void caretPositionChanged(@NotNull CaretEvent event) {
				Caret caret = event.getCaret();

				if ( scrollFromSource && caret != null ) {
					hierarchyViewer.selectNodeAtOffset(caret.getOffset());
				}
			}
		});
		splitPane.setFirstComponent(inputPanel.getComponent());
		// the input stays usable while the results show that the preview is busy
		resultsPanel = new JBLoadingPanel(new BorderLayout(), this, PreviewService.SLOW_PARSE_NOTICE_DELAY_MS);
		JLayer<JComponent> results = new JLayer<>(createParseTreeAndProfileTabbedPanel());
		results.setGlassPane(pausedPane);
		resultsPanel.add(results, BorderLayout.CENTER);
		splitPane.setSecondComponent(resultsPanel);

		JPanel main = new JPanel(new BorderLayout());
		main.add(bannerHolder, BorderLayout.NORTH);
		main.add(splitPane, BorderLayout.CENTER);
		this.add(main, BorderLayout.CENTER);
		this.buttonBar = createButtonBar();
		this.add(buttonBar.getComponent(), BorderLayout.WEST);
	}

	/** Shows whether the preview is paused for {@code grammarFile} ({@link PreviewService#getPause}):
	 *  an Error banner with the reason, over dimmed results that keep the
	 *  last good parse. Else shows whether it is out of date ({@link PreviewService#getUnsavedInput}):
	 *  an Info banner with a Save link over results left as they are, since they are right for
	 *  the saved grammar. Returns whether it is paused. Does nothing if that is already shown.
	 *  Prior art: Compose Preview and SwiftUI previews; the banner follows the IntelliJ UI
	 *  guidelines (top of the pane, two sentences at most, at most two actions).
	 */
	private boolean showBanner(@NotNull VirtualFile grammarFile) {
		PreviewService service = PreviewService.getInstance(project);
		LoadProblem pause = service.getPause(grammarFile);
		// paused says more: the fix is in the editor, and saving alone won't resume it
		VirtualFile unsaved = pause==null ? service.getUnsavedInput(grammarFile) : null;
		if ( Objects.equals(pause, shownPause) && Objects.equals(unsaved, shownUnsaved) ) return pause!=null;
		shownPause = pause;
		shownUnsaved = unsaved;
		saveLink = null;
		bannerHolder.removeAll();
		if ( pause!=null ) {
			EditorNotificationPanel banner = new EditorNotificationPanel(EditorNotificationPanel.Status.Error);
			banner.text("Preview paused. " + pause.reason());
			// no link to the file: the grammar with errors is open in the editor already
			bannerHolder.add(banner, BorderLayout.CENTER);
			inputPanel.clearParseErrors(); // they belong to the last parse, not the input as it is
		}
		else if ( unsaved!=null ) {
			EditorNotificationPanel banner = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);
			banner.text("Preview out of date. " + unsaved.getName() + " has unsaved changes.");
			// the user's own save, so the plugin still doesn't decide when files are saved
			saveLink = banner.createActionLabel("Save", () -> service.saveInputs(grammarFile));
			bannerHolder.add(banner, BorderLayout.CENTER);
		}
		pausedPane.setVisible(pause!=null);
		bannerHolder.revalidate();
		bannerHolder.repaint();
		return pause!=null;
	}

	@TestOnly
	@Nullable LoadProblem shownPause() {
		return shownPause;
	}

	@TestOnly
	@Nullable VirtualFile shownUnsaved() {
		return shownUnsaved;
	}

	/** Clicks the Save link on the out-of-date banner. */
	@TestOnly
	void clickSaveOnBanner() {
		Objects.requireNonNull(saveLink, "no out-of-date banner").doClick();
	}

	/** Dims what it covers and takes its mouse events, so stale results can't be clicked. A
	 *  {@link JLayer}'s glass pane, shown only while the preview is paused.
	 */
	private static final class PausedPane extends JPanel {
		PausedPane() {
			setOpaque(false);
			setVisible(false);
			addMouseListener(new MouseAdapter() {}); // a component with a listener receives the events
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
				g2.setColor(UIUtil.getPanelBackground());
				g2.fillRect(0, 0, getWidth(), getHeight());
			}
			finally {
				g2.dispose();
			}
		}
	}

	private ActionToolbar createButtonBar() {
		final AnAction refreshAction = new ToggleAction("Refresh Preview Automatically",
				"Refresh preview automatically upon grammar changes", AllIcons.Actions.Refresh) {

			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return PreviewService.getInstance(project).isAutoRefresh();
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				PreviewService.getInstance(project).setAutoRefresh(state);
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.BGT;
			}
		};
		ToggleAction scrollFromSourceBtn = new ToggleAction("Scroll from Source", null, AutoscrollFromSource) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return scrollFromSource;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				scrollFromSource = state;
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.BGT;
			}
		};
		ToggleAction scrollToSourceBtn = new ToggleAction("Highlight Source", null, Find) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return highlightSource;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				highlightSource = state;
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.BGT;
			}
		};
		ToggleAction autoBuildTree = new ToggleAction("Build parse tree after parse",null,AllIcons.Toolwindows.ToolWindowHierarchy) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return buildTree;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) { buildTree = state; }

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.BGT;
			}
		};
		ToggleAction autoBuildHier = new ToggleAction("Build hierarchy after parse",null,AllIcons.Actions.ShowAsTree) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return buildHierarchy;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				buildHierarchy = state;
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.BGT;
			}
		};

		DefaultActionGroup actionGroup = new DefaultActionGroup(
				refreshAction,
				cancelParserAction,
				scrollFromSourceBtn,
				scrollToSourceBtn,
				autoBuildTree,
				autoBuildHier
		);

		ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PREVIEW_WINDOW_ID, actionGroup, false);
		toolbar.setTargetComponent(this); // actions update against the preview panel, not whatever has focus
		return toolbar;
	}

	private InputPanel getEditorPanel() {
		LOG.debug("createEditorPanel"+" "+project.getName());
		return new InputPanel(this);
	}


	private JTabbedPane createParseTreeAndProfileTabbedPanel() {
		tabbedPane = new JBTabbedPane();

		LOG.debug("createParseTreePanel" + " " + project.getName());
		Pair<UberTreeViewer, JPanel> pair = createParseTreePanel();
		treeViewer = pair.a;
		setupContextMenu(treeViewer);
		treeCardPanel.add(pair.b, TREE_CARD);
		treeCardPanel.add(treeEmpty, EMPTY_CARD);
		tabbedPane.addTab("Parse tree", treeCardPanel);

		hierarchyViewer = new HierarchyViewer(null);
		hierarchyViewer.addParsingResultSelectionListener(this);
		tabbedPane.addTab("Hierarchy", hierarchyViewer);

		profilerPanel = new ProfilerPanel(project, this);
		tabbedPane.addTab("Profiler", profilerPanel.getComponent());

		tokensPanel = new TokensPanel(row -> selectInputText(row.startIndex(), row.stopIndex()));
		tabbedPane.addTab("Tokens", tokensPanel);

		return tabbedPane;
	}

	private static void setupContextMenu(final UberTreeViewer treeViewer) {
		treeViewer.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON3) {
					ParseTreeContextualMenu.showPopupMenu(treeViewer, e);
				}
			}
		});
	}

	private static Pair<UberTreeViewer,JPanel> createParseTreePanel() {
		// wrap tree and slider in panel
		JPanel treePanel = new JPanel(new BorderLayout(0, 0));
		treePanel.setBackground(JBColor.white);

		final UberTreeViewer viewer =
			isTrackpadZoomSupported ?
				new TrackpadZoomingTreeView(null, null, false) :
				new UberTreeViewer(null, null, false);

		JSlider scaleSlider = createTreeViewSlider(viewer);

		// Wrap tree viewer component in scroll pane
		JScrollPane scrollPane = new JBScrollPane(viewer); // use Intellij's scroller

		treePanel.add(scrollPane, BorderLayout.CENTER);

		treePanel.add(scaleSlider, BorderLayout.SOUTH);

		return new Pair<>(viewer, treePanel);
	}

	@NotNull
	private static JSlider createTreeViewSlider(final UberTreeViewer viewer) {
		JSlider scaleSlider;
		if ( isTrackpadZoomSupported ) {
			scaleSlider = new JSlider();
			scaleSlider.setModel(((TrackpadZoomingTreeView) viewer).scaleModel);
		}
		else {
			int sliderValue = (int) ((viewer.getScale() - 1.0) * 1000);
			scaleSlider = new JSlider(JSlider.HORIZONTAL, -999, 1000, sliderValue);
			scaleSlider.addChangeListener(e -> {
				int v = ((JSlider) e.getSource()).getValue();
				if ( viewer.hasTree() ) {
					viewer.setScale(v / 1000.0 + 1.0);
				}
			});
		}
		return scaleSlider;
	}

	/** The shown grammar was reloaded: show its start rule and reparse. */
	private void refreshGrammar(VirtualFile grammarFile) {
		LOG.debug("refreshGrammar " + grammarFile.getPath() + " " + project.getName());
		PreviewState previewState = PreviewService.getInstance(project).getPreviewState(grammarFile);

		inputPanel.grammarFileSaved();

		if ( showBanner(grammarFile) ) return; // keep the last results, dimmed
		reparseOrClear(previewState);
	}

	/** Reparses if there is a start rule, or retokenizes a lexer grammar alone; else blank tabs. */
	private void reparseOrClear(PreviewState previewState) {
		if ( (previewState.parserGrammar()!=null && previewState.startRule()!=null) || previewState.isLexerOnly() ) {
			updateParseTreeFromDoc(previewState.grammarFile());
		}
		else {
			clearTabs(previewState.parserGrammar()!=null ? "No start rule selected" : null, null);
		}
	}

	/** Load grammars and set editor component. */
	private void switchToGrammar(VirtualFile grammarFile) {
		String grammarFileName = grammarFile.getPath();
		LOG.debug("switchToGrammar " + grammarFileName+" "+project.getName());
		PreviewState previewState = PreviewService.getInstance(project).getPreviewState(grammarFile);

		inputPanel.switchToGrammar(grammarFile);
		profilerPanel.switchToGrammar();
		setEnabled(true);

		if ( showBanner(grammarFile) ) {
			clearTabs(null, null); // the results shown were another grammar's
			return;
		}
		if ( previewState.isLexerOnly() ) {
			tabbedPane.setSelectedComponent(tokensPanel); // the only tab with anything to show
		}
		reparseOrClear(previewState); // regens tree and profile data
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		this.setEnabledRecursive(this, enabled);
	}

	private void setEnabledRecursive(Component component, boolean enabled) {
		if (component instanceof JTable) {
			// seems there's a special case
			((JTable) component).getTableHeader().setEnabled(enabled);
		}
		if (component instanceof Container) {
			for (Component child : ((Container) component).getComponents()) {
				child.setEnabled(enabled);
				setEnabledRecursive(child, enabled);
			}
		}
	}

	private void closeGrammar(VirtualFile grammarFile) {
		String grammarFileName = grammarFile.getPath();
		LOG.debug("closeGrammar "+grammarFileName+" "+project.getName());

		inputPanel.resetStartRuleLabel();
		inputPanel.clearErrorConsole();
		clearParseTree(); // wipe tree
		inputPanel.closeGrammar(grammarFile);
	}

	/** Shows no tree, with {@code emptyText} saying why (null for nothing), and {@code tokens}
	 *  in the Tokens tab. At once on the EDT, so a tree shown after it stays.
	 */
	private void clearTabs(@Nullable String emptyText, @Nullable LexingResult tokens) {
		UIUtil.invokeLaterIfNeeded(() -> {
			treeViewer.setRuleNames(Collections.emptyList());
			treeViewer.setTree(null);
			treeEmpty.getEmptyText().setText(emptyText!=null ? emptyText : "");
			treeCards.show(treeCardPanel, EMPTY_CARD);
			hierarchyViewer.setTree(null);
			hierarchyViewer.setRuleNames(Collections.emptyList());
			tokensPanel.setTokens(tokens);
		});
	}

	/** Shows the lexer grammar alone's tokens; it has no parse tree, so the tree tab says so. */
	private void showTokensOnly(@NotNull LexingResult tokens) {
		clearTabs("A lexer grammar has no parse tree", tokens);
		treeEmpty.getEmptyText().appendSecondaryText("Show tokens", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
			e -> tabbedPane.setSelectedComponent(tokensPanel));
	}

	private void updateTreeViewer(final PreviewState preview, final ParsingResult result) {
//		long start = System.nanoTime();
//		System.out.println("START updateTreeViewer "+Thread.currentThread().getName());
		treeCards.show(treeCardPanel, TREE_CARD);
		if (result.parser instanceof PreviewParser) {
			AltLabelTextProvider provider = new AltLabelTextProvider(result.parser, preview.parserGrammar());
			if(buildTree) {
				treeViewer.setTreeTextProvider(provider);
				treeViewer.setTree(result.tree);
			}
			if(buildHierarchy) {
				hierarchyViewer.setTreeTextProvider(provider);
				hierarchyViewer.setTree(result.tree);
			}
		}
		else {
			if(buildTree) {
				treeViewer.setRuleNames(Arrays.asList(preview.parserGrammar().getRuleNames()));
				treeViewer.setTree(result.tree);
			}
			if(buildHierarchy) {
				hierarchyViewer.setRuleNames(Arrays.asList(preview.parserGrammar().getRuleNames()));
				hierarchyViewer.setTree(result.tree);
			}
		}
//		long parseTime_ns = System.nanoTime() - start;
//		double parseTimeMS = parseTime_ns/(1000.0*1000.0);
//		System.out.println("STOP updateTreeViewer "+Thread.currentThread().getName()+" "+parseTimeMS+"ms");
	}


	void clearParseTree() {
		clearTabs(null, null);
	}

	public void updateParseTreeFromDoc(VirtualFile grammarFile) {
		if ( project.isDisposed() ) return;
		PreviewService service = PreviewService.getInstance(project);
		PreviewState previewState = service.getPreviewState(grammarFile);
		LOG.debug("updateParseTreeFromDoc "+grammarFile+" rule "+previewState.startRule());
		if ( showBanner(grammarFile) ) return; // no parsing while paused
		if ( (previewState.parserGrammar()==null || previewState.lexerGrammar()==null) && !previewState.isLexerOnly() ) {
			return; // not loaded yet
		}

		Editor editor = inputPanel.getInputEditor();
		if ( editor==null ) return;
		final String inputText = editor.getDocument().getText();

		// The service publishes the result when it's done parsing
		updateQueue.queue(new Update(this) {
			@Override
			public boolean canEat(Update update) {
				return true; // kill any previous queued up parses; only last keystroke input text matters
			}
			@Override
			public void run() {
				inputPanel.clearParseErrors(); // wipes out the console and any error annotations
				cancelParserAction.setEnabled(false);
				buttonBar.updateActionsAsync();
				service.parse(grammarFile, inputText);
			}
		});
	}

	public InputPanel getInputPanel() {
		return inputPanel;
	}

	@TestOnly
	TokensPanel getTokensPanel() {
		return tokensPanel;
	}

	@TestOnly
	boolean isTokensTabSelected() {
		return tabbedPane.getSelectedComponent()==tokensPanel;
	}

	@TestOnly
	@Nullable String busyWith() {
		return busyWith;
	}

	/** Shows a finished parse; {@code previewState} is the snapshot it was made with. EDT only. */
	private void onParsingCompleted(PreviewState previewState, long duration) {
		cancelParserAction.setEnabled(false);
		buttonBar.updateActionsAsync();

		if (previewState.parsingResult() != null) {
			updateTreeViewer(previewState, previewState.parsingResult());
			profilerPanel.setProfilerData(previewState, duration);
			inputPanel.showParseErrors(previewState.parsingResult().syntaxErrorListener.getSyntaxErrors());
			tokensPanel.setTokens(previewState.lexingResult());
		}
		else if ( previewState.isLexerOnly() && previewState.lexingResult()!=null ) {
			showTokensOnly(previewState.lexingResult());
			inputPanel.showParseErrors(previewState.lexingResult().syntaxErrorListener().getSyntaxErrors());
		}
		else if (previewState.startRule() == null) {
			clearTabs("No start rule selected", null);
		}
		else {
			clearTabs(null, null); // a parse of grammars that failed to load; the pause says why
		}
	}


	@Override
	public void onParserRuleSelected(Tree tree) {
		int startIndex;
		int stopIndex;

		if ( tree instanceof ParserRuleContext ) {
			Token start = ((ParserRuleContext) tree).getStart();
			Token stop = ((ParserRuleContext) tree).getStop();
			if ( start==null || stop==null ) { // stop can be null if start is EOF; nothing to show so return
				return;
			}
			startIndex = start.getStartIndex();
			stopIndex = stop.getStopIndex();
		}
		else if ( tree instanceof TerminalNode ) {
			startIndex = ((TerminalNode) tree).getSymbol().getStartIndex();
			stopIndex = ((TerminalNode) tree).getSymbol().getStopIndex();
		}
		else {
			return;
		}

		// PreviewService.parse() lazily updates the parse tree so it's possible
		// that we have edited the input and something triggers a click on the Hierarchy pane
		// before the tree is done and therefore the tree parameter to this method.
		// Avoid trying to select text outside of doc[0..stopindex] as a general rule too.
		// It also looks like previous code was triggering an update to hierarchy view when we
		// click in input pane which then tried to select entire token like a string. Now,
		// text is selected in input pane only when a mouse event occurs in hierarchy pane.
		selectInputText(startIndex, stopIndex);
	}

	/** Selects the input text from {@code startIndex} to {@code stopIndex} inclusive, if the
	 *  input still has it, and scrolls to it. The caret stays, so scroll-from-source isn't set off.
	 */
	private void selectInputText(int startIndex, int stopIndex) {
		Editor editor = inputPanel.getInputEditor();
		if ( editor!=null && startIndex>=0 && stopIndex+1 <= editor.getDocument().getTextLength() ) {
			SelectionModel selectionModel = editor.getSelectionModel();
			selectionModel.removeSelection();
			selectionModel.setSelection(startIndex, stopIndex + 1);
			editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(startIndex), ScrollType.MAKE_VISIBLE);
		}
	}
}
