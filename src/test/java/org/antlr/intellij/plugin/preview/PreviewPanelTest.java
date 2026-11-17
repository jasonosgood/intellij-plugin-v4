package org.antlr.intellij.plugin.preview;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver;
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl;
import com.intellij.openapi.Disposable;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.ui.SpeedSearchBase;
import com.intellij.ui.speedSearch.SpeedSearchSupply;
import com.intellij.ui.table.JBTable;
import org.antlr.intellij.plugin.GrammarEditorTabListener;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.actions.GenerateParserAction;
import org.antlr.intellij.plugin.actions.TestRuleAction;
import org.antlr.intellij.plugin.parsing.GrammarCache;
import org.antlr.intellij.plugin.parsing.LexingResult;
import org.antlr.intellij.plugin.parsing.LoadProblem;
import org.antlr.intellij.plugin.parsing.ParsingResult;
import org.antlr.intellij.plugin.psi.ParserRuleSpecNode;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/**
 * Characterization tests for the ANTLR Preview: what it does today, recorded before the
 * preview and grammar model are rewritten (doc/DONE.md, greenfield plan). Asserts on
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

	/** The preview shows it is parsing from the parse's start until its result is in. */
	public void testParseShowsBusyUntilItFinishes() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		assertNull(panel.busyWith());
		ParsingResult before = parsingResult(parser);
		preview.parse(parser, "1.0.0");
		assertTrue(preview.isParsing());
		assertEquals("Parsing…", panel.busyWith());
		waitFor("parse", () -> parsingResult(parser)!=before && !preview.isParsing());
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertNull(panel.busyWith());
	}

	/** The preview shows it is loading from a load's request until its grammars are in, waiting included. */
	public void testGrammarLoadShowsBusyUntilItFinishes() throws Exception {
		select(parser);
		attachPanel();
		assertFalse(preview.isLoading());
		CountDownLatch release = new CountDownLatch(1);
		try {
			// occupy the loader thread, so the load waits behind this
			GrammarCache.getInstance(getProject()).submit(() -> {
				try { release.await(TIMEOUT_S, TimeUnit.SECONDS); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return null;
			});
			preview.grammarSaved(parser); // a load is requested even if nothing changed
			assertTrue(preview.isLoading());
			assertEquals("Loading grammar…", panel.busyWith());
		}
		finally {
			release.countDown();
		}
		waitForGrammarLoads();
		assertFalse(preview.isLoading());
		assertNull(panel.busyWith());
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

	public void testLexerGrammarAloneHasNoParser() {
		select(lexer);
		attachPanel();
		PreviewState state = preview.getPreviewState(lexer);
		assertEquals("SemanticVersionLexer", state.lexerGrammar().name);
		assertNull(state.parserGrammar());
		assertNull(state.startRule());
		assertTrue(state.isLexerOnly());
		assertTrue(panel.isEnabled());
		assertTrue(panel.isTokensTabSelected());
	}

	/** A lexer grammar alone is tokenized without a parser, and the Tokens tab lists the tokens. */
	public void testLexerGrammarAloneTokenizesTheInput() {
		select(lexer);
		attachPanel();
		LexingResult result = typeInputForTokens("1.2.0-RC");
		assertNull(parsingResult(lexer));
		assertEquals(List.of("NUMBER", "DOT", "NUMBER", "DOT", "NUMBER", "DASH", "RC", "EOF"), tokenTypes(result));
		assertTrue(result.syntaxErrorListener().getSyntaxErrors().isEmpty());
		assertEquals(8, panel.getTokensPanel().getRows().size());
	}

	public void testLexerErrorsInALexerGrammarAloneAreReported() {
		select(lexer);
		attachPanel();
		LexingResult result = typeInputForTokens("1.2!");
		assertFalse(result.syntaxErrorListener().getSyntaxErrors().isEmpty());
		assertEquals(TokenRows.Kind.ERROR, panel.getTokensPanel().getRows().get(3).kind()); // after 1 . 2
	}

	public void testSkippedTextCanBeHidden() {
		select(lexer);
		attachPanel();
		typeInputForTokens("1.2!");
		assertEquals(5, panel.getTokensPanel().getRows().size()); // NUMBER DOT NUMBER (error) EOF
		panel.getTokensPanel().getShowSkipped().doClick();
		assertEquals(4, panel.getTokensPanel().getRows().size());
	}

	/** A parse carries the whole token stream, through EOF, even where the parser stopped early. */
	public void testParseCarriesTheWholeTokenStream() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "versionCore"); // stops before the pre-release part
		typeInput("1.2.3-rc");
		LexingResult tokens = preview.getPreviewState(parser).lexingResult();
		assertNotNull(tokens);
		assertEquals(List.of("NUMBER", "DOT", "NUMBER", "DOT", "NUMBER", "DASH", "RC", "EOF"), tokenTypes(tokens));
		assertEquals(8, panel.getTokensPanel().getRows().size());
	}

	public void testSelectingATokenSelectsItsText() {
		select(lexer);
		attachPanel();
		typeInputForTokens("10.20.30");
		panel.getTokensPanel().getTable().setRowSelectionInterval(2, 2); // the second NUMBER
		assertEquals("20", panel.getInputPanel().getInputEditor().getSelectionModel().getSelectedText());
	}

	/** Typing in the Tokens tab searches the Type and Text columns, not the others. */
	public void testSpeedSearchInTokens() {
		select(lexer);
		attachPanel();
		typeInputForTokens("1.2.3");
		JBTable table = panel.getTokensPanel().getTable();
		speedSearch(table).findAndSelectElement("DOT");
		assertEquals(1, table.getSelectedRow());
		speedSearch(table).findAndSelectElement("3"); // the Text "3", not the "#" 3 (the second DOT)
		assertEquals(4, table.getSelectedRow());
	}

	/** Typing in the Hierarchy tab finds nodes inside collapsed ones. */
	public void testSpeedSearchInHierarchy() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		typeInput("1.2.3-rc");
		JTree tree = panel.hierarchyViewer.getTree();
		speedSearch(tree).findAndSelectElement("preRelease");
		assertNotNull("nothing selected in " + tree.getModel().getRoot(), tree.getSelectionPath());
		assertTrue(tree.getSelectionPath().getLastPathComponent().toString().startsWith("preRelease"));
	}

	private static SpeedSearchBase<?> speedSearch(JComponent component) {
		SpeedSearchSupply supply = SpeedSearchSupply.getSupply(component, true);
		assertNotNull("no speed search on " + component, supply);
		return (SpeedSearchBase<?>) supply;
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

	/** Unsaved changes to the grammar leave the preview out of date, and it says so until saved. */
	public void testUnsavedEditShowsOutOfDate() {
		select(parser);
		attachPanel();
		assertNull(panel.shownUnsaved());
		editWithoutSaving(parser, text -> text + "\nextra : NUMBER ;\n");
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertEquals(parser, panel.shownUnsaved());

		save(parser);
		assertNull(panel.shownUnsaved());
	}

	/** The lexer the parser uses counts too, open or not. */
	public void testUnsavedLexerShowsTheParserOutOfDate() {
		select(parser);
		attachPanel();
		editWithoutSaving(lexer, text -> text + "\nTILDE : '~';\n");
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertEquals(lexer, panel.shownUnsaved());
	}

	/** Save on the banner saves what the preview reads; the save then reloads it, as any does. */
	public void testSaveOnTheBannerSavesTheGrammar() {
		select(parser);
		attachPanel();
		Document doc = editWithoutSaving(parser, text -> text + "\nextra : NUMBER ;\n");
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		panel.clickSaveOnBanner();
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
		assertNull(panel.shownUnsaved());
	}

	/** Paused says more than out of date: errors must be fixed, and a save alone won't resume. */
	public void testPausedHidesOutOfDate() {
		WolfTheProblemSolver wolf = realProblemTracker();
		select(parser);
		attachPanel();
		editWithoutSaving(parser, text -> text + "\nextra : NUMBER ;\n");
		inBackground(() -> wolf.reportProblemsFromExternalSource(parser, this));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertEquals(LoadProblem.errorsIn(parser), panel.shownPause());
		assertNull(panel.shownUnsaved());

		inBackground(() -> wolf.clearProblemsFromExternalSource(parser, this));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		waitForGrammarLoads();
		assertNull(panel.shownPause());
		assertEquals(parser, panel.shownUnsaved());
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

	/** A lexer changed on disk while not open (VCS checkout, another editor) reloads the parser
	 *  that uses it, without becoming a previewed grammar itself. */
	public void testChangingAClosedLexerOnDiskReloadsTheParser() throws Exception {
		select(parser);
		assertFalse(FileEditorManager.getInstance(getProject()).isFileOpen(lexer));
		WriteAction.runAndWait(() ->
			VfsUtil.saveText(lexer, VfsUtil.loadText(lexer).replace("DOT  : '.';", "PERIOD : '.';")));
		preview.grammarFilesChanged(List.of(lexer));
		waitForGrammarLoads();
		LexerGrammar lexerGrammar = preview.getPreviewState(parser).lexerGrammar();
		assertNotNull(lexerGrammar);
		assertTrue(lexerGrammar.tokenNameToTypeMap.containsKey("PERIOD"));
		assertFalse(preview.hasPreviewState(lexer));
	}

	/** A save writes nothing: no generated code or {@code .tokens} file, even for a lexer whose
	 *  parser is previewed. Code generation belongs to Generate and the build. Replaces
	 *  {@code Issue540Test} (upstream issue #540: {@code .tokens} rewritten too often), which
	 *  pinned the old write-on-save. */
	public void testSavingTheLexerWritesNoFiles() {
		select(parser);
		editAndSave(lexer, text -> text + "\nTILDE : '~';\n");
		assertTrue(preview.getPreviewState(parser).lexerGrammar().tokenNameToTypeMap.containsKey("TILDE"));
		assertNull(generated("SemanticVersionLexer.tokens"));
		assertNull(generated("SemanticVersionLexer.java"));
	}

	/** Deleting a lexer the previewed parser uses reloads the parser, which then has no lexer. */
	public void testDeletingTheLexerReloadsTheParser() throws Exception {
		select(parser);
		String lexerPath = lexer.getPath();
		WriteAction.runAndWait(() -> lexer.delete(this));
		preview.grammarFilesRemoved(List.of(lexerPath));
		waitForGrammarLoads();
		LexerGrammar missing = preview.getPreviewState(parser).lexerGrammar();
		assertTrue(missing==null || !"SemanticVersionLexer".equals(missing.name));
	}

	/** A grammar with errors in the editor isn't loaded; the preview pauses, naming the file,
	 *  and loads it once the errors go.
	 */
	public void testGrammarWithErrorsInTheEditorIsNotLoaded() {
		WolfTheProblemSolver wolf = realProblemTracker();
		inBackground(() -> wolf.reportProblemsFromExternalSource(parser, this)); // as highlighting finding errors does
		assertTrue(wolf.isProblemFile(parser));
		select(parser);
		attachPanel();
		assertNull(preview.getPreviewState(parser).parserGrammar()); // not even tried
		assertEquals(LoadProblem.errorsIn(parser), panel.shownPause());

		inBackground(() -> wolf.clearProblemsFromExternalSource(parser, this));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		waitForGrammarLoads();
		assertEquals("SemanticVersionParser", preview.getPreviewState(parser).parserGrammar().name);
		assertNull(panel.shownPause());
	}

	/** Errors in the lexer the parser uses pause the parser's preview too. */
	public void testErrorsInTheLexerPauseTheParserPreview() {
		WolfTheProblemSolver wolf = realProblemTracker();
		select(parser);
		attachPanel();
		assertNull(panel.shownPause());
		inBackground(() -> wolf.reportProblemsFromExternalSource(lexer, this));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertEquals(LoadProblem.errorsIn(lexer), panel.shownPause());
		inBackground(() -> wolf.clearProblemsFromExternalSource(lexer, this));
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertNull(panel.shownPause());
	}

	/** A grammar never checked in the editor can still fail to load: the preview pauses the same way. */
	public void testGrammarThatFailsToLoadPausesAndResumesWhenFixed() {
		select(parser);
		attachPanel();
		preview.setStartRule(parser, "semver");
		typeInput("1.2.3");
		editAndSave(parser, text -> text + "\nbroken : ( ;\n");
		assertEquals(LoadProblem.errorsIn(parser), panel.shownPause());
		editAndSave(parser, text -> text.replace("\nbroken : ( ;\n", ""));
		assertNull(panel.shownPause());
		assertEquals("semver", ruleName(typeInput("2.0.0")));
	}

	/** A parser grammar whose lexer can't be found: not an error to ANTLR, so the preview says it. */
	public void testMissingLexerPausesWithTheReason() throws Exception {
		select(parser);
		attachPanel();
		String lexerPath = lexer.getPath();
		WriteAction.runAndWait(() -> lexer.delete(this));
		preview.grammarFilesRemoved(List.of(lexerPath));
		waitForGrammarLoads();
		LoadProblem pause = panel.shownPause();
		assertNotNull(pause);
		assertEquals("Lexer grammar SemanticVersionLexer.g4 not found.", pause.reason());
		assertEquals(parser, pause.file());
	}

	/** Test Rule saves all documents first, as Run does, so an unsaved lexer edit isn't left behind. */
	public void testTestRuleSavesAllDocuments() {
		select(lexer);
		Document lexerDoc = editWithoutSaving(lexer, text -> text.replace("DOT  : '.';", "PERIOD : '.';"));
		select(parser);
		caretAt("major = NUMBER"); // Test Rule tests the rule the caret is in
		myFixture.testAction(new TestRuleAction());
		waitForGrammarLoads();
		assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(lexerDoc));
		assertEquals("versionCore", preview.getPreviewState(parser).startRule());
	}

	/** Generate saves all documents first, as Build does, and generates even if a save was needed. */
	public void testGenerateSavesAllDocumentsAndGenerates() throws Exception {
		select(lexer);
		Document lexerDoc = editWithoutSaving(lexer, text -> text + "\nTILDE : '~';\n");
		select(parser);
		Document parserDoc = editWithoutSaving(parser, text -> text + "\nextra : NUMBER ;\n");
		myFixture.testAction(new GenerateParserAction());
		assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(lexerDoc));
		assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(parserDoc));
		waitFor("generated parser", () -> generated("SemanticVersionParser.java")!=null);
		assertTrue(generatedText("SemanticVersionParser.java").contains("ExtraContext"));
		assertTrue(generatedText("SemanticVersionLexer.java").contains("TILDE"));
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

	/** Edits the grammar's document and leaves it unsaved. */
	/** Light tests get a stand-in problem-file tracker that tracks nothing; plug in the real one,
	 *  as the platform's daemon tests do.
	 */
	private WolfTheProblemSolver realProblemTracker() {
		WolfTheProblemSolver wolf = WolfTheProblemSolver.getInstance(getProject());
		if ( wolf instanceof MockWolfTheProblemSolver mock ) {
			WolfTheProblemSolver real = WolfTheProblemSolverImpl.createTestInstance(getProject());
			mock.setDelegate(real);
			Disposer.register(getTestRootDisposable(), () -> {
				mock.resetDelegate();
				if ( real instanceof Disposable d ) Disposer.dispose(d);
			});
		}
		return wolf;
	}

	/** Runs {@code r} on a pooled thread and waits for it: the tracker reports off the EDT, as
	 *  highlighting does.
	 */
	private static void inBackground(Runnable r) {
		Future<?> done = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(r);
		waitFor("background work", done::isDone);
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
	}

	private Document editWithoutSaving(VirtualFile file, UnaryOperator<String> edit) {
		Document doc = FileDocumentManager.getInstance().getDocument(file);
		assertNotNull(doc);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(edit.apply(doc.getText())));
		assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
		return doc;
	}

	/** Puts the selected editor's caret inside the first occurrence of {@code text}. */
	private void caretAt(String text) {
		int offset = myFixture.getEditor().getDocument().getText().indexOf(text);
		assertTrue(text, offset>=0);
		myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
	}

	/** A generated file anywhere under the grammars' directory, or null. */
	private File generated(String name) {
		List<File> found = FileUtil.findFilesByMask(Pattern.compile(Pattern.quote(name)), dir);
		return found.isEmpty() ? null : found.getFirst();
	}

	private String generatedText(String name) throws IOException {
		File file = generated(name);
		assertNotNull(name, file);
		return FileUtil.loadFile(file);
	}

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

	/** Replaces the input text and waits for it to be tokenized (a lexer grammar alone). */
	private LexingResult typeInputForTokens(String text) {
		VirtualFile grammar = panel.getInputPanel().getGrammarFile();
		LexingResult before = preview.getPreviewState(grammar).lexingResult();
		Document doc = panel.getInputPanel().getInputEditor().getDocument();
		WriteCommandAction.runWriteCommandAction(getProject(), () -> doc.setText(text));
		waitFor("tokenizing", () -> {
			LexingResult now = preview.getPreviewState(grammar).lexingResult();
			return now!=null && now!=before;
		});
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		return preview.getPreviewState(grammar).lexingResult();
	}

	private static List<String> tokenTypes(LexingResult result) {
		return TokenRows.rows(result.tokens(), result.vocabulary(), result.channelNames(), result.errorSpans())
			.stream().filter(r -> r.kind()==TokenRows.Kind.TOKEN).map(TokenRows.Row::type).toList();
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
