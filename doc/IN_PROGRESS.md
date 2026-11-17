# In progress

Open work on branch `grammar-tools`. Finished items move to [DONE.md](DONE.md), grouped by the
work they belonged to.

## Decisions for this work (2026-11-15)
- The preview reloads a grammar **on save only**, not on unsaved edits.
- The preview is essential: the owner edits a grammar and its sample inputs side by side.
  UX ideas come after the plugin is stable.
- Preview input from a file is removed (it never worked); input is typed or pasted.
- Test grammars: grammars-v4 `semver` (MIT; `SemanticVersionLexer.g4` + `SemanticVersionParser.g4`
  with `tokenVocab`, 81 lines, 13 sample inputs). `xml` (BSD) if lexer modes are needed.
  Performance yardstick: `antlr4-xl/test/oracle_normal.g4`, the largest grammar known.
- Upstream later; this may stay a fork.

## Left open from saving and code generation
The work is in DONE.md ("Saving and code generation", items 43–56); the rules are in
[DECISIONS.md](DECISIONS.md).
- **Gradle or Maven module: check Generate in the sandbox** (tabled by the owner,
  2026-11-16). It should say "not generated" and offer "Generate anyway" (item 54).
- **"Don't warn again" on that notification** (owner, 2026-11-16), per project. Prior art: the
  "Don't offer again" action on `OutputRootOffer`'s notification, stored in
  `PropertiesComponent`. Decide what Generate does then: generate silently, or still step
  aside without saying so.
- **A settings change alone doesn't regenerate** in the JPS build. Changing a grammar's
  package or output directory doesn't make the grammar dirty; Rebuild Project fixes it. A fix
  would store the arguments with the outputs and compare. Prior art: javac option changes
  force a rebuild in JPS.

## When touching the code anyway
Not on their own:
- GUI Designer `.form` files (`InputPanel`, `ProfilerPanel`, `ShowAmbigTreesDialog`,
  `ConfigANTLRDialogPanel`) → plain Swing or Kotlin UI DSL, when one of them needs changing.
- Golden-file tests (`ParsingTestCase`, fixture before/after files) where tests are hand-built
  strings today; when adding tests, not as a rewrite.

## Later: preview UX (after the plugin is stable)
- **Lexer mode per token** in the Tokens tab (ANTLR Lab shows it; the token doesn't record it).
  The tabled "Tokens tab: which rule skipped" would give it.
- **Lexer DFA per parse.** Parses share the grammar's prediction cache (DONE.md item 58), but
  the lexer's DFA is still built per parse (`createLexerInterpreter`); worth sharing the same
  way if lexing large inputs shows up as slow.
- **The typing debounce shows nothing.** The 500 ms `MergingUpdateQueue` wait before a parse
  starts has no busy indication (the spinner is DONE.md item 59).
- **Highlighting target code in actions and `[...]` (owner, if ever needed).** They are one
  uncolored token each (DONE.md item 60), on purpose. If highlighting them is wanted, try a
  `LayeredLexer` first: the platform's way to re-lex chosen tokens with another lexer (Java's
  highlighter does it for strings and doc comments).
- Owner has more UX ideas for working on a grammar and its samples side by side.
- **Sample IntelliJ + ANTLR4 project** (owner, 2026-11-16). A small project to open in the
  sandbox: a grammar, sample inputs, and Java code that uses the generated parser, built by
  the JPS builder. Doubles as a manual test bed and a demo for users. Prior art: the
  ANTLR `antlr4-examples` repo and the platform's sample plugins.

## Tabled
- **Plugin reload leaks its class loader** (tabled by the owner, 2026-11-16: the platform's
  problem). Rebuilding under a running sandbox now reloads the plugin (DONE.md item 56), but
  the IDE then says "Restart advised: 'ANTLR v4' plugin didn't unload fully": the old class
  loader stays in memory, one copy per reload, until a restart. Found by bisection
  (`tmp/unload-test.sh`: restart, reload once, look for the leak warning): any icon from the
  plugin's jar that was drawn while the IDE started leaks, the tool window `icon=` in
  plugin.xml as well as `IconLoader.getIcon(path, Icons.class)`, even a blank SVG; with no
  icons of ours the reload is clean. A copy loaded by an earlier reload unloads cleanly. The
  platform's snapshot analysis shows a static Caffeine cache keyed by our `CachedImageIcon`;
  `IconLoader.clearCache()` before unload doesn't reach it. Also: with grammar editors open,
  the unload logs SEVERE "Trying to access element type from unloaded plugin" from the
  platform's highlighter and folding updates. If picked up: name the cache and report it on
  YouTrack with this repro.
- **2. Extract Rule on lexer rules should create a `fragment`.** Extracting `[0-9]+` from `INT`
  creates a new token rule `NEW_RULE`, which changes how the input is tokenized.
- **5. Canonicalize.** Decide whether the action rewrites the file or shows a diff view.
- **6. Offsets.** ANTLR char streams count code points, the editor counts UTF-16 chars, so
  grammars with characters outside the BMP (emoji) can shift Extract Rule offsets, and
  input with them shifts the preview's selection from a tree node or a Tokens tab row.
  Pre-existing. (The linter integration already converts its offsets.)
- **Grammar settings in `antlr4.properties`.** Replace the Configure ANTLR... dialog, whose
  per-grammar settings (output dir, package, language, listener/visitor, lib dir, encoding)
  live hidden in IDE project state (`ANTLRv4GrammarPropertiesStore`), with an
  `antlr4.properties` file next to the grammars. Prior art: settings in a plain, checked-in
  file every tool reads (`.editorconfig`, `tsconfig.json`, `.clang-format`). Also makes them
  readable by a JPS builder (B.2) and by the command line.
- **Tokens tab: which rule skipped** (tabled by the owner, 2026-11-17). Gap rows say
  "(skipped)" but not which rule. Instrument the lexer: wrap the `LexerInterpreter` so each
  match whose type becomes `SKIP` is recorded with its rule (the type `Lexer.nextToken` drops)
  and span, and show the rule name in the Type column. Would also give lexer modes, and
  replace the gap inference in `TokenRows`. Prior art to check first: whether ANTLR Lab or the
  ANTLR v4 VS Code extension shows skipped tokens, and how.

## Before sharing
- antlr4-xl changes live only in the submodule, branch `plugin-linter-api` (`8ffe462`,
  `61fc364`): not in the owner's standalone checkout (which has staged renames in progress)
  and not on GitHub. Push that branch and merge it into xl `main` before others can build.
- The submodule URL points at a private repo.
