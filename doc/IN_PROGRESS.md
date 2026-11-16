# In progress

Open work on branch `grammar-tools`. Finished items move to [DONE.md](DONE.md).

## Decisions for this work (2026-11-15)
- The preview reloads a grammar **on save only**, not on unsaved edits.
- The preview is essential: the owner edits a grammar and its sample inputs side by side.
  UX ideas come after the plugin is stable.
- Preview input from a file is removed (it never worked); input is typed or pasted.
- Test grammars: grammars-v4 `semver` (MIT; `SemanticVersionLexer.g4` + `SemanticVersionParser.g4`
  with `tokenVocab`, 81 lines, 13 sample inputs). `xml` (BSD) if lexer modes are needed.
  Performance yardstick: `antlr4-xl/test/oracle_normal.g4`, the largest grammar known.
- Upstream later; this may stay a fork.

## Done: greenfield plan for the preview and grammar model
Six steps, each tested, checked in the sandbox and committed on its own (DONE.md, items 36–42):
characterization tests; `GrammarCache`; platform async; immutable `PreviewState`; `PreviewService`
with `PreviewListener.TOPIC`; routine logging at debug. Lasting constraints are in
[DECISIONS.md](DECISIONS.md) ("Keep stable").

## Next: saving and code generation (decided 2026-11-15)
Principle: the plugin never decides when a file is saved, and nothing it does on a save writes
files the user didn't ask for. Saving follows the platform (⌘S; autosave on idle or when the IDE
loses focus, per Settings > Appearance & Behavior > System Settings > Autosave). Running a tool
saves all documents first, as Build and Run do. Code generation belongs to the build.

### A. Save behavior (first)
1. **Switching tabs no longer saves.** Remove the save from
   `GrammarEditorTabListener.selectionChanged`; switching tabs only switches the preview. An
   edit reaches the preview through whichever save the platform makes. Prior art: no platform
   editor saves on a tab switch. Trade-off: edit the lexer, switch to the parser's tab, and the
   preview shows the saved lexer until ⌘S or autosave.
2. **Tools save all documents.** Generate ANTLR Recognizer and Test Rule call
   `saveAllDocuments()`, not only save the current grammar, so an unsaved `tokenVocab` lexer
   isn't left behind. Prior art: Build, Run and VCS actions save all documents first.
3. **Reload on any saved dependency.** The preview reloads when any grammar the previewed
   grammar depends on (`tokenVocab`, imports) changes on disk, not only grammars open in an
   editor (the `isFileOpen` filter in `PreviewService.GrammarSaveListener`). Then `git checkout`
   or an external edit refreshes it too. Prior art: VFS listeners (`VFS_CHANGES`) see every
   save and refresh, whatever caused it.
4. **No `.tokens` files written on save.** Saving a lexer that a previewed grammar uses runs
   `RunANTLROnGrammarFile` (from `PreviewService.grammarSaved`), which writes the lexer's
   `.tokens` file into the project. That is preview plumbing; check whether `GrammarCache` can
   hand the lexer's vocabulary to the parser in memory.
5. **Remove the per-grammar `autoGen` setting** ("Auto-generate parsers upon save"). It is on
   by default, yet saving a combined or parser grammar never generated (same on upstream
   `master`): only a lexer saved while its parser was previewed ran the tool. Generate-on-save
   comes from the build instead (B.2).
6. Record the principle in [DECISIONS.md](DECISIONS.md) with its prior art.

### B. Code generation in the build (larger; after A)
Prior art: the build generates. Gradle's `antlr` plugin (`generateGrammarSource`, before
`compileJava`), Maven's `antlr4-maven-plugin` (`generate-sources`), protobuf-gradle-plugin. In
plain IntelliJ projects the build is JPS, which runs source-generator builders before the
compilers: annotation processing writes generated sources there; Android generated `R.java`
with a JPS builder. Grammar-Kit and JFlex ship no JPS builder: an explicit action, plus Gradle
plugins for real builds.
1. **Gradle or Maven projects: the build generates.** The plugin shouldn't write a second copy
   into `gen/`: duplicate classes, and the plugin's bundled ANTLR tool may differ from the
   project's runtime ("ANTLR Tool version X used for code generation does not match the current
   runtime version Y"). Detect the build's ANTLR plugin and say so, or step aside.
2. **Plain JPS projects: a JPS source-generator builder** (`<compileServer.plugin>`,
   `BuilderService`), so Build, Rebuild and run configurations generate, incrementally.
   Generate-on-save then comes from the platform's *Actions on Save > Build project*. Costs: a
   separate Gradle subproject; it runs in the build process without PSI or IDE services; the
   per-grammar settings (output dir, package, language, listener/visitor) must be readable by
   JPS (a JPS model serializer, or move them to a file JPS reads or the grammar's
   `options {}`); JPS build tests. Check the API against current SDK docs before planning.
3. **Generate ANTLR Recognizer stays** for one-offs, reporting in a notification (see "Remove
   the Tool Output tool window" below).
4. Optionally, the README points to the Gradle and Maven plugins for real builds.

## When touching the code anyway
Not on their own:
- GUI Designer `.form` files (`InputPanel`, `ProfilerPanel`, `ShowAmbigTreesDialog`,
  `ConfigANTLRDialogPanel`) → plain Swing or Kotlin UI DSL, when one of them needs changing.
- Golden-file tests (`ParsingTestCase`, fixture before/after files) where tests are hand-built
  strings today; when adding tests, not as a rewrite.

## Later: preview UX (after the plugin is stable)
- **Lexer grammar on its own.** Previewing `SemanticVersionLexer.g4` shows "Issues with parser
  and/or lexer grammar(s) prevent preview; see ANTLR 'Tool Output' pane", though nothing is
  wrong: the preview needs a parser grammar and there is none (`loadGrammars` returns no parser
  for a lexer grammar). At least say so; better, show the token stream. Prior art: `grun
  -tokens` (TestRig) and ANTLR Lab both show tokens for a lexer alone.
- **Remove the Tool Output tool window.** It shows the ANTLR tool's console output from
  Generate ANTLR Recognizer and grammar-load errors. Grammar errors are already editor
  annotations; what remains is generation results. Prior art: Grammar-Kit's Generate Parser
  Code reports in a notification balloon; build-integrated generators (Gradle `antlr`, Maven
  `antlr4`, protobuf) report in the Build tool window. Likely: a notification (success, or
  errors with a link to the grammar), and nothing else. `ToolOutput` is then deleted.
- **Profile only when the Profiler tab is shown.** Parsing a one-line query on
  `oracle_normal.g4` takes ~330 ms, because the preview parses with exact ambiguity detection
  and profiling on (found in greenfield step 1).
- Owner has more UX ideas for working on a grammar and its samples side by side.

## Tabled
- **2. Extract Rule on lexer rules should create a `fragment`.** Extracting `[0-9]+` from `INT`
  creates a new token rule `NEW_RULE`, which changes how the input is tokenized.
- **5. Canonicalize.** Decide whether the action rewrites the file or shows a diff view.
- **6. Offsets.** ANTLR char streams count code points, the editor counts UTF-16 chars, so
  grammars with characters outside the BMP (emoji) can shift Extract Rule offsets.
  Pre-existing. (The linter integration already converts its offsets.)
- **Grammar settings in `antlr4.properties`.** Replace the Configure ANTLR... dialog, whose
  per-grammar settings (output dir, package, language, listener/visitor, lib dir, encoding)
  live hidden in IDE project state (`ANTLRv4GrammarPropertiesStore`), with an
  `antlr4.properties` file next to the grammars. Prior art: settings in a plain, checked-in
  file every tool reads (`.editorconfig`, `tsconfig.json`, `.clang-format`). Also makes them
  readable by a JPS builder (B.2) and by the command line.

## Before sharing
- antlr4-xl changes live only in the submodule, branch `plugin-linter-api` (`8ffe462`,
  `61fc364`): not in the owner's standalone checkout (which has staged renames in progress)
  and not on GitHub. Push that branch and merge it into xl `main` before others can build.
- The submodule URL points at a private repo.
