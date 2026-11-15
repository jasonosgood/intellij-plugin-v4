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

## Next: greenfield plan for the preview and grammar model
Goal: the shape a new plugin would have, reached in small steps, each with tests and a sandbox
check, each committed on its own. Order matters: 1 makes the rest safe; 2 and 3 remove the most
hand-written threading code; 4–6 are cleanup on top.

### Step 1. Characterization tests for the preview: done (see DONE.md)
Found while writing them; decide during steps 2–4 whether to keep each behavior:
- Deleting the selected start rule and saving clears the start rule ("no start rule") instead
  of falling back to the first rule: `grammarFileSaved` runs `autoSetStartRule` before
  `ensureStartRuleExists`. Likely a bug.
- A grammar switched away from keeps its hidden input editor until it is switched back to
  (then the editor is recreated) or closed. Step 4 should keep one editor per visible grammar
  and keep only the text per grammar.
- Parsing a one-line query on `oracle_normal.g4` takes ~330 ms, because the preview parses
  with exact ambiguity detection and profiling on. Consider profiling only when the Profiler
  tab is shown.

### Step 2. Grammars as cached values of their files: done (see DONE.md, item 37)
`GrammarCache` keyed by saved text instead of `CachedValuesManager`; see DECISIONS.md.

### Step 3. Platform async in place of the hand-rolled loader: done (see DONE.md, item 38)
Parses use `ReadAction.nonBlocking`; loads don't (they can't be cancelled); see DECISIONS.md.

### Step 4. Immutable preview state, one owner: done (see DONE.md, item 39)
`PreviewState` is a record replaced on the EDT; `InputPanel` owns one input editor and the text
per grammar. Resolves the step 1 findings on the deleted start rule and hidden editors.

### Step 5. Split the controller; UI subscribes to a topic: done (see DONE.md, item 41)
`PreviewService` + `PreviewListener.TOPIC`, loading in `GrammarCache`, `ToolOutput` a service;
the controller and startup activity are gone.

### Step 6. Smaller cleanups
- Logging: routine events at debug, not info.
- GUI Designer `.form` files (`InputPanel`, `ProfilerPanel`, `ShowAmbigTreesDialog`,
  `ConfigANTLRDialogPanel`) → plain Swing or Kotlin UI DSL, when one of them needs changing
  anyway. Not on its own.
- Golden-file tests (`ParsingTestCase`, fixture before/after files) where tests are hand-built
  strings today; when adding tests, not as a rewrite.

### Keep stable while doing this
- Persisted settings: `ANTLRv4GrammarPropertiesComponent` (`@State
  "ANTLRGenerationPreferences"`) stores per-grammar settings keyed by path. Users' stored
  configs must still load; changing the key needs a migration.
- Dynamic plugin loading: the verifier says the plugin can load and unload without restart.
  Keep it so: no static state, everything registered against a disposable.
- The plugin ID `org.antlr.intellij.plugin` and action IDs (keymaps refer to them).

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
  (`editorSelectionChanged`), so an unsaved lexer edit reaches the parser's preview on the
  switch. Pre-existing; surprised the owner, who hadn't saved. Decide whether the switch should
  save, or the preview should wait for an explicit save (then say so in the preview).
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
