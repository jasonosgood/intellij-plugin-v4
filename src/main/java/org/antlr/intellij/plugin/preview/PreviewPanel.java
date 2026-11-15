package org.antlr.intellij.plugin.preview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
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
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.antlr.intellij.plugin.actions.MyActionUtils;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.parsing.PreviewParser;
import org.antlr.intellij.plugin.profiler.ProfilerPanel;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Pair;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
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

	private boolean scrollFromSource = false;
	private boolean highlightSource = false;
	private boolean buildTree = true;
	private boolean buildHierarchy = true;

	private ActionToolbar buttonBar;
	private final CancelParserAction cancelParserAction = new CancelParserAction();

	/** Used to avoid reparsing and also updating the parse tree upon each keystroke. */
	private final MergingUpdateQueue updateQueue;

	public PreviewPanel(Project project) {
		this.project = project;
		createGUI();
		updateQueue =
				new MergingUpdateQueue("(Re-) Parse Queue",
						500,
						true,
						treeViewer
				);
		// If someone is typing, keep resetting timer so parsing doesn't start
		updateQueue.setRestartTimerOnAdd(true);
		// Disposing the queue drops queued reparses, so none run after the panel is gone.
		Disposer.register(this, updateQueue);

		project.getMessageBus().connect(this).subscribe(PreviewListener.TOPIC, new PreviewEvents());
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
			showError("Parsing was aborted");
		}
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
		splitPane.setSecondComponent(createParseTreeAndProfileTabbedPanel());

		this.add(splitPane, BorderLayout.CENTER);
		this.buttonBar = createButtonBar();
		this.add(buttonBar.getComponent(), BorderLayout.WEST);
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
		JBTabbedPane tabbedPane = new JBTabbedPane();

		LOG.debug("createParseTreePanel" + " " + project.getName());
		Pair<UberTreeViewer, JPanel> pair = createParseTreePanel();
		treeViewer = pair.a;
		setupContextMenu(treeViewer);
		tabbedPane.addTab("Parse tree", pair.b);

		hierarchyViewer = new HierarchyViewer(null);
		hierarchyViewer.addParsingResultSelectionListener(this);
		tabbedPane.addTab("Hierarchy", hierarchyViewer);

		profilerPanel = new ProfilerPanel(project, this);
		tabbedPane.addTab("Profiler", profilerPanel.getComponent());

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

		// if the saved grammar is not a pure lexer and there is a start rule, reparse
		// means that switching grammars must refresh preview
		if ( previewState.parserGrammar()!=null && previewState.startRule()!=null ) {
			updateParseTreeFromDoc(previewState.grammarFile());
		}
		else {
			clearTabs(null); // blank tree
		}
	}

	/** Load grammars and set editor component. */
	private void switchToGrammar(VirtualFile grammarFile) {
		String grammarFileName = grammarFile.getPath();
		LOG.debug("switchToGrammar " + grammarFileName+" "+project.getName());
		PreviewState previewState = PreviewService.getInstance(project).getPreviewState(grammarFile);

		inputPanel.switchToGrammar(grammarFile);
		profilerPanel.switchToGrammar();

		if ( previewState.startRule()!=null ) {
			updateParseTreeFromDoc(grammarFile); // regens tree and profile data
		}
		else {
			clearTabs(null); // blank tree
		}

		setEnabled(previewState.parserGrammar()!=null || previewState.lexerGrammar()==null);
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

	private void clearTabs(@Nullable ParseTree tree) {
		ApplicationManager.getApplication().invokeLater(() -> {
			treeViewer.setRuleNames(Collections.emptyList());
			treeViewer.setTree(tree);
			hierarchyViewer.setTree(null);
			hierarchyViewer.setRuleNames(Collections.emptyList());
		});
	}

	private void updateTreeViewer(final PreviewState preview, final ParsingResult result) {
//		long start = System.nanoTime();
//		System.out.println("START updateTreeViewer "+Thread.currentThread().getName());
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
		clearTabs(null);
	}

	private void indicateInvalidGrammarInParseTreePane() {
		showError("Issues with parser and/or lexer grammar(s) prevent preview; see ANTLR 'Tool Output' pane");
	}

	private void showError(String message) {
		clearTabs(new TerminalNodeImpl(new CommonToken(Token.INVALID_TYPE, message)));
	}

	private void indicateNoStartRuleInParseTreePane() {
		showError("No start rule is selected");
	}

	public void updateParseTreeFromDoc(VirtualFile grammarFile) {
		if ( project.isDisposed() ) return;
		PreviewService service = PreviewService.getInstance(project);
		PreviewState previewState = service.getPreviewState(grammarFile);
		LOG.debug("updateParseTreeFromDoc "+grammarFile+" rule "+previewState.startRule());
		if ( previewState.parserGrammar()==null || previewState.lexerGrammar()==null ) {
			// likely error in grammar prevents it from loading properly into previewState; bail
			indicateInvalidGrammarInParseTreePane();
			return;
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

	/** Shows a finished parse; {@code previewState} is the snapshot it was made with. EDT only. */
	private void onParsingCompleted(PreviewState previewState, long duration) {
		cancelParserAction.setEnabled(false);
		buttonBar.updateActionsAsync();

		if (previewState.parsingResult() != null) {
			updateTreeViewer(previewState, previewState.parsingResult());
			profilerPanel.setProfilerData(previewState, duration);
			inputPanel.showParseErrors(previewState.parsingResult().syntaxErrorListener.getSyntaxErrors());
		}
		else if (previewState.startRule() == null) {
			indicateNoStartRuleInParseTreePane();
		}
		else {
			indicateInvalidGrammarInParseTreePane();
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
		Editor editor = inputPanel.getInputEditor();
		if ( startIndex>=0 && stopIndex+1 <= editor.getDocument().getTextLength() ) {
			SelectionModel selectionModel = editor.getSelectionModel();
			selectionModel.removeSelection();
			selectionModel.setSelection(startIndex, stopIndex + 1);
		}
	}
}
