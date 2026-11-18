# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is
The ANTLR v4 plugin for IntelliJ IDEs: grammar editing (PSI, highlighting, navigation,
completion, refactoring), the ANTLR Preview tool window (interprets a grammar on sample input),
code generation, and grammar tools from the `antlr4-xl` submodule (lint, format, sort).
Branch `grammar-tools` is local and experimental, to be shared publicly later.

## Rules
- **No CI work.** Don't touch `.github/` or `scripts/github_release_notes.py`.
- Commit once the owner has verified a step (or when asked); never push without asking.
- Temp files go in `tmp/` (git-ignored): scratch files, `launch-sandbox.sh` output, and the
  projects opened in the sandbox IDE (`tmp/jps-spike` for the JPS build, `tmp/tokens-check`
  for the preview: semver and a grammar with hidden and skipped tokens; `tmp/oracle-check`, a
  copy of `oracle_normal.g4`, for speed; `tmp/gradle-check`, a Gradle project with the `antlr`
  plugin and the semver grammars). Nothing in `/tmp`, the home directory or a session
  scratchpad.
- Design from prior art: name the mature systems that solve the problem, follow their standard
  answer, and justify each deviation with a "Prior art" note in code or docs.
- `antlr4-xl/` is the owner's project with its own rules ([antlr4-xl/CLAUDE.md](antlr4-xl/CLAUDE.md)):
  Java with ANTLR only (tokens and parse trees, no regex or string processing), and never
  modify its meta-grammar `ANTLR4.g4` without explicit permission. Commit there on branch
  `plugin-linter-api`.
- Keep the two meta-grammars apart (plugin `ANTLRv4Parser.g4` for the PSI, xl `ANTLR4.g4` for
  the tools); `MetaGrammarAgreementTest` guards against drift. See [doc/DECISIONS.md](doc/DECISIONS.md).
- The plugin compiles only the antlr4-xl classes listed in `xlTools` (`build.gradle`), plus the
  parser generated from its meta-grammar. To use another xl class, add it there. Those classes
  are the plugin's API into xl, so a signature change in them breaks the plugin build.
- Don't publish personal details (names, emails, home paths) in committed files.

## Build and run
- Target: IntelliJ 2026.2.1 (since-build 262), Java 25 toolchain, Gradle 9.7, IntelliJ Platform
  Gradle Plugin 2.x. Clone with `--recurse-submodules` (or `git submodule update --init`).
- `./gradlew test`: all tests (232, light fixtures plus plain JUnit; 27 are in `jps-plugin`:
  the generation core shared with the IDE and the code that runs in IntelliJ's build process,
  both Java 11 with no IDE classes). Add `--rerun` to force a
  rerun when Gradle thinks the task is up to date. `ANTLR_PERF=1` also runs
  `PreviewPerformanceTest` (load and parse times on `oracle_normal.g4`).
- Sandbox IDE: `scripts/launch-sandbox.sh [--restart] [--no-build] [--no-project | PROJECT_DIR]`
  (macOS, Apple silicon; ~10 s) or `./gradlew runIde` (anywhere; ~60 s). Its log:
  `.intellijPlatform/sandbox/intellij-plugin-v4/IU-2026.2.1/log/idea.log`. Rebuilding
  (`./gradlew prepareSandbox`) under a running sandbox reloads the plugin in place; each reload
  leaks the old class loader ("Restart advised", tabled in doc/IN_PROGRESS.md), so restart
  (`--restart`) when that matters, and before measuring memory or performance.
  The plugin's own traces log at debug: enable `#org.antlr.intellij.plugin` in Help > Diagnostic
  Tools > Debug Log Settings to see them in `idea.log`.
- `./gradlew verifyPlugin`: Plugin Verifier against the build IDE. It runs `prepareSandbox`
  (through `buildPlugin`), so it also reloads the plugin in a running sandbox.
- Don't edit a project's `.iml` or `.idea` files from outside to set up a sandbox check: the
  sandbox IDE restores module roots from its own cache and writes them back. Change roots in
  the IDE.
- `-Pantlr4XlDir=...` builds against another antlr4-xl checkout.
- Keep Gradle output out of context: run with `-q` or pipe through `tail -30`; on failure, grep
  the report or `build/test-results/test/*.xml` for the failing tests, not the whole log. Read
  large files (`PreviewPanel`, `InputPanel`, `idea.log`) by range, not whole.

## Docs
- [README.md](README.md): features and usage for users.
- [ARCHITECTURE.md](ARCHITECTURE.md): how the plugin is built: PSI, preview, grammar tools.
- [doc/IN_PROGRESS.md](doc/IN_PROGRESS.md): current work, later preview UX, tabled items.
- [doc/DECISIONS.md](doc/DECISIONS.md): settled decisions; read before reopening one.
- [doc/B2_JPS_BUILDER.md](doc/B2_JPS_BUILDER.md): design record for code generation in the JPS
  build; read when working on `jps-plugin`.
- [doc/DONE.md](doc/DONE.md): completed work, a history log; read only when an item number is cited.
- [antlr4-xl/CLAUDE.md](antlr4-xl/CLAUDE.md): the submodule's own guide; its plans and reports
  are in [antlr4-xl/docs/superpowers/plans/](antlr4-xl/docs/superpowers/plans/).
- [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md): upstream's PR checklist
  (read-only here; see Rules).
- [developer-cert-of-origin.txt](developer-cert-of-origin.txt),
  [historical-contributors-agreement.txt](historical-contributors-agreement.txt): upstream's
  contribution terms, relevant before sharing.
- `<change-notes>` in `src/main/resources/META-INF/plugin.xml`: the next release's notes.

## Testing notes
- Fixture tests extend `AntlrFixtureTestCase`. The platform checks after every test that all
  editors were released; keep it that way: editors belong to the UI that shows them (tool
  window content, a panel), never to a project service.
- Light tests run no tool window factories. A test that needs the preview builds a
  `PreviewPanel` with `getTestRootDisposable()` as parent (see `PreviewPanelTest`). Grammars
  the preview loads must be real files (copy test data to a temp directory), since loading
  checks them through `java.io.File`.
- Pure logic (Extract Rule planning, lint fixing) is tested without the IDE; prefer that.
  `TokenRowsTest` builds a `LexerGrammar` from a string, with no files and no IDE.
- To run some tests, name the root project's task: `./gradlew :test --tests '*PreviewPanelTest'`.
  Plain `test --tests` also runs `jps-plugin:test`, which fails with "No tests found for given
  includes" when the filter matches nothing there.
- A preview test that waits for a result compares it by identity with the one before (see
  `typeInput`, `typeInputForTokens` in `PreviewPanelTest`); waiting for "not null" passes on a
  stale result.
- The editor highlights with antlr4-xl's lexer (`ANTLRv4HighlightingLexer`), not the PSI's.
  A highlighting lexer must restart correctly at any token: test it with the platform's
  `LexerTestCase.checkCorrectRestart` (see `ANTLRv4HighlightingLexerTest`).
- `JBLoadingPanel.isLoading()` turns true only once its spinner shows, after the start delay;
  test what the panel asked for instead (`PreviewPanel.busyWith`).

## Diagnosing slowness (lessons, 2026-11-17)
- Measure before fixing. The backlog blamed profiling for slow preview parses; timing the
  parts showed a cold DFA on every parse (DONE.md item 58). Time with `ANTLR_PERF=1` and
  `PreviewPerformanceTest`, or a small Java program in `tmp/` against `build/classes`.
- Slow typing or a sluggish editor: take thread dumps of the sandbox while the owner types
  (`jcmd <pid> Thread.print` every 250 ms; the IDE runs on JBR, find it with `pgrep -f
  antlr-sandbox`), count the EDT's top frames, and grep `idea.log` for floods of `STDERR`
  lines. No "UI was frozen" entries doesn't mean the EDT is idle (DONE.md item 60).
- Check a premise against the code before planning around it: `ParserInterpreter` keeps its
  own DFA per instance, unlike generated parsers; `AsyncPromise.onProcessed` runs on success,
  failure and cancellation alike.
