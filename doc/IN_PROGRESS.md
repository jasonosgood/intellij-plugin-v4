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

### A. Save behavior: done
DONE.md items 43–47; the rule is in [DECISIONS.md](DECISIONS.md) ("Saving and code
generation"). Left open:
- **Stray `.tokens` beside a grammar.** ANTLR looks for a `tokenVocab` `.tokens` in `-lib`
  (default: the grammar's directory) before the output directory, so a stray one (from an
  older plugin or a hand-run `antlr4`) shadows the generated one when Generate runs. Consider
  a warning from Generate when it finds one.

### B. Code generation in the build (larger; after A)
Prior art: the build generates. Gradle's `antlr` plugin (`generateGrammarSource`, before
`compileJava`), Maven's `antlr4-maven-plugin` (`generate-sources`), protobuf-gradle-plugin. In
plain IntelliJ projects the build is JPS, which runs source-generator builders before the
compilers: annotation processing writes generated sources there; Android generated `R.java`
with a JPS builder. Grammar-Kit and JFlex ship no JPS builder: an explicit action, plus Gradle
plugins for real builds.
1. **Gradle or Maven projects: the build generates. Done** (DONE.md item 54). Not yet checked
   in the sandbox: Generate in a Gradle or Maven module should say "not generated" and offer
   "Generate anyway" (tabled by the owner, 2026-11-16).
2. **Plain JPS projects: a JPS source-generator builder. Done** (DONE.md items 48–52; design
   in [B2_JPS_BUILDER.md](B2_JPS_BUILDER.md); the rules in [DECISIONS.md](DECISIONS.md), "Code
   generation in the JPS build"). Build, Rebuild and run configurations generate,
   incrementally; generate-on-save comes from the platform's *Actions on Save > Build
   project*. Left open:
   - **A settings change alone doesn't regenerate.** Changing a grammar's package or output
     directory doesn't make the grammar dirty; Rebuild Project fixes it. A fix would store
     the arguments with the outputs and compare. Prior art: javac option changes force a
     rebuild in JPS.
3. **Generate ANTLR Recognizer stays** for one-offs, reporting in a notification. **Done**
   (DONE.md item 54).
4. **README points to the Gradle and Maven plugins. Done** (DONE.md item 54).
5. **"Cannot find tokens file" in a `tokenVocab` parser. Done** (DONE.md item 53).

## When touching the code anyway
Not on their own:
- GUI Designer `.form` files (`InputPanel`, `ProfilerPanel`, `ShowAmbigTreesDialog`,
  `ConfigANTLRDialogPanel`) → plain Swing or Kotlin UI DSL, when one of them needs changing.
- Golden-file tests (`ParsingTestCase`, fixture before/after files) where tests are hand-built
  strings today; when adding tests, not as a rewrite.

## Later: preview UX (after the plugin is stable)
- **Reload the plugin in a running IDE** (owner, 2026-11-16: tweak the plugin while using it
  for real work). The plugin is dynamic (the verifier says so), but rebuilding under a running
  sandbox half-reloads it: stale classes, "Cannot find suitable constructor". That is a failed
  unload: something still holds a plugin class. Find what (the unload report in `idea.log`; the
  platform can dump a heap snapshot when an unload fails), fix it, then let
  `launch-sandbox.sh` rebuild without `--restart`. Prior art: the platform's own dynamic
  plugin reload, which `runIde` uses for auto-reload.
- **Lexer grammar on its own.** Previewing `SemanticVersionLexer.g4` shows "Issues with parser
  and/or lexer grammar(s) prevent preview; see ANTLR 'Tool Output' pane", though nothing is
  wrong: the preview needs a parser grammar and there is none (`loadGrammars` returns no parser
  for a lexer grammar). At least say so; better, show the token stream. Prior art: `grun
  -tokens` (TestRig) and ANTLR Lab both show tokens for a lexer alone.
- **Remove the Tool Output tool window.** Generate reports in a notification now (DONE.md
  item 54); the window is left with the preview's grammar-load errors (`ParsingUtils`), which
  are also editor annotations. Decide whether the preview needs them elsewhere, then delete
  `ToolOutput`. Prior art: build-integrated generators report in the Build tool window.
- **Profile only when the Profiler tab is shown.** Parsing a one-line query on
  `oracle_normal.g4` takes ~330 ms, because the preview parses with exact ambiguity detection
  and profiling on (found in greenfield step 1).
- **Show that the preview is working.** Loading or parsing a large grammar (`refactorme.g4`,
  `oracle_normal.g4`) takes seconds with no sign of progress, so the plugin looks hung. Prior
  art: the platform's `JBLoadingPanel` (a spinner and "Loading…" over a panel, as in the VCS
  log and diff viewers) and `AsyncProcessIcon` for a small busy marker. Today a parse running
  past `SLOW_PARSE_NOTICE_DELAY_MS` only enables the Cancel Parse button (`parseSlow`); a
  grammar load shows nothing.
- Owner has more UX ideas for working on a grammar and its samples side by side.
- **Sample IntelliJ + ANTLR4 project** (owner, 2026-11-16). A small project to open in the
  sandbox: a grammar, sample inputs, and Java code that uses the generated parser, built by
  the JPS builder (B). Doubles as a manual test bed and a demo for users. Prior art: the
  ANTLR `antlr4-examples` repo and the platform's sample plugins.

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
