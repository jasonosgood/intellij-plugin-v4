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

## Preview and grammar model: the greenfield plan (2026-11-15)
Six steps, each tested, checked in the sandbox and committed on its own: characterization
tests; `GrammarCache`; platform async; immutable `PreviewState`; `PreviewService` with
`PreviewListener.TOPIC`; routine logging at debug. Lasting constraints are in
[DECISIONS.md](DECISIONS.md) ("Keep stable").

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
37. Greenfield step 2: grammars as cached values of their files. `parsing/GrammarCache` (project
    service) holds the loaded grammars per `VirtualFile`; an entry is valid while the saved text
    of every file the load read is unchanged (grammar, `tokenVocab` or by-name lexer, a lexer
    looked for but absent, imports). A save event reloads the saved grammar and the open
    grammars that read it, or nothing if no text changed. Removed: `grammarFileMods`,
    `loadedGrammarText` (item 31), `grammarLoadGeneration`, `getAssociatedParserIfLexer`, the
    `modified` flag on tab switches. Preview state is keyed by `VirtualFile`, not path. Grammars
    load from saved text, not the editor's. Planned as `CachedValuesManager`; changed because
    its compute runs in a read action and a 3 s uncancellable load would block typing (see
    DECISIONS). Four tests added: a save event with no change keeps the grammars, an unsaved
    edit doesn't reload, moving the grammars keeps the start rule, creating a missing lexer
    makes the parser stale.
38. Greenfield step 3: platform async. Preview parses run in `ReadAction.nonBlocking`
    with `coalesceBy` (a new input cancels the running parse; before, superseded parses ran to
    the end and could finish last), `expireWith(panel)` and `finishOnUiThread`; the cancel
    button cancels the promise; the slow-parse notice no longer blocks the EDT for 300 ms
    (`BackgroundTaskUtil.executeAndTryWait` did). Grammar loads stay on the sequential pool
    executor, without the read lock, and finish through `AppUIExecutor.expireWith`; a
    `CheckedDisposable` replaces the `projectIsClosed` flag. `grammarLoadsDone` stays as the
    tests' handle, now the load's own future including its UI follow-up. Two tests added: the
    newest input wins when parses overlap; breaking the lexer (a token the parser uses renamed)
    shows as syntax errors in the parser's preview.
39. Greenfield step 4: immutable preview state, one owner. `PreviewState` is a record (parser
    and lexer grammar, start rule, parse result) that the controller replaces whole, on the
    EDT only; loads hand their grammars to the EDT instead of writing them on the loader thread.
    A parse result is published only if the grammars and start rule it was made with are still
    current; new grammars or a new start rule drop the old result. The profiler and the mouse
    handlers keep one snapshot, so the grammar and parse they read belong together. `InputPanel`
    owns one input editor (for the grammar shown) and the input text per grammar; switching
    grammars releases the previous editor (before, each grammar switched away from kept a hidden
    one). `RunANTLROnGrammarFile` reads grammars from `GrammarCache`. Start rule rules moved
    into `PreviewState.withGrammars`: a start rule deleted from the grammar now falls back to
    the first rule (before, it was cleared: a step 1 finding); a grammar that fails to load
    keeps it. Removed: `synchronized`, `grammarToPreviewState` (public), `autoSetStartRule`,
    `ensureStartRuleExists`, `INPUT_LISTENER_SCOPE`. Tests: 2 added (switching releases the
    previous editor; a start rule change drops the old result), 2 changed (deleted start rule;
    panel disposal).
40. Renaming the start rule keeps it as the preview's start rule. `StartRuleRenameListenerProvider`
    (`refactoring.elementListenerProvider`; prior art: run configurations following a renamed
    class) records the new name in `PreviewState.renamedStartRule`; the next load that has the
    name makes it the start rule. Not at once: the preview reloads on save only, so until then
    the loaded grammar still has the old name. Undo restores the old name. Two tests added. In
    light tests a rename can't update references (the grammars are outside the project), so the
    tests rename `semver`, which nothing refers to.
41. Greenfield step 5: the controller is split into services; the UI subscribes to a topic.
    `ANTLRv4PluginController` and `ANTLRv4StartupActivity` are deleted. `PreviewService`
    (project service) holds the preview states and start rules, runs parses and handles grammar
    selection, save and close; it publishes `PreviewListener.TOPIC` events (grammar selected,
    reloaded, closed, deselected; start rule changed; parse slow, completed, cancelled) and never
    calls the UI. `PreviewPanel` subscribes with itself as disposable and, when created, asks for
    the selected grammar (replaces `attachPreviewPanel` and the replay bookkeeping; the startup
    selection may now switch the panel twice, harmlessly). `GrammarCache` owns the loader thread
    (`submit`). `ToolOutput` is a project service. `GrammarEditorTabListener` holds the editor
    glue (save on tab switch, hide on close). Grammar-editor clicks for the profiler go through
    the `EditorFactory` event multicaster, scoped to the panel, instead of per-editor listeners.
    The VFS save listener is subscribed by the service. Auto-refresh is a service flag the
    annotator reads. Fixed on the way: a reload now refreshes the grammar shown, not the saved
    one (a save of a grammar not shown parsed the shown input with the wrong grammar). Plan
    deviation: input text stays with the panel (step 4), not the preview service.
42. Greenfield step 6, logging: routine events (action invocations, grammar loads, switches,
    editor creation, ANTLR tool runs) log at debug, not info; `ProfilerPanel`'s `System.err`
    is a logger warning. Enable with Help > Diagnostic Tools > Debug Log Settings
    (`#org.antlr.intellij.plugin`).

## Saving and code generation (2026-11-15 to 2026-11-16)
Principle: the plugin never decides when a file is saved, and nothing it does on a save writes
files the user didn't ask for; code generation belongs to the build. The rules are in
[DECISIONS.md](DECISIONS.md) ("Saving and code generation", "Code generation in the JPS
build"); the JPS builder's design is in [B2_JPS_BUILDER.md](B2_JPS_BUILDER.md). Prior art:
Gradle's `antlr` plugin, `antlr4-maven-plugin`, protobuf-gradle-plugin; JPS source-generator
builders (annotation processing, Android's `R.java`). Grammar-Kit and JFlex ship no JPS
builder.

43. Saving A.1: switching tabs no longer saves the grammar. `GrammarEditorTabListener` only
    switches the preview; an edit reaches the preview through the platform's saves (⌘S,
    Autosave settings). Prior art: no platform editor saves on a tab switch. Test:
    `PreviewPanelTest.testSwitchingTabsDoesNotSave`. Checked by the owner in the sandbox.
44. Saving A.2: Generate ANTLR Recognizer and Test Rule save all documents first
    (`MyActionUtils.saveAllBeforeRunning`), not only the current grammar, so an unsaved
    `tokenVocab` lexer isn't left behind. Prior art: Build and Run save all documents first.
    Fixed on the way: Generate on an unsaved grammar with auto-generate on (the default) saved
    it and generated nothing, expecting the save to generate; the save only did that for a lexer
    whose parser was previewed. Generate now always generates. Tests:
    `PreviewPanelTest.testTestRuleSavesAllDocuments`, `testGenerateSavesAllDocumentsAndGenerates`.
45. Saving A.3: the preview reloads when any grammar it uses changes on disk, open in an editor
    or not: a previewed grammar, or one a previewed grammar's load read (`tokenVocab` lexer,
    imports, a lexer looked for but absent). So a `git checkout` or an edit outside the IDE
    refreshes it too. `PreviewService.grammarFilesChanged` filters by the cached loads' inputs,
    which also keeps other projects' grammars out (VFS events are application-wide); the VFS
    listener passes content changes and creations. A changed grammar that isn't previewed
    itself only reloads the grammars using it. Prior art: VFS listeners see every change,
    whatever caused it. Test: `PreviewPanelTest.testChangingAClosedLexerOnDiskReloadsTheParser`
    (fails with the old open-files filter).
46. Saving A.4: saves write nothing. `PreviewService.grammarSaved` no longer runs
    `RunANTLROnGrammarFile`, which regenerated a lexer's Java and `.tokens` into `gen/` (or,
    with auto-generate off, wrote the vocabulary next to the grammar) whenever a lexer a
    previewed parser used changed, as a modal task. `RunANTLROnGrammarFile` now only serves
    Generate: its `forceGeneration` flag, staleness check and vocabulary-only branch are gone.
    - The editor's checks (`GrammarIssuesCollector`) read a `tokenVocab` lexer's tokens from
      the lexer grammar as the editor shows it, saved or not (captured in `snapshot` through
      PSI, `importVocab` in memory), as the preview does, instead of the `.tokens` file in
      `-lib` or `gen/`. A `.tokens` from elsewhere, via `-lib`, is still read when there is no
      lexer grammar beside the parser. Test: `validation/TokenVocabIssuesTest` (all three
      failed before: with no `.tokens` on disk, the lexer's tokens were reported as implicit).
    - The preview also reloads when a grammar it uses is deleted, moved or renamed
      (`grammarFilesRemoved`; `GrammarFileListener` maps the VFS events). Tests:
      `PreviewPanelTest.testSavingTheLexerWritesNoFiles`, `testDeletingTheLexerReloadsTheParser`.
    - `Issue540Test` deleted: it pinned the write-on-save (and wrote into
      `src/test/resources/gen/`); `testSavingTheLexerWritesNoFiles` replaces it.
    - Rule: a `.tokens` file is generated output, written only into the generated sources
      directory by Generate or the build. Prior art: ANTLR writes it beside the recognizer;
      the Gradle and Maven ANTLR plugins put it in their generated-sources directory.
    - plugin.xml change notes cover A.1–A.4.
    - Known regression, fixed with B.5 (item 53): with no generated `.tokens`, the
      parser's editor reports "cannot find tokens file" on its `tokenVocab` option.
    - Checked by the owner in the sandbox.
47. Saving A.5: the per-grammar "Auto-generate parsers upon save" setting (`autoGen`, on by
    default) is removed; since A.4 it did nothing. Its checkbox stays in the GUI Designer form
    `ConfigANTLRDialogPanel.form`, hidden in code (`ConfigANTLRPerGrammar.initAntlrFields`),
    since the `antlr4.properties` backlog item replaces the whole dialog (owner's call,
    2026-11-16). Saved settings with `autoGen` still load: the XML serializer skips unknown
    properties. Also removed `ANTLRv4GrammarProperties.PROP_LANGUAGE`, unused since A.4.
    Checked by the owner in the sandbox.
48. Build B.2 steps 0 and 1: the `jps-plugin` subproject (Java 11, JPS API from the bundled
    Java plugin), a spike builder seen in a sandbox build, and the generation core extracted
    from `RunANTLROnGrammarFile` (`GenerationArgs`, `AntlrGenerator`, `ToolMessage`). No
    behavior change for the Generate action.
49. Build B.2 step 2: the build process reads the per-grammar settings from `.idea/misc.xml`
    (`AntlrModelSerializerExtension`); the lookup rules moved to the core (`SettingsLookup`).
50. Build B.2 step 3: `AntlrSourceGeneratorBuilder` generates before the Java compiler,
    registers outputs per grammar, and compiles generated Java in the same build. Deleting
    `gen/` and `out/` and rebuilding regenerates. Checked by the owner in the sandbox.
51. Build B.2 step 4: the builder steps aside for Gradle and Maven modules and for output
    directories that aren't source roots; `OutputRootOffer` offers "Mark as generated sources
    root" (creating the directory if missing); messages name the directory relative to the
    module; a warning when the module's ANTLR runtime differs from the tool's. Checked by the
    owner in the sandbox. Temp files and sandbox test projects moved to `tmp/` in the repo.
52. Build B.2 step 5: README ("Generating code", and the stale "builds on save" text removed),
    change notes, ARCHITECTURE.md ("Code generation"), DECISIONS.md.
53. Build B.5: no "cannot find tokens file" (error 114) on a `tokenVocab` parser without a
    generated `.tokens` file. Once the lexer grammar's vocabulary is imported, the annotator
    ignores that error, as the preview does (`PluginIgnoreMissingTokensFileErrorManager`). The
    error had also stopped ANTLR's analysis, hiding warnings such as `EPSILON_CLOSURE`.
    `TokenVocabIssuesTest` covers both. Checked by the owner in the sandbox (grammars-v4 `semver`).
54. Build B.1, B.3, B.4: Generate ANTLR Recognizer reports in one notification
    (`GenerationReport`): where the files went, or the errors with "Go to error"; it no longer
    says "generated" when ANTLR reported errors, and no longer writes to Tool Output. In a
    Gradle or Maven module (`ExternalBuildOf`, the IDE's side of the builder's `ExternalBuild`)
    it steps aside and offers "Generate anyway". README and change notes updated.
55. A.: stray `.tokens` warning. ANTLR reads a `tokenVocab` `.tokens` from `-lib` (default: the
    grammar's directory) before the output directory, so one left there by an older plugin or
    a hand-run `antlr4` shadows the generated one, with possibly stale token types. The
    generation core (`AntlrGenerator.strayTokensFile`) warns, on the `tokenVocab` value, when
    `V.tokens` and its lexer `V.g4` are both in `-lib` and `-lib` isn't the output directory;
    a `.tokens` without its lexer is a supplied vocabulary and is left alone. The build warns;
    Generate asks first, before the Gradle/Maven check: Delete Stray File, Continue Anyway or
    Cancel (owner's call, 2026-11-16).
56. Plugin reload in a running sandbox: `launch-sandbox.sh` passed `-Dplugin.path`, a directory
    `idea.plugins.path` also scans, so the IDE held the plugin twice and refused every unload;
    the old classes then ran against the new jars ("Cannot find suitable constructor"). Without
    it, rebuilding under the sandbox unloads and reloads the plugin. The class loader still
    leaks through a platform icon cache (tabled, doc/IN_PROGRESS.md).

## Preview: speed and feedback on large grammars (2026-11-17)
Measured on `oracle_normal.g4`. Preview parses went from ~330 ms per keystroke to ~27 ms;
the preview shows a spinner while it works; typing in the grammar is fast. Lessons are in
CLAUDE.md ("Diagnosing slowness").

57. Preview Tokens tab, and a lexer grammar on its own (verified in the sandbox, 2026-11-17).
    A lexer grammar alone used to show "Issues with parser and/or lexer grammar(s) prevent
    preview" though nothing was wrong; now its input is tokenized without a parser
    (`ParsingUtils.lexText`) and the preview opens on the Tokens tab, the tree tabs saying
    "Lexer grammar: see the Tokens tab". Every grammar gets the tab: index, type, escaped
    text, line:col, channel (blank for the default; rows off it greyed); selecting a row
    selects its text in the input. `LexingResult` sits in `PreviewState` beside
    `parsingResult`, which keeps always having a parser; a parse fills the token stream to EOF
    afterwards, so lexer errors past where the parser stopped are now reported, as
    `grun -tokens` does. Channel names come from the grammar: ANTLR 4.13.2's
    `createLexerInterpreter` shifts user channels by two. Input between tokens gets
    "(skipped)" rows, and input a lexer error dropped "(error)" rows, behind a "Show skipped
    text" toggle (DECISIONS.md, "Skipped text in the Tokens tab"). Prior art: `grun -tokens`,
    ANTLR Lab. Tests: `TokenRowsTest` (plain JUnit), `PreviewPanelTest`.
58. Preview parses reuse the grammar's prediction cache (verified in the sandbox, 2026-11-17).
    A one-line query on `oracle_normal.g4` took ~330 ms on every keystroke. Measured: profiling and exact
    ambiguity detection cost almost nothing (~350 ms plain vs ~315 ms profiled, ~10 ms either
    way warm); the time was the DFA, which started empty for each new `PreviewParser`
    (`ParserInterpreter` keeps one per instance). `ParserDfaCache` keeps one ATN, DFA and
    `PredictionContextCache` per loaded `Grammar` object (weak keys, so a reload starts
    fresh), as generated parsers share static ones. Now the first parse after a load takes
    ~310 ms and later ones ~27 ms. The Profiler's numbers describe a warm parser (DFA states
    accumulate; fewer cache misses). Gating profiling on the Profiler tab was tried and
    dropped: no measured gain. Tests: `ParserDfaCacheTest` (plain JUnit);
    `PreviewPerformanceTest` prints cold and warm parse times.
59. The preview shows when it is loading a grammar or parsing. Loading or parsing a large
    grammar took seconds with no sign of progress. `PreviewService` owns a busy state on the
    EDT: a grammar load counts from its request (waiting on the loader thread included) until
    its EDT follow-up ends; a parse from `parse()` until its promise is processed (done,
    failed, cancelled or superseded). It publishes `PreviewListener.busyChanged`, and
    `PreviewPanel` covers the result tabs with a `JBLoadingPanel` spinner ("Loading
    grammar…", "Parsing…") after `SLOW_PARSE_NOTICE_DELAY_MS` (300 ms, the platform's
    progress postpone time), so quick work doesn't flash it. The input editor and the Cancel
    button stay usable. Prior art: `JBLoadingPanel` in the VCS log and diff viewer. Tests:
    `PreviewPanelTest` (parse and grammar load, the load held behind a blocked loader thread).
60. Typing in a large grammar was slow; now fast (verified in the sandbox, 2026-11-17).
    On `oracle_normal.g4` (10,588 lines), thread dumps showed
    the editor's highlighting lexer re-lexing the rest of the file on the EDT after edits, and
    48,500 "token recognition error" lines in `idea.log` in a minute. The plugin's lexer
    didn't restart correctly: its `insideOptionsBlock` field wasn't in the saved state, so a
    restart could lex everything after an `options { }` in options mode (the platform's
    `LexerTestCase.checkCorrectRestart` fails on a five-line grammar); the adaptor library's
    `ANTLRLexerState` compares mode stacks by array identity, so states never matched while a
    mode was pushed; and ANTLR's `ConsoleErrorListener` printed every error. Now the editor
    highlights with antlr4-xl's lexer (no modes, state always 0) through
    `ANTLRv4HighlightingLexer`, mapped to the plugin's token types; unmatched text is one bad
    character, lexing resumes after it. The PSI keeps the plugin's lexer, which lexes from the
    start, now without the console listener. Brace matching inside actions and `[...]` is gone
    (one token each). Neither lexer reports an error on grammars-v4 (1,102 files); a full lex
    of `oracle_normal.g4` takes ~4 ms. Prior art: the platform's separate
    highlighting and parsing lexers. Tests: `ANTLRv4HighlightingLexerTest`.

## Preview: behaves like other IntelliJ previews (2026-11-17)
Four steps, each checked in the sandbox and committed on its own. The conventions are in
[DECISIONS.md](DECISIONS.md). Prior art: Compose Preview and SwiftUI previews; the IntelliJ UI
guidelines.

61. The preview pauses on grammar errors; the Tool Output window is gone (verified in the
    sandbox, 2026-11-17). Grammars opt into the platform's problem-file tracking
    (`GrammarProblemFileFilter`, a `problemFileHighlightFilter`; `WolfTheProblemSolver`), so
    grammars with errors are underlined in the Project view. Before loading, `PreviewService`
    checks the grammar and the files its last load read (lexer, imports); with errors it
    doesn't load, and `ProblemListener` reloads once they go. A load that fails anyway (a
    grammar never opened, so never checked) records a `LoadProblem` in `GrammarCache.Loaded`
    and keeps the last good grammars and start rule (before, a failed load lost the start
    rule). Two cases ANTLR doesn't count as errors get their own reason: a parser grammar's
    lexer not found, a combined grammar with no lexer rules. `PreviewPanel` shows an Error
    banner (`EditorNotificationPanel`) over the last results, dimmed and inert; no link to the
    file, which is open and selected already. Tree-node messages became `StatusText` empty
    states. ANTLR's messages from the preview's load go to the debug log. Known: with
    auto-refresh, a load can start before the tracker has the new errors; it fails and pauses
    the same way. Prior art: Compose Preview, SwiftUI previews; the IntelliJ UI guidelines
    (Banner, Empty state). Tests: four in `PreviewPanelTest` (the real tracker plugged into
    the light fixture's stand-in, as the platform's daemon tests do).
62. The preview says when it is out of date (verified in the sandbox, 2026-11-17). It loads saved
    text only, so while the grammar or a file its last load read (lexer, imports) has unsaved
    changes, an Info banner says "Preview out of date. X.g4 has unsaved changes." with a Save
    link, which saves those files (the user's save; the save reloads as any does). The results
    stay as they are, not dimmed: they are right for the saved grammar. Paused wins over out of
    date: the errors must go first. `PreviewService.getUnsavedInput` (`FileDocumentManager.
    isFileModified`, what the editor tab and Save All go by), fed by a document listener and
    `FileDocumentManagerListener` (saved, reverted, reloaded) as `PreviewListener.unsavedChanged`.
    Prior art: Compose Preview's "out of date" banner. Tests: four in `PreviewPanelTest`.
63. Generate ANTLR Recognizer reports in the Build tool window (verified in the sandbox,
    2026-11-17). Each run is one entry ("Generate X.g4") with ANTLR's messages under it, each
    going to its place in the grammar (`GenerationBuildOutput`, events sent to
    `BuildViewManager`), and the output directory when it succeeds. The window opens by itself
    only on failure (the platform's build default); a balloon with "Go to error" only on
    failure; the status bar otherwise. Uses the platform's event builders, marked
    experimental (the event implementations are internal), and `FilePosition(Path, …)` (the
    `File` constructors are deprecated for removal in 2026.2). Prior art: actions running a
    Gradle or Maven task; the IDE's build output, which shows the JPS builder's ANTLR messages
    the same way. Tests: three in `GenerationBuildOutputTest`.
64. Speed search in the preview's Hierarchy and Tokens tabs (verified in the sandbox,
    2026-11-17). Typing finds a node in Hierarchy, collapsed ones included (`TreeSpeedSearch`),
    or a row in Tokens by its Type or Text (`TableSpeedSearch`; not by "#", so "3" finds the
    text 3); matches are highlighted (`SpeedSearchUtil`, Hierarchy's renderer now a
    `ColoredTreeCellRenderer`). A found Hierarchy node or token row selects its text in the
    input, as a click does. Left out of the parse tree tab, a drawing with no selection
    (DECISIONS.md). Prior art: the Project and Structure views. Tests: two in `PreviewPanelTest`.

## Code generation follow-ups (2026-11-17)
65. "Don't warn again" on Generate's Gradle or Maven notification (verified in the sandbox,
    2026-11-17, on `tmp/gradle-check`; this also checked item 54's "not generated" and
    "Generate anyway"). It generates now and remembers "Generate anyway" for the project
    (`PropertiesComponent`, as `OutputRootOffer`'s "Don't offer again"); later Generates
    generate without the notification. Prior art: a dialog's "Don't ask again" remembers its
    answer. README and change notes updated. Test: `GenerateParserActionTest`.

## Preview: speed follow-ups (2026-11-17 to 2026-11-18)
66. Preview lexers share the grammar's prediction cache (verified in the sandbox, 2026-11-18).
    Measured on `oracle_normal.g4` first: lexing itself wasn't slow, but making the lexer cost
    ~15 ms per parse (`Grammar.createLexerInterpreter` round-trips the ATN through the
    serializer on every call), about half of a warm parse. `LexerDfaCache` keeps one ATN, DFA
    and context cache per loaded `LexerGrammar` (weak keys), as `ParserDfaCache` does, with
    channel names by channel number. Now: making the lexer ~0 ms, warm parse ~30 → ~14 ms,
    5,000 lines lex in 15–28 ms instead of 35–45. Tests: three in `LexerDfaCacheTest` (same
    tokens as ANTLR's interpreter, modes and channels included); `PreviewPerformanceTest`
    prints lexing times.
67. Shorter typing wait (verified in the sandbox, 2026-11-18). The reparse waits the IDE's
    autoreparse delay (300 ms by default) instead of a fixed 500 ms. The owner chose no
    indication during it, since warm parses now take ~14 ms (DECISIONS.md).
