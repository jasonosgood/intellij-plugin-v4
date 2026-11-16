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
  projects opened in the sandbox IDE (`tmp/jps-spike`). Nothing in `/tmp`, the home directory or
  a session scratchpad.
- Design from prior art: name the mature systems that solve the problem, follow their standard
  answer, and justify each deviation with a "Prior art" note in code or docs.
- `antlr4-xl/` is the owner's project with its own rules ([antlr4-xl/CLAUDE.md](antlr4-xl/CLAUDE.md)):
  Java with ANTLR only (tokens and parse trees, no regex or string processing), and never
  modify its meta-grammar `ANTLR4.g4` without explicit permission. Commit there on branch
  `plugin-linter-api`.
- Keep the two meta-grammars apart (plugin `ANTLRv4Parser.g4` for the PSI, xl `ANTLR4.g4` for
  the tools); `MetaGrammarAgreementTest` guards against drift. See [doc/DECISIONS.md](doc/DECISIONS.md).
- Don't publish personal details (names, emails, home paths) in committed files.

## Build and run
- Target: IntelliJ 2026.2.1 (since-build 262), Java 25 toolchain, Gradle 9.7, IntelliJ Platform
  Gradle Plugin 2.x. Clone with `--recurse-submodules` (or `git submodule update --init`).
- `./gradlew test`: all tests (180, light fixtures plus plain JUnit; 24 are in `jps-plugin`:
  the generation core shared with the IDE and the code that runs in IntelliJ's build process,
  both Java 11 with no IDE classes). Add `--rerun` to force a
  rerun when Gradle thinks the task is up to date. `ANTLR_PERF=1` also runs
  `PreviewPerformanceTest` (load and parse times on `oracle_normal.g4`).
- Sandbox IDE: `scripts/launch-sandbox.sh [--restart] [--no-build] [--no-project | PROJECT_DIR]`
  (macOS, Apple silicon; ~10 s) or `./gradlew runIde` (anywhere; ~60 s). Its log:
  `.intellijPlatform/sandbox/intellij-plugin-v4/IU-2026.2.1/log/idea.log`. Never rebuild
  (`prepareSandbox`) under a running sandbox: the IDE half-reloads the plugin. Use `--restart`.
  The plugin's own traces log at debug: enable `#org.antlr.intellij.plugin` in Help > Diagnostic
  Tools > Debug Log Settings to see them in `idea.log`.
- `./gradlew verifyPlugin`: Plugin Verifier against the build IDE. It runs `prepareSandbox`
  (through `buildPlugin`), so the rule above applies: not under a running sandbox.
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
