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

## Notes
- Full suite: `./gradlew check buildPlugin` → 92 tests, 0 failures (as of this write-up).
- Sandbox: `launch-sandbox.sh` (see the user's Claude memory). Demo grammar used for Extract Rule
  lived in the session scratchpad; recreate from the test cases if needed.
- `gradle.properties` has a local, uncommitted `org.gradle.java.home` line (machine-specific).
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
8. Run the IntelliJ Plugin Verifier against 2026.2.
9. Small: typed exception instead of `IllegalStateException` for xl syntax errors; optional
   `--start` rule for Sort; consider formatting after sorting.
10. Longer term: the plugin and xl each carry an ANTLR meta-grammar (`ANTLRv4Parser.g4`,
    `ANTLR4.g4`); consider converging.
11. Release: change notes in `plugin.xml`, version bump.
