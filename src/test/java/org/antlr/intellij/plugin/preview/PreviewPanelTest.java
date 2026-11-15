package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.parsing.GrammarCache;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;

import java.io.File;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * Characterization tests for the ANTLR Preview: what it does today, recorded before the
 * preview and grammar model are rewritten (doc/IN_PROGRESS.md, greenfield plan). Asserts on
 * the model (preview state, parse results), not on Swing.
 * <p>
 * Uses grammars-v4 {@code semver}, a parser grammar with {@code tokenVocab} on a separate lexer.
 * The grammars are copied to a real directory: loading resolves the lexer and checks the
 * grammar through {@code java.io.File}, which the light fixture's in-memory files don't satisfy.
 */
public class PreviewPanelTest extends AntlrFixtureTestCase {
	private static final String SEMVER_DIR = "src/test/resources/preview/semver";
	private static final int TIMEOUT_S = 30;

	private ANTLRv4PluginController controller;
	private File dir;
	private VirtualFile lexer;
	private VirtualFile parser;
	private PreviewPanel panel;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		controller = ANTLRv4PluginController.getInstance(getProject());
		dir = FileUtil.createTempDirectory("semver", null, true);
		FileUtil.copyDir(new File(SEMVER_DIR), dir);
		VirtualFile vdir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir);
		assertNotNull(vdir);
		vdir.refresh(false, true);
		lexer = vdir.findChild("SemanticVersionLexer.g4");
		parser = vdir.findChild("SemanticVersionParser.g4");
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			FileUtil.delete(dir);
		}
		finally {
			super.tearDown();
		}
	}

	public void testParserGrammarLoadsWithItsLexerAndFirstRuleAsStart() {
		select(parser);
		attachPanel();
		PreviewState state = controller.getPreviewState(parser);
		assertEquals("SemanticVersionParser", state.g.name);
		assertEquals("SemanticVersionLexer", state.lg.name); // found through tokenVocab
		assertEquals("tag", state.startRuleName); // first rule
		assertSame(state, panel.getInputPanel().previewState);
		assertTrue(panel.isEnabled());
	}

	public void testPanelAttachedLateShowsTheSelectedGrammar() {
		select(parser);
		assertNull(controller.getPreviewPanel()); // no tool window opened yet
		attachPanel();
		assertEquals(parser, panel.getInputPanel().previewState.grammarFile);
		assertNotNull(panel.getInputPanel().getInputEditor());
	}

	public void testInputParsesWithTheStartRule() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		ParsingResult result = typeInput("2.0.0-rc.1+build.123");
		assertEquals("semver", ruleName(result));
		assertTrue(result.syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	public void testBadInputReportsSyntaxErrors() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		ParsingResult result = typeInput("2.0.");
		assertFalse(result.syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	/** A new input cancels the parse still running for the previous one; the newest wins. */
	public void testNewestInputWins() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		typeInput("0.0.1");
		PreviewState state = controller.getPreviewState(parser);
		controller.parseText(parser, "1.0.0");
		controller.parseText(parser, "2.0.0");
		waitFor("reparse", () -> state.parsingResult.tree.getText().startsWith("2.0.0"));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertTrue(state.parsingResult.tree.getText().startsWith("2.0.0"));
	}

	public void testChangingStartRuleReparses() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		ParsingResult first = typeInput("2.0.0");
		assertEquals("semver", ruleName(first));
		ParsingResult second = awaitNewResult(parser, first, () -> controller.setStartRuleNameEvent(parser, "versionCore"));
		assertEquals("versionCore", ruleName(second));
	}

	public void testSwitchingGrammarsKeepsInputAndStartRulePerGrammar() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		typeInput("1.2.3");
		select(lexer);
		assertEquals(lexer, panel.getInputPanel().previewState.grammarFile);
		assertEquals("", inputText());
		select(parser);
		assertEquals("1.2.3", inputText());
		assertEquals("semver", controller.getPreviewState(parser).startRuleName);
	}

	/** Current gap (doc/IN_PROGRESS.md, "Lexer grammar on its own"): no parser, so no preview. */
	public void testLexerGrammarAloneHasNoParser() {
		select(lexer);
		attachPanel();
		PreviewState state = controller.getPreviewState(lexer);
		assertEquals("SemanticVersionLexer", state.lg.name);
		assertNull(state.g);
		assertNull(state.startRuleName);
	}

	public void testSavingAGrammarReloadsIt() {
		select(parser);
		attachPanel();
		editAndSave(parser, text -> text + "\nextra : NUMBER ;\n");
		assertNotNull(controller.getPreviewState(parser).g.getRule("extra"));
	}

	/** A save event with nothing changed (it also arrives after a project opens) keeps the grammars. */
	public void testSaveEventWithoutChangesDoesNotReload() {
		select(parser);
		attachPanel();
		Grammar before = controller.getPreviewState(parser).g;
		controller.grammarFileSavedEvent(parser);
		waitForGrammarLoads();
		assertSame(before, controller.getPreviewState(parser).g);
	}

	/** The preview reloads on save only; auto-refresh sends save events for unsaved edits too. */
	public void testUnsavedEditDoesNotReload() {
		select(parser);
		attachPanel();
		Document doc = FileDocumentManager.getInstance().getDocument(parser);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(doc.getText() + "\nextra : NUMBER ;\n"));
		controller.grammarFileSavedEvent(parser);
		waitForGrammarLoads();
		assertNull(controller.getPreviewState(parser).g.getRule("extra"));
	}

	/** Preview state is keyed by file, not path: moving the grammars keeps it. */
	public void testMovingGrammarsKeepsTheStartRule() throws Exception {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		VirtualFile vdir = parser.getParent();
		WriteCommandAction.writeCommandAction(getProject()).run(() -> vdir.rename(this, vdir.getName() + "-moved"));
		dir = new File(vdir.getPath());
		select(lexer);
		select(parser);
		assertTrue(parser.getPath().contains("-moved"));
		assertEquals("semver", controller.getPreviewState(parser).startRuleName);
		assertEquals("SemanticVersionParser", controller.getPreviewState(parser).g.name);
	}

	/** A lexer looked for but absent is an input too: creating it makes the parser's load stale. */
	public void testCreatingTheMissingLexerReloadsTheParser() throws Exception {
		File lexerFile = new File(dir, "SemanticVersionLexer.g4");
		File moved = new File(dir.getParentFile(), dir.getName() + "-SemanticVersionLexer.g4");
		FileUtil.rename(lexerFile, moved);
		LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir).refresh(false, true);
		select(parser);
		LexerGrammar missing = controller.getPreviewState(parser).lg;
		assertTrue(missing==null || !"SemanticVersionLexer".equals(missing.name));
		FileUtil.rename(moved, lexerFile);
		LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir).refresh(false, true);
		assertTrue(GrammarCache.getInstance(getProject()).isStale(parser));
		select(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(lexerFile));
		select(parser);
		assertEquals("SemanticVersionLexer", controller.getPreviewState(parser).lg.name);
	}

	/** Records today's behavior: the start rule is cleared, not reset to the first rule. */
	public void testDeletingTheStartRuleClearsIt() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "versionCore");
		editAndSave(parser, text -> text.replace(
			"versionCore\n    : major = NUMBER DOT minor = NUMBER DOT patch = NUMBER\n    ;\n", "")
			.replace("versionCore (", "NUMBER ("));
		PreviewState state = controller.getPreviewState(parser);
		assertNull(state.g.getRule("versionCore"));
		assertNull(state.startRuleName);
	}

	public void testSavingTheLexerReloadsTheParserThatUsesIt() {
		select(parser);
		attachPanel();
		select(lexer);
		editAndSave(lexer, text -> text + "\nTILDE : '~' ;\n");
		assertNotNull(controller.getPreviewState(lexer).lg.getRule("TILDE"));
		select(parser);
		assertTrue(controller.getPreviewState(parser).lg.tokenNameToTypeMap.containsKey("TILDE"));
	}

	/** Breaking the lexer (the parser's DOT no longer exists) shows up when parsing with the parser. */
	public void testBreakingTheLexerShowsInTheParserPreview() {
		select(parser);
		attachPanel();
		controller.setStartRuleNameEvent(parser, "semver");
		ParsingResult good = typeInput("1.2.3");
		assertTrue(good.syntaxErrorListener.getSyntaxErrors().isEmpty());
		select(lexer);
		editAndSave(lexer, text -> text.replace("DOT  : '.';", "PERIOD : '.';"));
		PreviewState state = controller.getPreviewState(parser);
		ParsingResult before = state.parsingResult;
		select(parser);
		assertTrue(state.lg.tokenNameToTypeMap.containsKey("PERIOD"));
		waitFor("reparse", () -> state.parsingResult!=before);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertFalse(state.parsingResult.syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	public void testClosingAGrammarReleasesItsInputEditor() {
		select(parser);
		attachPanel();
		Editor input = panel.getInputPanel().getInputEditor();
		assertFalse(input.isDisposed());
		controller.editorFileClosed(FileEditorManager.getInstance(getProject()), parser);
		assertTrue(input.isDisposed());
		assertFalse(controller.grammarToPreviewState.containsKey(parser));
	}

	public void testDisposingThePanelReleasesAllInputEditors() {
		select(parser);
		attachPanel();
		Editor parserInput = controller.getPreviewState(parser).getInputEditor();
		select(lexer);
		Editor lexerInput = controller.getPreviewState(lexer).getInputEditor();
		assertNotSame(parserInput, lexerInput);
		assertFalse(lexerInput.isDisposed());
		assertFalse(parserInput.isDisposed()); // today a grammar switched away from keeps its hidden editor
		Disposer.dispose(panel);
		assertNull(controller.getPreviewPanel());
		assertTrue(lexerInput.isDisposed());
		assertTrue(parserInput.isDisposed());
	}

	// ------------------------------

	/** Opens {@code file} in the editor (the controller hears the tab change) and waits for its load. */
	private void select(VirtualFile file) {
		myFixture.openFileInEditor(file);
		waitForGrammarLoads();
	}

	/** What the tool window factory does; light tests run no factories. */
	private void attachPanel() {
		panel = new PreviewPanel(getProject());
		Disposer.register(getTestRootDisposable(), panel);
		controller.attachPreviewPanel(panel);
		waitForGrammarLoads();
	}

	private String inputText() {
		return panel.getInputPanel().getInputEditor().getDocument().getText();
	}

	/** Replaces the input text and waits for the reparse. */
	private ParsingResult typeInput(String text) {
		VirtualFile grammar = panel.getInputPanel().previewState.grammarFile;
		ParsingResult before = controller.getPreviewState(grammar).parsingResult;
		Document doc = panel.getInputPanel().getInputEditor().getDocument();
		return awaitNewResult(grammar, before, () ->
			WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(text)));
	}

	private ParsingResult awaitNewResult(VirtualFile grammar, ParsingResult before, Runnable action) {
		PreviewState state = controller.getPreviewState(grammar);
		action.run();
		waitFor("reparse", () -> state.parsingResult!=null && state.parsingResult!=before);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		return state.parsingResult;
	}

	/** Edits the grammar's document, saves it, and sends the save event (the VFS listener is off in tests). */
	private void editAndSave(VirtualFile file, UnaryOperator<String> edit) {
		Document doc = FileDocumentManager.getInstance().getDocument(file);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			doc.setText(edit.apply(doc.getText()));
			PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
			FileDocumentManager.getInstance().saveDocument(doc);
		});
		controller.grammarFileSavedEvent(file);
		waitForGrammarLoads();
	}

	private static String ruleName(ParsingResult result) {
		assertNotNull(result);
		ParserRuleContext root = (ParserRuleContext) result.tree;
		return result.parser.getRuleNames()[root.getRuleIndex()];
	}

	private void waitForGrammarLoads() {
		Future<?> done = controller.grammarLoadsDone();
		waitFor("grammar loads", done::isDone);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
	}

	private static void waitFor(String what, BooleanSupplier condition) {
		PlatformTestUtil.waitWithEventsDispatching(what + " did not finish", condition::getAsBoolean, TIMEOUT_S);
	}
}
