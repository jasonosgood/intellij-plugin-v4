package org.antlr.intellij.plugin.lint;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.format.TextDiffs;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xl.GrammarSyntaxException;
import xl.Linter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the antlr4-xl linter on a grammar and shows its findings, each with its fix (if it has
 * one) and a "fix all" for the file. Enabled and configured through {@link GrammarLintInspection}.
 * Grammars with syntax errors aren't linted; the plugin's parser already reports those.
 */
public class GrammarLintAnnotator extends ExternalAnnotator<GrammarLintAnnotator.Input, GrammarLintAnnotator.Result> {
	private static final Logger LOG = Logger.getInstance(GrammarLintAnnotator.class);

	/** The text to lint and the document version it came from, so fixes can tell if it's stale. */
	public record Input(String text, long stamp) {
	}

	/** A replacement of {@code range} of the linted text by {@code text}. */
	public record Replacement(TextRange range, String text) {
	}

	/** One finding: where to highlight, what to say, and its fix ({@code replacements} may be empty). */
	public record Problem(TextRange range, String lint, String message, List<Replacement> replacements) {
	}

	public record Result(Input input, List<Problem> problems) {
	}

	@Override
	public @Nullable Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
		if ( !(file instanceof ANTLRv4FileRoot) || hasErrors ) return null;
		return new Input(editor.getDocument().getText(), editor.getDocument().getModificationStamp());
	}

	@Override
	public @Nullable Input collectInformation(@NotNull PsiFile file) { // batch: Inspect Code
		if ( !(file instanceof ANTLRv4FileRoot) ) return null;
		Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
		return new Input(file.getText(), doc==null ? -1 : doc.getModificationStamp());
	}

	@Override
	public @Nullable Result doAnnotate(Input input) {
		if ( input==null ) return null;
		try {
			return new Result(input, lint(input.text()));
		}
		catch (GrammarSyntaxException e) {
			return null;
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) { // a bug in the linter; don't break highlighting
			LOG.warn("antlr4-xl linter failed", e);
			return null;
		}
	}

	/** The linter's findings with token indexes turned into editor offsets. */
	static List<Problem> lint(String text) {
		Linter linter = new Linter();
		List<Linter.Finding> findings = linter.lint(text);
		TokenStream tokens = linter.tokens();
		List<Problem> problems = new ArrayList<>();
		for (Linter.Finding f : findings) {
			List<Replacement> replacements = new ArrayList<>();
			for (Linter.Edit e : f.edits()) {
				TextRange range = range(text, tokens.get(e.from()), tokens.get(e.to()));
				replacements.add(new Replacement(range, e.text()==null ? "" : e.text()));
			}
			replacements.sort(Comparator.comparingInt((Replacement r) -> r.range().getStartOffset()).reversed());
			problems.add(new Problem(range(text, f.at(), f.at()), lintId(f.message()), f.message(), replacements));
		}
		return problems;
	}

	/** Most fixes {@link #fixAll} applies before giving up on a grammar that keeps changing. */
	static final int MAX_STEPS = 1000;

	/**
	 * Applies fixes one at a time, re-linting after each, until no fix changes the text: a fix
	 * can expose new findings and invalidate the others' token ranges. Returns the fixed text.
	 * <p>
	 * Prior art: ESLint {@code --fix} also re-lints after fixing and repeats (up to 10 passes),
	 * but applies every non-overlapping fix per pass; one fix per step costs more parses and
	 * never applies a fix computed against a tree another fix has changed.
	 */
	static String fixAll(String text, @Nullable ProgressIndicator progress) {
		for (int step = 0; step<MAX_STEPS; step++) {
			if ( progress!=null ) {
				progress.checkCanceled();
				progress.setText2("Fix " + (step + 1));
			}
			String next = null;
			for (Problem p : lint(text)) {
				if ( p.replacements().isEmpty() ) continue;
				String fixed = applyTo(text, p.replacements());
				if ( !fixed.equals(text) ) {
					next = fixed;
					break;
				}
			}
			if ( next==null ) return text;
			text = next;
		}
		LOG.warn("antlr4-xl fixes still changing the grammar after " + MAX_STEPS + " steps");
		return text;
	}

	/** {@code text} with {@code replacements} (last offset first) applied. */
	static String applyTo(String text, List<Replacement> replacements) {
		StringBuilder buf = new StringBuilder(text);
		for (Replacement r : replacements) {
			buf.replace(r.range().getStartOffset(), r.range().getEndOffset(), r.text());
		}
		return buf.toString();
	}

	/** Editor (UTF-16) range of tokens first..last; ANTLR counts code points. */
	private static TextRange range(String text, Token first, Token last) {
		return new TextRange(utf16(text, first.getStartIndex()), utf16(text, last.getStopIndex() + 1));
	}

	private static int utf16(String text, int codePointIndex) {
		return text.offsetByCodePoints(0, Math.min(codePointIndex, text.codePointCount(0, text.length())));
	}

	/** "lint4" from "lint4: parentheses ...". */
	private static String lintId(String message) {
		int colon = message.indexOf(':');
		return colon>0 ? message.substring(0, colon) : "lint";
	}

	@Override
	public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
		if ( result==null ) return;
		HighlightSeverity severity = InspectionProjectProfileManager.getInstance(file.getProject()).getCurrentProfile()
			.getErrorLevel(HighlightDisplayKey.find(GrammarLintInspection.SHORT_NAME), file).getSeverity();
		boolean anyFix = result.problems().stream().anyMatch(p -> !p.replacements().isEmpty());
		for (Problem p : result.problems()) {
			var builder = holder.newAnnotation(severity, p.message()).range(p.range());
			if ( !p.replacements().isEmpty() ) {
				builder = builder.withFix(new FixOne(p, result.input().stamp()));
			}
			if ( anyFix ) {
				builder = builder.withFix(new FixAll(result.input().stamp()));
			}
			builder.create();
		}
	}

	@Override
	public @Nullable String getPairedBatchInspectionShortName() {
		return GrammarLintInspection.SHORT_NAME;
	}

	/** Re-runs highlighting, and so the linter, on {@code file}. */
	private static void relint(Project project, PsiFile file) {
		PsiDocumentManager.getInstance(project).commitAllDocuments();
		DaemonCodeAnalyzer.getInstance(project).restart(file);
	}

	/** True if the document still holds the text that was linted. */
	private static boolean current(Editor editor, long stamp) {
		return editor!=null && editor.getDocument().getModificationStamp()==stamp;
	}

	/** Applies one finding's fix. */
	static final class FixOne implements IntentionAction {
		private final Problem problem;
		private final long stamp;

		FixOne(Problem problem, long stamp) {
			this.problem = problem;
			this.stamp = stamp;
		}

		@Override
		public @NotNull String getText() {
			return "Fix " + problem.lint() + ": " + summary(problem.message());
		}

		@Override
		public @NotNull String getFamilyName() {
			return "Fix grammar lint finding";
		}

		@Override
		public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
			return current(editor, stamp);
		}

		@Override
		public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
			if ( !current(editor, stamp) ) return; // re-linted soon; its offsets are stale
			Document doc = editor.getDocument();
			for (Replacement r : problem.replacements()) { // last offset first
				doc.replaceString(r.range().getStartOffset(), r.range().getEndOffset(), r.text());
			}
			relint(project, file); // the fix may expose new findings
		}

		@Override
		public boolean startInWriteAction() {
			return true;
		}

		/** The message without its "lintN: " prefix and the quoted grammar text after the last ':'. */
		private static String summary(String message) {
			String rest = message.substring(message.indexOf(':') + 1).trim();
			int colon = rest.indexOf(':');
			return colon>0 ? rest.substring(0, colon) : rest;
		}
	}

	/** Applies every fix in the file, one at a time with a re-lint after each ({@link #fixAll}), as one undoable edit. */
	static final class FixAll implements IntentionAction, PriorityAction {
		private final long stamp;

		FixAll(long stamp) {
			this.stamp = stamp;
		}

		@Override
		public @NotNull String getText() {
			return "Fix all grammar lint findings in file";
		}

		@Override
		public @NotNull String getFamilyName() {
			return getText();
		}

		@Override
		public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
			return current(editor, stamp);
		}

		@Override
		public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
			if ( !current(editor, stamp) ) return;
			Document doc = editor.getDocument();
			String text = doc.getText();
			String fixed;
			try {
				fixed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
					() -> fixAll(text, ProgressManager.getInstance().getProgressIndicator()),
					getText(), true, project);
			}
			catch (GrammarSyntaxException e) {
				return;
			}
			catch (ProcessCanceledException e) {
				return;
			}
			if ( fixed.equals(text) || !current(editor, stamp) ) return; // edited while fixing
			WriteCommandAction.runWriteCommandAction(project, getText(), null, () -> TextDiffs.apply(doc, fixed), file);
			relint(project, file);
		}

		@Override
		public boolean startInWriteAction() {
			return false;
		}

		@Override
		public @NotNull Priority getPriority() {
			return Priority.LOW;
		}
	}
}
