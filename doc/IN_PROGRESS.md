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
- **Switching tabs saves the grammar.** Switching away from an edited `.g4` tab saves it
  (`GrammarEditorTabListener.selectionChanged`), so an unsaved lexer edit reaches the parser's
  preview on the switch. Pre-existing; surprised the owner, who hadn't saved. Decide whether
  the switch should save, or the preview should wait for an explicit save (then say so in the
  preview).
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

## Before sharing
- antlr4-xl changes live only in the submodule, branch `plugin-linter-api` (`8ffe462`,
  `61fc364`): not in the owner's standalone checkout (which has staged renames in progress)
  and not on GitHub. Push that branch and merge it into xl `main` before others can build.
- The submodule URL points at a private repo.
