# Done

Completed work on branch `grammar-tools`, oldest first. Item numbers are kept from the original
worklog because entries refer to each other by number. Open work is in
[IN_PROGRESS.md](IN_PROGRESS.md); settled decisions in [DECISIONS.md](DECISIONS.md).

## Extract Rule, 2026.2 baseline, antlr4-xl tools

### 1. Refactor > Extract rule applies to all occurrences
- After selecting, IntelliJ's standard "Replace this occurrence only / Replace all N occurrences"
  popup (same as Introduce Variable) highlights the duplicates, then the name dialog opens.
- **Matching is structural, not textual** (prior art: IntelliJ Extract Method duplicate search,
  which compares syntax trees). An occurrence is a run of whole sibling elements in one
  alternative, or a run of whole alternatives in one alternative list, with equal non-hidden
  tokens. So `A` is *not* matched inside `A*`, `~A`, `x=A`, and `B | C` is not matched inside
  `X B | C`. Whitespace and comments are ignored. A parser-rule selection only matches parser
  rules; a lexer-rule selection only matches lexer rules.
- **Name is pre-filled with a guess**: `expr ',' type` → `exprCommaType`, `expr (',' expr)*` →
  `exprList`, `'(' expr ')'` → `parenExpr`, lexer → `UPPER_SNAKE`, taken names get `2`, `3`…,
  ANTLR/Java keywords get a `Rule` suffix. Dialog rejects invalid, wrong-case or taken names.
- All edits are one undoable command. Also fixed: the new rule could be inserted in the middle
  of the next rule's name when it started on the same line as the `;`.
- Code: `refactor/ExtractRuleOccurrences`, `ExtractRuleNameSuggester`, `ExtractRulePlan`
  (pure, no IDE), thin `actions/ExtractRuleAction` + `ChooseExtractedRuleName`.
- Tests (plain JUnit, no IDE fixture, ~5 s):
  `./gradlew test --tests '*ExtractRuleOccurrencesTest' --tests '*ExtractRulePlanTest'`.
  Cases are written as grammar text: `«…»` marks the selection, `[…]` the expected matches.

### 2. Baseline raised to IntelliJ 2026.2 (since-build 262) and Java 25
- 2026.2 runs on JBR 25, so the plugin compiles with a Java 25 toolchain.
- CI and release workflows: single `IU-2026.2.1` entry, JDK 25, `setup-java@v4`.
  Users of older IDEs stay on plugin 1.26.
- Removed old-IDE shims (`Icons` build check, 2017.x `getVariants` override, Gradle version
  conditionals, separate commons-lang3) and replaced all 9 APIs marked for removal. The editor
  factory listener now has the project as parent disposable (it used to leak past project close).
- `gradle.properties`: the local `org.gradle.java.home` line was removed; `JAVA_HOME` points at
  JDK 25, and the Java 25 toolchain finds an installed JDK 25.

### 3. antlr4-xl tools in the plugin: Format and Sort
- antlr4-xl is a **git submodule** at `antlr4-xl/` (URL `../antlr4-xl.git`, relative to origin),
  compiled in its own Gradle source set `xl`. Its meta-grammar is regenerated with the plugin's
  ANTLR 4.13.2, so the plugin ships one runtime. Only the tools listed in `xlTools` in
  `build.gradle` are compiled. Override the location with `-Pantlr4XlDir=...`.
- **Format**: `format/GrammarFormattingService` (`AsyncDocumentFormattingService`; prior art:
  google-java-format and ktfmt plugins) → Code > Reformat Code, reformat on save, reformat
  directory. Width = the code style's right margin. Syntax errors → no change + notification.
- **Sort**: Code > Sort Parser Rules (`actions/SortRulesAction`). Start rule stays first, lexer
  rules move after the parser rules in their original order (token priority), one undo step.
- antlr4-xl commits: `75f368c` (`Formatter.format(text, width)`, `SortRules.sort(text, start)`
  as library APIs; CLI unchanged on 72 grammars) and `c59f813` (comments above a rule move with
  it; no glued `;` after a last rule without final newline; checked on 200 grammars-v4 files).
- Tests: `format/GrammarToolsTest` drives Reformat Code and the Sort action in the IDE fixture.

### 4. Review fixes (Plugin Verifier + code review against master)
- `build.gradle`: `pluginVerification` now verifies against the build IDE (`ideaVersion`). It used
  to fall back to 2020.2.4 (no longer downloadable) because `sinceBuild` is left unset. Mutes
  `TemplateWordInPluginId`: the published ID `org.antlr.intellij.plugin` can't change.
- Extract Rule only accepts whole elements or whole alternatives of one rule
  (`ExtractRuleOccurrences.isUnitRun`, the same model the duplicate search uses); anything else
  shows an error dialog. Fixes selections spanning two rules (overlapping edits corrupted the
  grammar) and selections of a rule name, `:`, `;`, a dangling `|`, or part of an alternative.
- Cross-project bleed: the editor-created listener and the VFS save listener are application-
  wide and had no project filter, so saving a grammar in one project repainted another
  project's preview. Both now ignore other projects' editors/files.
- Preview input document listener is scoped to its editor (`PreviewState.INPUT_LISTENER_SCOPE`,
  disposed on release). For file input it sat on the IDE-wide Document and piled up on each
  grammar switch, reparsing with stale grammars and outliving the project.
- After project close: parse completion and slow-parse callbacks check `projectIsClosed`.
- Formatter: any runtime exception from `xl.Formatter` is logged and reported via `onError`
  instead of leaving the request hanging.

## Backlog items completed

1. Push antlr4-xl to GitHub as `jasonosgood/antlr4-xl`. Pushed (private). CI is not a goal:
   this branch is local and experimental, so CI's failure to fetch the private submodule
   doesn't matter.
3. Linter → inspections with quick fixes:
   - antlr4-xl (branch `plugin-linter-api`, commits `8ffe462` linter API and `61fc364` lint1
     skips unreferenced rules; in the submodule only): `Linter` is class `xl.Linter` with
     `lint(text)`, `fix(text)`, `tokens()`; golden tests pass (`MAIN_CLASS=xl.Linter ./run.sh`).
   - Plugin: `lint/GrammarLintAnnotator` (`ExternalAnnotator`) + `lint/GrammarLintInspection`
     (`ExternalAnnotatorBatchInspection`), "Grammar lint (antlr4-xl)" under Settings ›
     Inspections › ANTLR, on by default as a warning. Prior art: IntelliJ's ESLint integration,
     the same pairing: the annotator runs the whole-file tool off the read lock; the inspection
     gives on/off, severity and Code › Inspect Code. Each finding with edits gets "Fix lintN: …";
     every finding gets "Fix all grammar lint findings in file". A fix can expose new findings
     (removing the parens in `( B | C ) | C` exposes a duplicate `C`), so each fix re-runs the
     linter, and Fix All is stepwise: apply one fix, re-lint, repeat until nothing changes (cap
     1000 steps), under a cancellable progress dialog, then applied as one line diff (one undo).
     Prior art: ESLint `--fix` re-lints and repeats too, but batches non-overlapping fixes per
     pass (as xl's own `fix` does); one fix per step never applies a fix computed against a tree
     another fix has changed. Fixes check the document hasn't changed since the lint. Not linted:
     grammars with syntax errors. Offsets are converted from code points to UTF-16 (emoji test).
   - Tests: `lint/GrammarLintTest` (report, one fix, fix all, cascading fix one/fix all,
     stepwise settles, unused single-child rule, syntax errors, emoji offsets).
4. Tried Extract Rule, Format and Sort by hand in the sandbox; all looked right.
7. Migrated the 7 other fixture tests to `AntlrFixtureTestCase`, including the two on
   `LightPlatformCodeInsightTestCase`. `CreateRuleFixTest` now runs its fix in a write command,
   as the platform does (the old base wrapped every test in one).
8. Ran the IntelliJ Plugin Verifier against 2026.2; see 12–14 for what it found.
9. Typed exception for xl syntax errors; start rule for Sort:
   - xl `GrammarSyntaxException` (extends `IllegalStateException`) from Formatter, SortRules and
     `Linter.lint(text)`; Format and Sort report it as "syntax errors (N)" and log anything else
     as an internal error.
   - Sort keeps the start rule chosen in the ANTLR Preview first (falls back to the first parser
     rule if it no longer exists).
11. Release notes and version. Change notes for the next release are in `plugin.xml`. The
    version comes from the release tag (`-PpluginVersion=${GITHUB_REF_NAME}`), so
    `gradle.properties` stays `next-SNAPSHOT`; tag `1.27` to release.

### From the Plugin Verifier (2026.2.1)
Verifier now: **Compatible**; "can probably be enabled or disabled without IDE restart";
4 deprecated uses, all ANTLR-generated `getTokenNames()` overrides (ours and xl's); 2
experimental uses (`BrowseFolderRunnable`, the never-working file input of 33).

12. Compatibility problem: `PsiTreeElementBase` in `DummyViewTreeElement`. The dummy element
    implements `StructureViewTreeElement` + `ItemPresentation` directly, and the
    `bundledModule("intellij.platform.structureView")` dependency is gone from `build.gradle`.
13. Internal API `PluginManager.getPlugin()` in `projectOpened()`: the version lookup is gone;
    every public way to get our own descriptor is internal API too.
14. Deprecated API uses, 37 → 4:
    - `FileTypeFactory` → `<fileType>` in plugin.xml (this unblocked dynamic loading).
    - Live templates: modernized, then removed entirely (35).
    - `ReadAction.run/compute` → `runBlocking/computeBlocking`; `ServiceManager` →
      `project.getService`; `IconLoader.getIcon(path, Icons.class)`; `ImageUtil.createImage`;
      `updateActionsAsync`; `CaretListener`/`DocumentListener` for the adapters; popups via
      `createPopupChooserBuilder(List)`; `FileSaverDescriptor` with `withExtensionFilter`;
      `OccurrencesChooser.showChooser(Map, Consumer)`; `java.util.function.Predicate` in
      `Utils.filter`; dropped the `spaceExistanceTypeBetweenTokens` override (it returned the
      default).
    - `ProjectFileIndex.getPackageNameByDirectory`: its replacement (`PackageIndex`) is in the
      Java plugin, which we don't require, so `RunANTLROnGrammarFile.packageNameOf` computes it
      from the source root and its package prefix.

### Project lifecycle / state
15. `ANTLRv4PluginController` converted from a `ProjectComponent` to a project service (prior
    art: the platform's "migrating components to services" guide):
    - `@Service(PROJECT)`, `implements Disposable`; `dispose()` replaces `projectClosed()`,
      sets `projectIsClosed`, shuts down the grammar loader.
    - Listeners registered against the service: VFS saves via `VFS_CHANGES` (`BulkFileListener`)
      and the editor-factory listener. No manual uninstall.
    - Editor tab events: `GrammarEditorTabListener` under `<projectListeners>` in plugin.xml,
      forwarding to `editorSelectionChanged` / `editorFileClosed`.
    - Tool windows declared in plugin.xml, filled by `ANTLRv4ToolWindowFactory`; looked up via
      `ToolWindowManager` (tests can set `previewWindowForTests`).
    - Checked by hand in the sandbox (log reviewed): three projects open at once, each with
      its own controller; startup grammar replay; switching grammars and non-grammar files;
      start rule; saves reload only their own project; closing projects disposes each
      controller with nothing running afterwards; no errors or freezes.
16. `uninstallListeners()` disconnected nothing; gone with 15.
17. Fixture tests no longer suppress `ObjectNotDisposedException`. Editor ownership moved to the
    tool windows; the controller holds model state only:
    - `ANTLRv4ToolWindowFactory` creates the `PreviewPanel` (now `Disposable`) and the console,
      each the content's disposer. The panel owns the input editors, the reparse queue and the
      input listener scopes, and releases the editors when disposed; it attaches to and
      detaches from the controller (`attachPreviewPanel`, which also replays the selected
      grammar, and `detachPreviewPanel`). The panel now exists only once its tool window opens.
    - Tool output goes to `ToolOutput`, which buffers (capped at 1 MB, like a console's cycle
      buffer) until the console attaches. Prior art: the Build tool window's event model.
    - Light tests run no tool window factories, so they create no plugin editors; the one test
      that needs a panel (`Issue559Test`) builds it with the test root disposable as parent.
    - Removed: `TestUtils` and its suppression, `PreviewState.releaseEditor`'s unit-test skip,
      the manual fixture-editor releases in `Issue540Test`/`Issue559Test`, and the console
      pause in `GrammarElementRefTest`. 115 tests pass with the platform's leak check on.
    - Checked by hand in the sandbox on grammars-v4 `semver` (log reviewed): preview opened
      late shows the selected grammar; Test Rule with the preview closed keeps the start rule;
      input kept per grammar across switches; Tool Output shows generation output; project
      close disposes cleanly with no errors.
36. Greenfield step 1: characterization tests for the preview (`preview/PreviewPanelTest`,
    12 tests) on grammars-v4 `semver`, copied with its MIT license into
    `src/test/resources/preview/semver`. They build a `PreviewPanel` as the tool window does
    and assert on preview state and parse results: parser + `tokenVocab` lexer loading, first
    rule as default start rule, a panel attached late, parsing and syntax errors, start rule
    changes, input and start rule kept per grammar across switches, lexer grammar alone (no
    parser), save reloads, a saved lexer reloading the parser that uses it, deleting the start
    rule, closing a grammar and disposing the panel releasing editors. The grammars are copied
    to a real temp directory, since loading checks files through `java.io.File`. Stable over
    repeated runs.
    - Speed baseline (`PreviewPerformanceTest`, run with `ANTLR_PERF=1`): `oracle_normal.g4`
      (10,587 lines, 1,830 rules) loads in ~3.2 s warm (3.9 s cold) and parses a one-line
      query in ~330 ms (650 ms cold).
18. Static `RefactorUtils.lexerRuleNameID`; non-final `BAD_*_GRAMMAR`. Generate Lexer Rules
    names `T__N` from the first number not already used in the grammar (and names each literal
    once); the `BAD_*` markers are `final`.

### From code review
19. Extract Rule "replace all" created mutual left recursion. A selection or occurrence at the
    left edge of an alternative of rule `e` that starts with `e` is refused (the selection with
    a reason, occurrences are skipped). `e : '(' «e '*' e» ')'` is still allowed.
20. Extract Rule broke labels and `$x` references (`ExtractRuleOccurrences.problem`): refused
    when an action outside the run uses a label or element only inside it, when an action
    inside it uses anything not defined in it (including `$text`, arguments), or when it holds
    some but not all of a rule's `# label`ed alternatives. Label and action moving together
    (`«y=A {$y.text}»`) is fine.
21. Name dialog accepted keywords. `ExtractRuleNameSuggester.isReserved` (ANTLR words +
    `SourceVersion.isKeyword`) is shared by the suggester and the dialog.
22. Extract Rule passed no `PsiFile` to `runWriteCommandAction`.
23. Sort and Extract parsed on the EDT. Both plan in `ReadAction.nonBlocking` and finish on the
    EDT. Extract re-checks the document's modification stamp before applying, since the user can
    edit while the occurrence popup or name dialog is open.
24. Sort replaced the whole document. `format/TextDiffs.apply` applies the result as a line diff
    (`ComparisonManager`), like the platform does for async formatters; the caret on an unmoved
    line stays put (test).
25. Format on a range did nothing. The service declares `FORMAT_FRAGMENTS`, formats the whole
    grammar and keeps only the changes on the requested lines (`TextDiffs.keepChangesIn`; prior
    art: google-java-format's range formatting).
26. Sort description said lexer rules stay in place; plugin.xml and the javadoc are corrected.
27. xl source set included both `ANTLR4.g4` paths. `build.gradle` includes whichever exists,
    preferring `src/xl/` (the owner's rename in the standalone antlr4-xl repo).
28. Cleanup: `matchRuns` extends each run incrementally; `deepestEnclosing` replaced by
    `Trees.getRootOfSubtreeEnclosingRegion`; dead `getCharIndexOfNextRuleStart` deleted.
29. Stale IDE logs `doc/error.txt`, `doc/error2.txt` deleted, never committed.
31. Redundant grammar reload after each project opens. The save path skips the reload when the
    grammar's text equals the text it was last loaded from (`loadedGrammarText`). Not verified
    in the sandbox; the event's source is still unknown. (Greenfield step 2 replaces this.)
34. Profiler: clicking a decision row threw "Read access is allowed from inside read-action
    only". 2026.2 no longer gives plain Swing listeners an implicit lock, so the profiler row
    click, the parse-tree hierarchy selection and the input radio buttons run their editor work
    in `WriteIntentReadAction.run` (the fix the platform's error message names). The verifier
    lists `WriteIntentReadAction` as experimental API; kept, since the platform prescribes it.
35. Live templates removed (none showed up in the sandbox, and the owner doesn't use them).

### Cleanup and docs
- Removed unreferenced helpers (`ParsingUtils.nextRealToken`/`previousRealToken`/
  `tokenizeANTLRGrammar`, `RefactorUtils.getAncestorWithType`/`childIndexOf`/`replaceText`/
  `insertText`, `ProfilerPanel.getProfilerDataTable`) and unused images (`lexer-templates.png`,
  `pycharm.png`).
- README: live templates section removed; lint, format, sort and the submodule checkout step
  added. ARCHITECTURE: grammar tools section, IntelliJ Platform Gradle Plugin 2.x, tool window
  ownership.
