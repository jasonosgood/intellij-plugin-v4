# Decisions

Settled decisions for branch `grammar-tools`; reopen only with a reason. Numbers in
parentheses are item numbers in [DONE.md](DONE.md).

- **Two meta-grammars (10).** The plugin's `ANTLRv4Parser.g4` drives the PSI (34 source files
  depend on its rules and tokens) and has IDE needs (lexer modes for actions, error recovery);
  xl's `ANTLR4.g4` is the owner's rewrite and off-limits without permission. Prior art:
  google-java-format and ktfmt keep their own parsers apart from IntelliJ's PSI; the seam is
  text. The risk is drift, so `xl/MetaGrammarAgreementTest` checks that both parsers accept or
  reject the same grammars (test resources + xl's tests; set `GRAMMARS_V4` to add grammars-v4).
  On grammars-v4 (1,102 files) it found a plugin parser bug, now fixed: lexer alternatives made
  only of commands (`OTHER : -> mode(DEFAULT_MODE) ;`) were syntax errors in the editor. Known
  difference: xl rejects labels in lexer rules (`LexerElementLabel.g4`), as ANTLR does.
  Highlighting is the exception: the editor highlights with xl's lexer (DONE.md item 60), which
  has no modes and so restarts anywhere; the PSI keeps the plugin's lexer. xl's token names are
  then plugin API: `ANTLRv4HighlightingLexerTest` fails if one has no plugin token type.
- **Format after sort: no (9).** Separate commands; reformat-on-save covers it. Prior art:
  IntelliJ's Rearrange Code is separate from Reformat Code.
- **Extract Rule's `update()` doesn't check the selection's shape (30).** Matches the platform
  (Extract Method stays enabled and explains a bad selection).
- **Duplicate unused-rule warnings.** Reported by both the plugin's checks and xl's lint3, on
  purpose: the prior errors and warnings stay until the linter can replace every kind of them.
- **Platform warning "use project as a parent disposable" (32)**, about once per project open,
  no stack trace; comes from the platform, not this plugin. Ignore.
- **Preview input from a file (33)** never worked; removed (radio buttons, file chooser,
  `PreviewState.inputFile`). Input is typed or pasted. This also drops the verifier's
  experimental `BrowseFolderRunnable` use.
- **Skipped text in the Tokens tab (2026-11-17).** `-> skip` leaves no token, so `grun
  -tokens` never shows skipped text; the Tokens tab does, because it is for debugging the
  lexer (a greedy skip rule eating code, significant whitespace vanishing). Invalid input is
  skipped too: on a token recognition error the lexer reports the text from the token's start
  through the character it failed on, drops it and carries on, so it also leaves no token
  (`Lexer.nextToken`, `recover`). Cheap version: input between tokens becomes "(skipped)"
  rows, inferred from the gaps; text a lexer error dropped is told apart by the spans
  `LexerErrorSpans` records, and shown as "(error)".
  On by default, with a "Show skipped text" toggle (prior art: the PSI Viewer's "show
  PsiWhiteSpace"). Not known: which rule skipped. The owner wants that later, from an
  instrumented lexer (IN_PROGRESS.md, "Tokens tab: which rule skipped").
- **Grammar cache keyed by content, not `CachedValuesManager` (37).** `CachedValuesManager`
  computes inside a read action; loading `oracle_normal.g4` takes ~3 s and ANTLR's `Tool` never
  checks for cancellation, so even a non-blocking read action would block write actions (typing)
  for the whole load. `GrammarCache` records the saved text of each file a load read and
  revalidates by comparing texts. Prior art: Bazel and Gradle build caches (content, not
  timestamps); `ExternalAnnotator` (short read-locked collection, lock-free work). Saved text,
  not editor text, because the preview reloads on save only and the `Tool` reads imports from
  disk. Known gap: a load that fails on a missing import isn't retried when the import is
  created, until the grammar changes or is reopened.
- **Grammar loads don't use `ReadAction.nonBlocking`; parses do (38).** Same reason as the
  cache: a non-blocking read action holds the read lock, and the `Tool` can't be cancelled.
  Loads run in order on one pool thread (so an older load never overwrites a newer one) and
  finish through `AppUIExecutor.expireWith`. Parses check for cancellation at every decision
  (`PreviewParser.visitDecisionState`), so they run as non-blocking read actions, coalesced
  per panel. Prior art: the platform's threading guide.
- **Preview input text stays with the panel, not `PreviewService` (39, 41).** The text typed
  for each grammar is UI state and goes with the editor that shows it; the service holds the
  grammar-derived state (`PreviewState`, start rules) and never calls the UI.
- **Saving and code generation (43–47).** The plugin never decides when a file is saved, and
  nothing it does on a save writes files.
  - Saving follows the platform: Save All, and Autosave (idle, or the IDE losing focus) as the
    user set it. Switching tabs doesn't save. Prior art: no platform editor saves on a tab
    switch.
  - Running a tool on a grammar (Generate ANTLR Recognizer, Test Rule) saves all documents
    first, so the tool sees what the editors show, `tokenVocab` lexers and imports included.
    Prior art: Build and Run save all documents first.
  - Code generation happens only on request (Generate) or in the build, never on a save. A
    `.tokens` file is generated output, written only into the generated sources directory.
    Prior art: Gradle's `antlr` and Maven's `antlr4` plugins generate in the build, into a
    generated-sources directory; Grammar-Kit generates from an explicit action.
  - Nothing reads generated files to serve the editor. The preview and the editor's checks
    import a `tokenVocab` lexer's tokens from the lexer grammar itself
    (`Grammar.importVocab`). Prior art: IntelliJ's editor checks work from PSI and documents,
    never from build output.
  - The preview reacts to every change of a grammar it uses, open in an editor or not,
    whatever caused it (a save, a VCS checkout, another editor; creation, deletion, move,
    rename), through the VFS listener, filtered by the inputs its cached loads recorded. That
    filter also keeps other projects' changes out. Prior art: VFS listeners see every change.
  - On purpose, the preview sees saved text (it reloads on save only) while the editor's checks
    see the editor's text, unsaved edits to a `tokenVocab` lexer included. Each follows its own
    prior art: a run uses saved files; editor checks see what the user sees.
  - The removed "Auto-generate parsers upon save" setting still loads from old projects: the
    XML serializer skips unknown properties (see "Keep stable").
- **Code generation in the JPS build (48–52).** In plain IntelliJ projects a JPS
  source-generator builder runs ANTLR before the Java compiler. Full design, API checks and
  spike results: [B2_JPS_BUILDER.md](B2_JPS_BUILDER.md). Prior art: Gradle's `antlr` plugin,
  `antlr4-maven-plugin`, IntelliJ's own JPS builders (Groovy stubs, GUI forms).
  - On by default. A grammar is built when it is under a source root of its module, its
    output directory is a source root of the same module, and the module isn't imported from
    Gradle or Maven (that build generates; a second copy means duplicate classes).
  - The plugin never changes a module's roots unasked. When the output directory isn't a
    source root it generates nothing and offers to mark it as a generated sources root. Prior
    art: annotation processing's generated sources directory.
  - One rule for every target language: output goes only to a source root, also for targets
    the IDE doesn't compile.
  - When any grammar of a module is dirty, all the module's grammars are regenerated
    (`tokenVocab`, imports). Prior art: Gradle's task-level answer. Maven's dependency graph
    is the finer one, deferred until a module is slow (yardstick: `oracle_normal.g4`).
  - Outputs are keyed by their grammar in JPS's source-to-output mapping; the plugin keeps no
    state, no clock and no files of its own.
  - Settings stay in the existing project state (`ANTLRGenerationPreferences`, see "Keep
    stable"), read in the build process by a second reader guarded by a round-trip test.
    Deviation from prior art (settings in a checked-in build file) is the tabled
    `antlr4.properties` item's argument.
  - One generation core (Java 11, no IDE classes) serves the Generate action and the builder,
    so they can't disagree about where files go.
  - ANTLR errors are build errors and stop the module's build. The builder generates with the
    bundled ANTLR and warns when the module's runtime differs in major.minor.
  - Builder tests run the standalone JPS build (`jps-build-standalone`); `JpsBuildTestCase`
    isn't published for current IDEs. Its version is the IDE's build number, a second place to
    change with `ideaVersion`.
  - Known gap: a settings change alone doesn't regenerate; Rebuild Project does.
  - Generate ANTLR Recognizer agrees with the builder: in a Gradle or Maven module it steps
    aside, offering "Generate anyway" since the build tool is known but not whether it applies
    an ANTLR plugin (54). It reports in a notification, not a console. Prior art: Grammar-Kit.
- **Keep stable.** Persisted settings: `ANTLRv4GrammarPropertiesComponent` (`@State
  "ANTLRGenerationPreferences"`) stores per-grammar settings keyed by path; users' stored configs
  must still load, and changing the key needs a migration. Dynamic plugin loading: the verifier
  says the plugin loads and unloads without restart; keep it so (no static state, everything
  registered against a disposable). The plugin ID `org.antlr.intellij.plugin` and action IDs
  (keymaps refer to them).
- **No CI.** Work is local and experimental; `.github/` is left alone.
