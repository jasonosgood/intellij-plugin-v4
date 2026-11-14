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
  shows an error hint. Fixes selections spanning two rules (overlapping edits corrupted the
  grammar) and selections of a rule name, `:`, `;`, a dangling `|`, or part of an alternative.
- Cross-project bleed: the editor-created listener and the VFS save listener are application-
  wide and had no project filter, so saving a grammar in one project repainted another
  project's preview. Both now ignore other projects' editors/files.
- Preview input document listener is scoped to its editor (`PreviewState.INPUT_LISTENER_SCOPE`,
  disposed on release, project as backstop). For file input it sat on the IDE-wide Document and
  piled up on each grammar switch, reparsing with stale grammars and outliving the project.
- After project close: parse completion and slow-parse callbacks check `projectIsClosed`; the
  reparse `MergingUpdateQueue` is disposed with the project.
- Formatter: any runtime exception from `xl.Formatter` is logged and reported via `onError`
  instead of leaving the request hanging.

## Notes
- Full suite: `./gradlew check buildPlugin` → 96 tests, 0 failures (after the section 4 fixes).
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
1. **Push antlr4-xl to GitHub as `jasonosgood/antlr4-xl`.** Until then CI cannot check out the
   submodule and fails. If the repo is private, CI needs a token for the checkout.
2. **Extract Rule on lexer rules should create a `fragment`.** Extracting `[0-9]+` from `INT`
   currently creates a new token rule `NEW_RULE`, which changes how the input is tokenized.
3. **Linter → inspections with quick fixes.** xl's `Finding(Token, rule, message, List<Edit>)`
   maps to `LocalInspectionTool` + `LocalQuickFix`. Needs `Linter` (a Java 25 compact source
   file) turned into a class with a callable API returning findings for a text, no file I/O.
4. Try Extract Rule, Format and Sort by hand in the sandbox (only covered by tests so far).
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
12. **Compatibility problem**: `structview/ANTLRv4StructureViewFactory.DummyViewTreeElement`
    uses `com.intellij.ide.structureView.impl.common.PsiTreeElementBase`, which the verifier
    can't resolve. `build.gradle` compiles against `bundledModule("intellij.platform.structureView")`
    but `plugin.xml` only depends on `com.intellij.modules.lang`. Check the Structure view on a
    `.g4` file in the sandbox; if it fails, declare the module dependency in `plugin.xml`.
13. **Internal API**: `PluginManager.getPlugin()` in `ANTLRv4PluginController.projectOpened()`
    (Marketplace may reject internal API use). Use `PluginManagerCore.getPlugin` or drop the log line.
14. **51 deprecated API uses**, mostly from 15 below (project component, `VirtualFileAdapter`,
    `EditorMouseAdapter`, `registerToolWindow`, `ServiceManager`); `getComponent(Class)` is
    scheduled for removal. Also `FileTypeFactory`, `DefaultLiveTemplatesProvider`,
    `ReadAction.run/compute`, `IconLoader.getIcon(String)`, `Document.addDocumentListener`
    without a disposable.

### Project lifecycle / state
15. **Convert `ANTLRv4PluginController` from a `ProjectComponent` to a project service that
    implements `Disposable`** (prior art: every current platform plugin; components are
    deprecated). Register all listeners, queues, preview editors and the grammar-load executor
    against it, tool windows via `toolWindow` extension + `ToolWindowFactory`. This removes the
    manual install/uninstall and the null-out-on-close pattern behind the bleed bugs fixed in
    section 4, clears most of 14, and makes the plugin dynamic (no IDE restart to enable or
    disable).
16. `uninstallListeners()`: `project.getMessageBus().connect(project).disconnect()` opens a new
    connection and closes it, so it disconnects nothing. Harmless (the real connection is parented
    to the project); delete it.
17. Fixture tests suppress `ObjectNotDisposedException` for the Tool Output console editor
    (`AntlrFixtureTestCase`, `TestUtils`). That's a real leak; release it on project close (15
    will do it) and drop the suppression.
18. Small: `RefactorUtils.lexerRuleNameID` is a static counter shared by all projects and never
    reset (`T__N` numbering); `ParsingUtils.BAD_*_GRAMMAR` statics could be `final`.

### From code review (not fixed yet)
19. Extract Rule: "replace all" also replaces alternatives of a left-recursive rule, creating
    mutual left recursion ANTLR rejects (`e : e '*' e | ...` with `e '*' e` elsewhere). Exclude
    occurrences that start with a reference to their own rule.
20. Extract Rule: labels inside the selection move into the new rule and break `$x` references
    in actions; selecting a labelled alternative without its `# label` leaves the rule with a
    mix of labelled and unlabelled alternatives. (Selection checks now block the partial cases,
    `x=A`; whole labelled elements still move.)
21. Extract Rule name dialog accepts ANTLR and target keywords (`grammar`, `fragment`, `class`).
    Reuse the suggester's reserved set; use `javax.lang.model.SourceVersion.isKeyword` instead of
    the hand-written Java keyword list.
22. Extract Rule passes no `PsiFile` to `runWriteCommandAction`, so read-only files throw instead
    of offering to make them writable (Sort does pass it).
23. Sort and Extract parse the whole grammar on the EDT (same kind of freeze as commit `50f99b2`).
    Use `ReadAction.nonBlocking` or modal progress; apply on the EDT.
24. Sort replaces the whole document, losing caret, folding and scroll; apply as a diff.
25. Format: with no range feature, Reformat on a selection and reformat-on-save "changed lines
    only" (the platform default) silently do nothing on `.g4`. Format the whole file or notify.
26. `plugin.xml` Sort description and `SortRulesAction` javadoc say lexer rules stay in place;
    `xl.SortRules` moves them after all parser rules (relative order kept).
27. `build.gradle` xl source set includes both `grammar/ANTLR4.g4` and `src/xl/ANTLR4.g4`; with
    `-Xexact-output-dir` both generate into one folder and the last one wins. Include exactly one.
28. Cleanup: `ExtractRuleOccurrences.matchRuns` is quadratic per alternative list;
    `deepestEnclosing` duplicates `Trees.getRootOfSubtreeEnclosingRegion` (and `ExtractRulePlan`
    walks the tree twice for the same token); `RefactorUtils.getCharIndexOfNextRuleStart` is dead.
29. ~~`doc/error2.txt` (stale IDE log) is staged; `doc/error.txt` is untracked. Unstage/delete.~~
    Done: both deleted, never committed.
30. Extract Rule's `update()` can't cheaply check the selection's shape (needs a parse), so the
    action stays enabled and rejects on invoke with a hint. Revisit if 23 adds a cached parse.
