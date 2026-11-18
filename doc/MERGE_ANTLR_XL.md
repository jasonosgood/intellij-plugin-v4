# Merging antlr4-xl's syntax-error handling (`xl.errors`)

Plan, 2026-11-18. antlr4-xl branch `merge-antlr-errors` (commit 85caa91, a worktree next to the
antlr4-xl checkout) adds package `xl.errors`: friendly, rustc-style syntax errors for any ANTLR
grammar. Design: `antlr4-xl/docs/errors/error-handling.md` (after the merge), with
`prechecks.md` and `sql-prechecks.md`.

## Findings
- **The merge itself doesn't break the plugin.** A trial merge of `plugin-linter-api` with
  `merge-antlr-errors` conflicts only in xl's `CLAUDE.md` (both sides drop the Nateclipse rule),
  and the plugin's main and test code compile against it (`compileJava compileTestJava
  -Pantlr4XlDir=…`; tests not run). The plugin compiles only the `xlTools` classes and one
  meta-grammar, so `xl/errors/*`, `PreCheckLexer.g4`, `expr.g4` and `pom.xml` are ignored.
- **The xl branches have diverged.** xl `main` (and so `merge-antlr-errors`) lacks the plugin's
  three API commits (`Linter` class, `GrammarSyntaxException`, lint1/lint3), which exist only on
  `plugin-linter-api` in the submodule clone. Moving the submodule to `main` alone breaks the
  plugin build. The submodule's `origin/master` is stale; upstream's branch is now `main`.
- **`oracle_normal.g4` is gone on xl `main`** (moved, curated, to `grammar/refactorme.g4`).
  `ANTLRv4HighlightingLexerTest.testLargeGrammarHasNoBadCharacters` silently skips when the file
  is missing; `PreviewPerformanceTest` (with `ANTLR_PERF=1`) fails. Javadoc in `PreviewPanel`,
  `ParserDfaCache` and `LexerDfaCache` cites its timings.
- **`xl.errors` is package-private** throughout (`Diagnostic`, `Range`, `FriendlyErrorStrategy`,
  `PreCheck`, …), so the plugin can't call it yet.
- **A planned API change:** the design doc unifies `Linter.Finding`/`Edit` with
  `Diagnostic`/`Fix`. That changes an `xlTools` signature the plugin uses
  (`GrammarLintAnnotator`), so the plugin changes in the same step.

## Plan

### A. antlr4-xl (on its side)
1. Merge `merge-antlr-errors` into `main`, then `plugin-linter-api` into `main` (resolve
   `CLAUDE.md`). Rebase or reset `plugin-linter-api` onto `main` and push it to origin, so the
   submodule's commits are reachable from upstream.
2. Make public what the plugin will call: `Diagnostic`, `Range`, `Position`, `Severity`,
   `Label`, `Fix`, `Diagnostics`, `PreCheck`, `TokenCheck`, `FriendlyLexer`,
   `FriendlyErrorStrategy`, and their `Options`.
3. Decide when to unify `Linter.Finding` with `Diagnostic` (now, or later as its own step).

### B. Plugin: move to the new xl
1. Bump the submodule to the merged commit. Point the two `oracle_normal.g4` tests at
   `grammar/refactorme.g4` (or copy the grammar into the plugin's test data), and make the
   highlighting test fail rather than skip when the file is missing but the submodule is present.
   Run `./gradlew test` and, with `ANTLR_PERF=1`, re-baseline the timings cited in Javadoc.
2. Add the `xl.errors` classes to `xlTools` in `build.gradle`.

### C. Plugin: use it in the preview
The preview's input is the case `xl.errors` is built for: people using a language, not grammar
authors.
1. **Model.** Annotate the preview input from `Diagnostic` (code, range, labels, fixes) instead
   of the plugin's `SyntaxError` (message and offending token). Convert code-point columns to
   UTF-16 offsets at that one boundary (design D8).
2. **Layer A, `PreCheck`,** on the preview input: invisible, bidi and lookalike characters, bad
   encodings for input loaded from a file. Cheap, and it doesn't change how the parser behaves.
3. **Layer B, `TokenCheck`,** with bracket pairs inferred from the user's grammar's literal
   tokens (`'('`/`')'`, `'['`/`']'`, `'{'`/`'}'`): unclosed and unmatched brackets, with a label
   on the opener.
4. **Fixes as quick fixes** in the input editor ("insert ')'").
5. **Open decision: `FriendlyErrorStrategy` in the preview parser.** It recovers differently
   from ANTLR (stays in recovery until 3 tokens match), so it changes the tree and errors the
   preview shows, while the preview today shows exactly what the generated parser would do.
   Proposal: keep ANTLR's recovery by default; friendly wording as an option. `FriendlyLexer`
   (tokens named `STRING`, `UNTERMINATED_*`, `ERR`) and rule phrases need per-grammar
   conventions; grammars without them skip those checks.

### D. Later
- A lint rule enforcing the error-token naming conventions (`ERR : . ;` last, `UNTERMINATED_*`).
- Diagnostic `code`s as inspection and suppression IDs.

*Prior art:* `xl.errors` follows LSP's `Diagnostic` and rustc/Clang (labels, fix-its); the
design doc's table maps them to IntelliJ annotations, related information and quick fixes, which
step C follows. Keeping ANTLR's recovery in the preview follows its role as an interpreter of the
generated parser's behaviour.
