# Progress: Extract Rule, 2026.2 baseline, antlr4-xl tools (2026-11-14)

## Done

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

### 3. antlr4-xl tools in the plugin: Format and Sort
- antlr4-xl is a **git submodule** at `antlr4-xl/` (URL `../antlr4-xl.git`, relative to origin),
  compiled in its own Gradle source set `xl`. Its meta-grammar is regenerated with the plugin's
  ANTLR 4.13.2, so the plugin ships one runtime. Only the tools listed in `xlTools` in
  `build.gradle` are compiled. Override the location with `-Pantlr4XlDir=...`.
- **Format**: `format/GrammarFormattingService` (`AsyncDocumentFormattingService`; prior art:
  google-java-format and ktfmt plugins) → Code > Reformat Code, reformat on save, reformat
  directory. Width = the code style's right margin. Syntax errors → no change + notification.
- **Sort**: Code > Sort Parser Rules (`actions/SortRulesAction`). Start rule stays first, lexer
  rules keep their order (token priority), one undo step.
- antlr4-xl commits: `75f368c` (`Formatter.format(text, width)`, `SortRules.sort(text, start)`
  as library APIs; CLI unchanged on 72 grammars) and `c59f813` (comments above a rule move with
  it; no glued `;` after a last rule without final newline; checked on 200 grammars-v4 files).
- Tests: `format/GrammarToolsTest` drives Reformat Code and the Sort action in the IDE fixture.

### 4. Review fixes (Plugin Verifier + `/code-review high master`)
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
  disposed on release, controller as backstop). For file input it sat on the IDE-wide Document and
  piled up on each grammar switch, reparsing with stale grammars and outliving the project.
- After project close: parse completion and slow-parse callbacks check `projectIsClosed`; the
  reparse `MergingUpdateQueue` is disposed with the controller.
- Formatter: any runtime exception from `xl.Formatter` is logged and reported via `onError`
  instead of leaving the request hanging.

## Notes
- Full suite: `./gradlew test` → 105 tests, 0 failures (after items 12–31).
- Sandbox: `launch-sandbox.sh` (see the user's Claude memory). Demo grammar used for Extract Rule
  lived in the session scratchpad; recreate from the test cases if needed.
- `gradle.properties`: the local `org.gradle.java.home` line was removed; `JAVA_HOME` already
  points at JDK 25, and the Java 25 toolchain finds an installed JDK 25.
- antlr4-xl's own rules (its CLAUDE.md): tokens and parse trees only, no regex or string
  processing, `var`, commit at logical breakpoints. Its working tree has the owner's staged
  renames in progress (`grammar/ANTLR4.g4` → `src/xl/ANTLR4.g4`); the Gradle build accepts
  either location.
- CodeEraser was removed from the machine (plugin, marketplace, Homebrew package and tap,
  daemon, `.ce/` index). Its pre-edit duplicate check caused `test/.../AntlrFixtureTestCase`
  (a shared fixture base class), which is worth keeping.

## Backlog
1. ~~Push antlr4-xl to GitHub as `jasonosgood/antlr4-xl`.~~ Pushed (private). CI is not a goal:
   this branch is local and experimental, so CI's failure to fetch the private submodule
   doesn't matter.
2. **Extract Rule on lexer rules should create a `fragment`.** Extracting `[0-9]+` from `INT`
   currently creates a new token rule `NEW_RULE`, which changes how the input is tokenized.
3. **Linter → inspections with quick fixes.** xl's `Finding(Token, rule, message, List<Edit>)`
   maps to `LocalInspectionTool` + `LocalQuickFix`. Needs `Linter` (a Java 25 compact source
   file) turned into a class with a callable API returning findings for a text, no file I/O.
4. ~~Try Extract Rule, Format and Sort by hand in the sandbox.~~ Done; all looked right.
5. Canonicalize (tabled): decide whether the action rewrites the file or shows a diff view.
6. Offsets: ANTLR char streams count code points, the editor counts UTF-16 chars, so grammars
   with characters outside the BMP (emoji) can shift Extract Rule offsets. Pre-existing.
7. Migrate the 7 other fixture tests to `AntlrFixtureTestCase`.
8. ~~Run the IntelliJ Plugin Verifier against 2026.2.~~ Done; see 12–14 for what it found.
   Report: `build/reports/pluginVerifier/IU-262.9437.185/report.html`.
9. Small: typed exception instead of `IllegalStateException` for xl syntax errors; optional
   `--start` rule for Sort; consider formatting after sorting.
10. Longer term: the plugin and xl each carry an ANTLR meta-grammar (`ANTLRv4Parser.g4`,
    `ANTLR4.g4`); consider converging.
11. Release: change notes in `plugin.xml`, version bump.

### From the Plugin Verifier (2026.2.1)
Verifier now: **Compatible**; "can probably be enabled or disabled without IDE restart";
4 deprecated uses, all ANTLR-generated `getTokenNames()` overrides (ours and xl's); 2
experimental uses (`BrowseFolderRunnable`, the never-working file input of 33).
12. ~~Compatibility problem: `PsiTreeElementBase` in `DummyViewTreeElement`.~~ Done: the dummy
    element implements `StructureViewTreeElement` + `ItemPresentation` directly, and the
    `bundledModule("intellij.platform.structureView")` dependency is gone from `build.gradle`.
13. ~~**Internal API**: `PluginManager.getPlugin()` in `projectOpened()`.~~ Done (15): the
    version lookup is gone; every public way to get our own descriptor is internal API too.
14. ~~Deprecated API uses: 51 → 37.~~ Done, 37 → 4:
    - `FileTypeFactory` → `<fileType>` in plugin.xml (this unblocked dynamic loading).
    - Live templates: modernized, then removed entirely (35).
    - `ReadAction.run/compute` → `runBlocking/computeBlocking`; `ServiceManager` →
      `project.getService`; `IconLoader.getIcon(path, Icons.class)`; `ImageUtil.createImage`;
      `updateActionsAsync`; `CaretListener`/`DocumentListener` for the adapters; the manual
      input document listener now has the editor's listener scope as parent disposable; popups
      via `createPopupChooserBuilder(List)`; `FileSaverDescriptor` with `withExtensionFilter`;
      `OccurrencesChooser.showChooser(Map, Consumer)`; `java.util.function.Predicate` in
      `Utils.filter`; dropped the `spaceExistanceTypeBetweenTokens` override (it returned the
      default).
    - `ProjectFileIndex.getPackageNameByDirectory`: its replacement (`PackageIndex`) is in the
      Java plugin, which we don't require, so `RunANTLROnGrammarFile.packageNameOf` computes it
      from the source root and its package prefix.

### Project lifecycle / state
15. ~~Convert `ANTLRv4PluginController` from a `ProjectComponent` to a project service.~~ Done
    (prior art: the platform's "migrating components to services" guide):
    - `@Service(PROJECT)`, `implements Disposable`; `dispose()` replaces `projectClosed()`,
      sets `projectIsClosed`, releases preview editors, shuts down the grammar loader.
    - Listeners registered against the service: VFS saves via `VFS_CHANGES` (`BulkFileListener`),
      the editor-factory listener, the console. No manual uninstall.
    - Editor tab events: `GrammarEditorTabListener` under `<projectListeners>` in plugin.xml,
      forwarding to `editorSelectionChanged` / `editorFileClosed`.
    - Tool windows declared in plugin.xml, filled by `ANTLRv4ToolWindowFactory`; looked up via
      `ToolWindowManager` (tests can set `previewWindowForTests`).
    - UI parts (preview panel, console) and listeners are created on the EDT by
      `projectOpened()`, run from `ANTLRv4StartupActivity` (`ProjectActivity`), or earlier by
      `getPreviewPanel()` on the EDT. After creating the panel it replays the selected grammar,
      since its tab may have been selected before the panel existed.
    - Checked by hand in the sandbox (log reviewed): three projects open at once, each with
      its own controller; startup grammar replay; switching grammars and non-grammar files;
      start rule; saves reload only their own project; closing projects disposes each
      controller with nothing running afterwards; no errors or freezes.
    - The preview reparse queue and input listener scopes use the controller (not the project)
      as parent disposable; the preview toolbar has an explicit target component.
16. ~~`uninstallListeners()` disconnected nothing.~~ Gone with 15.
17. Fixture tests suppress `ObjectNotDisposedException` for the plugin's editors. **Still
    needed** (checked: 28 tests fail without it): the light test project lives for the whole
    run, so the controller's console and preview editors outlive each test, and the platform
    checks after every test that all editors were released.
18. ~~Static `RefactorUtils.lexerRuleNameID`; non-final `BAD_*_GRAMMAR`.~~ Done: Generate Lexer
    Rules names `T__N` from the first number not already used in the grammar (and names each
    literal once); the `BAD_*` markers are `final`.

### From code review
19. ~~Extract Rule "replace all" creates mutual left recursion.~~ Done: a selection or occurrence
    at the left edge of an alternative of rule `e` that starts with `e` is refused (the
    selection with a reason, occurrences are skipped). `e : '(' «e '*' e» ')'` is still allowed.
20. ~~Extract Rule breaks labels and `$x` references.~~ Done (`ExtractRuleOccurrences.problem`):
    refused when an action outside the run uses a label or element only inside it, when an
    action inside it uses anything not defined in it (including `$text`, arguments), or when it
    holds some but not all of a rule's `# label`ed alternatives. Label and action moving
    together (`«y=A {$y.text}»`) is fine.
21. ~~Name dialog accepts keywords.~~ Done: `ExtractRuleNameSuggester.isReserved` (ANTLR words +
    `SourceVersion.isKeyword`) is shared by the suggester and the dialog.
22. ~~Extract Rule passes no `PsiFile` to `runWriteCommandAction`.~~ Done.
23. ~~Sort and Extract parse on the EDT.~~ Done: both plan in `ReadAction.nonBlocking` and finish
    on the EDT. Extract re-checks the document's modification stamp before applying, since the
    user can edit while the occurrence popup or name dialog is open.
24. ~~Sort replaces the whole document.~~ Done: `format/TextDiffs.apply` applies the result as a
    line diff (`ComparisonManager`), like the platform does for async formatters; the caret on
    an unmoved line stays put (test).
25. ~~Format on a range does nothing.~~ Done: the service declares `FORMAT_FRAGMENTS`, formats
    the whole grammar and keeps only the changes on the requested lines
    (`TextDiffs.keepChangesIn`; prior art: google-java-format's range formatting).
26. ~~Sort description says lexer rules stay in place.~~ Done: plugin.xml and the javadoc say
    lexer rules move after the parser rules in their original order (checked with xl).
27. ~~xl source set includes both `ANTLR4.g4` paths.~~ Done: `build.gradle` includes whichever
    exists, preferring `src/xl/` (the owner's rename in the standalone antlr4-xl repo); built
    against both the submodule and the standalone checkout.
28. ~~Cleanup.~~ Done: `matchRuns` extends each run incrementally; `deepestEnclosing` replaced by
    `Trees.getRootOfSubtreeEnclosingRegion` (`ExtractRuleOccurrences.enclosingRule`, also used
    by `ExtractRulePlan`, so one walk); dead `getCharIndexOfNextRuleStart` deleted.
29. ~~`doc/error2.txt` (stale IDE log) is staged; `doc/error.txt` is untracked. Unstage/delete.~~
    Done: both deleted, never committed.
30. Extract Rule's `update()` doesn't check the selection's shape. Keeping it that way: it matches
    the platform (Extract Method stays enabled and explains a bad selection), and the rejection
    dialog now names the specific reason (19, 20).
31. ~~Redundant grammar reload after each project opens.~~ Fixed, not verified in the sandbox:
    the save path skips the reload when the grammar's text equals the text it was last loaded
    from (`loadedGrammarText`). The event's source is still unknown.
34. ~~Profiler: clicking a decision row threw "Read access is allowed from inside read-action
    only".~~ Fixed: 2026.2 no longer gives plain Swing listeners an implicit lock, so the profiler
    row click, the parse-tree hierarchy selection and the input radio buttons run their editor
    work in `WriteIntentReadAction.run` (the fix the platform's error message names).
35. ~~Live templates.~~ Removed (none showed up in the sandbox, and the owner doesn't use them):
    the `templates/` package, `liveTemplates/lexer/user.xml` and their plugin.xml entries.
32. Platform warning "use project as a parent disposable" (no stack trace) appears about once
    per project open. Still there after our last project-parented disposables moved to the
    controller, so it comes from the platform, not this plugin. Ignore.
33. Preview input from a file (the "file" radio button) has never worked, and the owner doesn't
    use it. Not fixing; the listener fixes in section 4 only stop it from leaking.
