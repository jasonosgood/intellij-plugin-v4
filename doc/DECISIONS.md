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
- **Keep stable.** Persisted settings: `ANTLRv4GrammarPropertiesComponent` (`@State
  "ANTLRGenerationPreferences"`) stores per-grammar settings keyed by path; users' stored configs
  must still load, and changing the key needs a migration. Dynamic plugin loading: the verifier
  says the plugin loads and unloads without restart; keep it so (no static state, everything
  registered against a disposable). The plugin ID `org.antlr.intellij.plugin` and action IDs
  (keymaps refer to them).
- **No CI.** Work is local and experimental; `.github/` is left alone.
