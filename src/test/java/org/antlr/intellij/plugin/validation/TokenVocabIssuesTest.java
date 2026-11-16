package org.antlr.intellij.plugin.validation;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.v4.tool.ErrorType;

import java.util.List;
import java.util.Objects;

/** The editor's grammar checks know a {@code tokenVocab} lexer's tokens from the lexer grammar
 *  itself, as the editor shows it, not from a generated {@code .tokens} file. Light fixtures
 *  keep files in memory, so the ANTLR tool finds no {@code .tokens} file on disk here.
 */
public class TokenVocabIssuesTest extends AntlrFixtureTestCase {
	private static final String LEXER = "lexer grammar L;\nA : 'a';\nB : 'b';\nWS : [ \\t\\r\\n]+ -> skip;\n";

	public void testTokensDefinedInTheLexerAreNotImplicit() {
		myFixture.addFileToProject("L.g4", LEXER);
		PsiFile parser = myFixture.addFileToProject("P.g4", "parser grammar P;\noptions { tokenVocab=L; }\ns : A B ;\n");
		assertEquals(List.of(), implicitTokens(parser));
	}

	/** With no generated {@code .tokens} file, nothing is reported: not "cannot find tokens
	 *  file" (B.5), which ANTLR raises though the vocabulary came from the lexer grammar. */
	public void testNoIssuesWithoutATokensFile() {
		myFixture.addFileToProject("L.g4", LEXER);
		PsiFile parser = myFixture.addFileToProject("P.g4", "parser grammar P;\noptions { tokenVocab=L; }\ns : A B ;\n");
		assertEquals(List.of(), errorTypes(parser));
	}

	/** The missing-file error also stopped ANTLR before its analysis, hiding what it finds. */
	public void testAnalysisRunsWithoutATokensFile() {
		myFixture.addFileToProject("L.g4", LEXER);
		PsiFile parser = myFixture.addFileToProject("P.g4", "parser grammar P;\noptions { tokenVocab=L; }\ns : (A?)* ;\n");
		assertEquals(List.of(ErrorType.EPSILON_CLOSURE), errorTypes(parser));
	}

	/** Control: a token the lexer doesn't define is still reported. */
	public void testTokenMissingFromTheLexerIsImplicit() {
		myFixture.addFileToProject("L.g4", LEXER);
		PsiFile parser = myFixture.addFileToProject("P.g4", "parser grammar P;\noptions { tokenVocab=L; }\ns : A Z ;\n");
		assertEquals(List.of("Z"), implicitTokens(parser));
	}

	/** The lexer as the editor shows it, saved or not: prior art, cross-file checks in the IDE
	 *  see other files' unsaved edits. */
	public void testUnsavedLexerEditIsSeen() {
		PsiFile lexer = myFixture.addFileToProject("L.g4", LEXER);
		PsiFile parser = myFixture.addFileToProject("P.g4", "parser grammar P;\noptions { tokenVocab=L; }\ns : A C ;\n");
		Document doc = PsiDocumentManager.getInstance(getProject()).getDocument(lexer);
		assertNotNull(doc);
		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			doc.insertString(doc.getTextLength(), "C : 'c';\n");
			PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
		});
		assertEquals(List.of(), implicitTokens(parser));
	}

	/** Runs the annotator's two phases, as {@code ANTLRv4ExternalAnnotator} does. */
	private List<String> implicitTokens(PsiFile grammar) {
		return issues(grammar).stream()
			.filter(issue -> issue.getMsg()!=null && issue.getMsg().getErrorType()==ErrorType.IMPLICIT_TOKEN_DEFINITION)
			.map(issue -> issue.getMsg().getArgs()[0].toString())
			.toList();
	}

	/** ANTLR's errors and warnings; the plugin's own infos (unused parser rule) have no type. */
	private List<ErrorType> errorTypes(PsiFile grammar) {
		return issues(grammar).stream()
			.map(issue -> issue.getMsg()==null ? null : issue.getMsg().getErrorType())
			.filter(Objects::nonNull)
			.toList();
	}

	private List<GrammarIssue> issues(PsiFile grammar) {
		GrammarIssuesCollector.Input input = ReadAction.computeBlocking(() -> GrammarIssuesCollector.snapshot(grammar));
		assertNotNull(input);
		return GrammarIssuesCollector.collectGrammarIssues(input);
	}
}
