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
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PlatformTestUtil;
import org.antlr.intellij.plugin.GrammarEditorTabListener;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.parsing.GrammarCache;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.psi.ParserRuleSpecNode;
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

	private PreviewService preview;
	private File dir;
	private VirtualFile lexer;
	private VirtualFile parser;
	private PreviewPanel panel;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		preview = PreviewService.getInstance(getProject());
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
		PreviewState state = preview.getPreviewState(parser);
		assertEquals("SemanticVersionParser", state.parserGrammar().name);
		assertEquals("SemanticVersionLexer", state.lexerGrammar().name); // found through tokenVocab
		assertEquals("tag", state.startRule()); // first rule
		assertEquals(parser, panel.getInputPanel().getGrammarFile());
		assertTrue(panel.isEnabled());
	}

	public void testPanelAttachedLateShowsTheSelectedGrammar() {
		select(parser);
		attachPanel();
		assertEquals(parser, panel.getInputPanel().getGrammarFile());
		assertNotNull(panel.getInputPanel().getInputEditor());
	}

	public void testInputParsesWithTheStartRule() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		ParsingResult result = typeInput("2.0.0-rc.1+build.123");
		assertEquals("semver", ruleName(result));
		assertTrue(result.syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	public void testBadInputReportsSyntaxErrors() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		ParsingResult result = typeInput("2.0.");
		assertFalse(result.syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	/** A new input cancels the parse still running for the previous one; the newest wins. */
	public void testNewestInputWins() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		typeInput("0.0.1");
		preview.parse(parser, "1.0.0");
		preview.parse(parser, "2.0.0");
		waitFor("reparse", () -> parsingResult(parser).tree.getText().startsWith("2.0.0"));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertTrue(parsingResult(parser).tree.getText().startsWith("2.0.0"));
	}

	public void testChangingStartRuleReparses() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		ParsingResult first = typeInput("2.0.0");
		assertEquals("semver", ruleName(first));
		ParsingResult second = awaitNewResult(parser, first, () -> preview.setStartRule(parser, "versionCore"));
		assertEquals("versionCore", ruleName(second));
	}

	public void testSwitchingGrammarsKeepsInputAndStartRulePerGrammar() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		typeInput("1.2.3");
		select(lexer);
		assertEquals(lexer, panel.getInputPanel().getGrammarFile());
		assertEquals("", inputText());
		select(parser);
		assertEquals("1.2.3", inputText());
		assertEquals("semver", preview.getPreviewState(parser).startRule());
	}

	/** Current gap (doc/IN_PROGRESS.md, "Lexer grammar on its own"): no parser, so no preview. */
	public void testLexerGrammarAloneHasNoParser() {
		select(lexer);
		attachPanel();
		PreviewState state = preview.getPreviewState(lexer);
		assertEquals("SemanticVersionLexer", state.lexerGrammar().name);
		assertNull(state.parserGrammar());
		assertNull(state.startRule());
	}

	public void testSavingAGrammarReloadsIt() {
		select(parser);
		attachPanel();
		editAndSave(parser, text -> text + "\nextra : NUMBER ;\n");
		assertNotNull(preview.getPreviewState(parser).parserGrammar().getRule("extra"));
	}

	/** A save event with nothing changed (it also arrives after a project opens) keeps the grammars. */
	public void testSaveEventWithoutChangesDoesNotReload() {
		select(parser);
		attachPanel();
		Grammar before = preview.getPreviewState(parser).parserGrammar();
		preview.grammarSaved(parser);
		waitForGrammarLoads();
		assertSame(before, preview.getPreviewState(parser).parserGrammar());
	}

	/** The preview reloads on save only; auto-refresh sends save events for unsaved edits too. */
	public void testUnsavedEditDoesNotReload() {
		select(parser);
		attachPanel();
		Document doc = FileDocumentManager.getInstance().getDocument(parser);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(doc.getText() + "\nextra : NUMBER ;\n"));
		preview.grammarSaved(parser);
		waitForGrammarLoads();
		assertNull(preview.getPreviewState(parser).parserGrammar().getRule("extra"));
	}

	/** Switching tabs doesn't save: the platform decides when (⌘S, Autosave settings). */
	public void testSwitchingTabsDoesNotSave() {
		select(parser);
		select(lexer);
		Document doc = FileDocumentManager.getInstance().getDocument(lexer);
		assertNotNull(doc);
		WriteCommandAction.runWriteCommandAction(getProject(), () ->
			doc.setText(doc.getText().replace("DOT  : '.';", "PERIOD : '.';")));
		select(parser);
		assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
	}

	/** Preview state is keyed by file, not path: moving the grammars keeps it. */
	public void testMovingGrammarsKeepsTheStartRule() throws Exception {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		VirtualFile vdir = parser.getParent();
		WriteCommandAction.writeCommandAction(getProject()).run(() -> vdir.rename(this, vdir.getName() + "-moved"));
		dir = new File(vdir.getPath());
		select(lexer);
		select(parser);
		assertTrue(parser.getPath().contains("-moved"));
		assertEquals("semver", preview.getPreviewState(parser).startRule());
		assertEquals("SemanticVersionParser", preview.getPreviewState(parser).parserGrammar().name);
	}

	/** A lexer looked for but absent is an input too: creating it makes the parser's load stale. */
	public void testCreatingTheMissingLexerReloadsTheParser() throws Exception {
		File lexerFile = new File(dir, "SemanticVersionLexer.g4");
		File moved = new File(dir.getParentFile(), dir.getName() + "-SemanticVersionLexer.g4");
		FileUtil.rename(lexerFile, moved);
		LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir).refresh(false, true);
		select(parser);
		LexerGrammar missing = preview.getPreviewState(parser).lexerGrammar();
		assertTrue(missing==null || !"SemanticVersionLexer".equals(missing.name));
		FileUtil.rename(moved, lexerFile);
		LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir).refresh(false, true);
		assertTrue(GrammarCache.getInstance(getProject()).isStale(parser));
		select(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(lexerFile));
		select(parser);
		assertEquals("SemanticVersionLexer", preview.getPreviewState(parser).lexerGrammar().name);
	}

	/** A start rule deleted from the grammar falls back to the first rule. */
	public void testDeletingTheStartRuleFallsBackToTheFirstRule() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "versionCore");
		editAndSave(parser, text -> text.replace(
			"versionCore\n    : major = NUMBER DOT minor = NUMBER DOT patch = NUMBER\n    ;\n", "")
			.replace("versionCore (", "NUMBER ("));
		PreviewState state = preview.getPreviewState(parser);
		assertNull(state.parserGrammar().getRule("versionCore"));
		assertEquals("tag", state.startRule());
	}

	/** Renaming the start rule renames the preview's start rule, once the grammar is saved.
	 *  Renames {@code semver}, which nothing refers to: the test grammars are outside the light
	 *  project, so the rename can't find references to update.
	 */
	public void testRenamingTheStartRuleKeepsItAsTheStartRule() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		myFixture.renameElement(parserRule("semver"), "release");
		assertEquals("semver", preview.getPreviewState(parser).startRule()); // reloads on save only
		save(parser);
		assertEquals("release", preview.getPreviewState(parser).startRule());
	}

	/** Renaming another rule leaves the start rule alone. */
	public void testRenamingAnotherRuleKeepsTheStartRule() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "versionCore");
		myFixture.renameElement(parserRule("semver"), "release");
		save(parser);
		PreviewState state = preview.getPreviewState(parser);
		assertNotNull(state.parserGrammar().getRule("release"));
		assertEquals("versionCore", state.startRule());
	}

	public void testSavingTheLexerReloadsTheParserThatUsesIt() {
		select(parser);
		attachPanel();
		select(lexer);
		editAndSave(lexer, text -> text + "\nTILDE : '~' ;\n");
		assertNotNull(preview.getPreviewState(lexer).lexerGrammar().getRule("TILDE"));
		select(parser);
		assertTrue(preview.getPreviewState(parser).lexerGrammar().tokenNameToTypeMap.containsKey("TILDE"));
	}

	/** Breaking the lexer (the parser's DOT no longer exists) shows up when parsing with the parser. */
	public void testBreakingTheLexerShowsInTheParserPreview() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		ParsingResult good = typeInput("1.2.3");
		assertTrue(good.syntaxErrorListener.getSyntaxErrors().isEmpty());
		select(lexer);
		editAndSave(lexer, text -> text.replace("DOT  : '.';", "PERIOD : '.';"));
		select(parser);
		assertTrue(preview.getPreviewState(parser).lexerGrammar().tokenNameToTypeMap.containsKey("PERIOD"));
		waitFor("reparse", () -> parsingResult(parser)!=null && parsingResult(parser)!=good);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertFalse(parsingResult(parser).syntaxErrorListener.getSyntaxErrors().isEmpty());
	}

	public void testClosingAGrammarReleasesItsInputEditor() {
		select(parser);
		attachPanel();
		Editor input = panel.getInputPanel().getInputEditor();
		assertFalse(input.isDisposed());
		new GrammarEditorTabListener(getProject()).fileClosed(FileEditorManager.getInstance(getProject()), parser);
		assertTrue(input.isDisposed());
		assertFalse(preview.hasPreviewState(parser));
	}

	/** One input editor at a time: switching grammars releases the previous one. */
	public void testSwitchingGrammarsReleasesThePreviousInputEditor() {
		select(parser);
		attachPanel();
		Editor parserInput = panel.getInputPanel().getInputEditor();
		select(lexer);
		Editor lexerInput = panel.getInputPanel().getInputEditor();
		assertNotSame(parserInput, lexerInput);
		assertTrue(parserInput.isDisposed());
		assertFalse(lexerInput.isDisposed());
	}

	public void testDisposingThePanelReleasesTheInputEditor() {
		select(parser);
		attachPanel();
		Editor input = panel.getInputPanel().getInputEditor();
		Disposer.dispose(panel);
		assertTrue(input.isDisposed());
	}

	/** A finished parse belongs to the grammars and start rule it was made with. */
	public void testParseResultIsDroppedWhenTheStartRuleChanges() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		assertNotNull(typeInput("1.2.3"));
		preview.setStartRule(parser, "versionCore");
		PreviewState state = preview.getPreviewState(parser);
		assertEquals("versionCore", state.startRule());
		assertNull(state.parsingResult()); // the reparse hasn't finished
	}

	// ------------------------------

	/** Opens {@code file} in the editor (the tab listener hears the change) and waits for its load. */
	private void select(VirtualFile file) {
		myFixture.openFileInEditor(file);
		waitForGrammarLoads();
	}

	/** What the tool window factory does (the panel shows the selected grammar); light tests run no factories. */
	private void attachPanel() {
		panel = new PreviewPanel(getProject());
		Disposer.register(getTestRootDisposable(), panel);
		waitForGrammarLoads();
	}

	private String inputText() {
		return panel.getInputPanel().getInputEditor().getDocument().getText();
	}

	/** Replaces the input text and waits for the reparse. */
	private ParsingResult typeInput(String text) {
		VirtualFile grammar = panel.getInputPanel().getGrammarFile();
		ParsingResult before = parsingResult(grammar);
		Document doc = panel.getInputPanel().getInputEditor().getDocument();
		return awaitNewResult(grammar, before, () ->
			WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(text)));
	}

	private ParsingResult awaitNewResult(VirtualFile grammar, ParsingResult before, Runnable action) {
		action.run();
		waitFor("reparse", () -> parsingResult(grammar)!=null && parsingResult(grammar)!=before);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		return parsingResult(grammar);
	}

	private ParsingResult parsingResult(VirtualFile grammar) {
		return preview.getPreviewState(grammar).parsingResult();
	}

	/** Edits the grammar's document, saves it, and sends the save event (the VFS listener is off in tests). */
	private void editAndSave(VirtualFile file, UnaryOperator<String> edit) {
		Document doc = FileDocumentManager.getInstance().getDocument(file);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			doc.setText(edit.apply(doc.getText()));
			PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
			FileDocumentManager.getInstance().saveDocument(doc);
		});
		preview.grammarSaved(file);
		waitForGrammarLoads();
	}

	private ParserRuleSpecNode parserRule(String name) {
		PsiFile psi = PsiManager.getInstance(getProject()).findFile(parser);
		for (ParserRuleSpecNode rule : PsiTreeUtil.findChildrenOfType(psi, ParserRuleSpecNode.class)) {
			if ( name.equals(rule.getName()) ) return rule;
		}
		throw new AssertionError("no parser rule " + name);
	}

	/** Saves the grammar's document and sends the save event. */
	private void save(VirtualFile file) {
		editAndSave(file, UnaryOperator.identity());
	}

	private static String ruleName(ParsingResult result) {
		assertNotNull(result);
		ParserRuleContext root = (ParserRuleContext) result.tree;
		return result.parser.getRuleNames()[root.getRuleIndex()];
	}

	private void waitForGrammarLoads() {
		Future<?> done = preview.grammarLoadsDone();
		waitFor("grammar loads", done::isDone);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
	}

	private static void waitFor(String what, BooleanSupplier condition) {
		PlatformTestUtil.waitWithEventsDispatching(what + " did not finish", condition::getAsBoolean, TIMEOUT_S);
	}
}
