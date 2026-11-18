# Plugin architecture

## Build

Gradle is used to build the project. The [IntelliJ Platform Gradle Plugin][1] (2.x) is used
to pull any given version of IntelliJ to add its JARs to the classpath, build forms,
publish to the marketplace etc.

The `antlr4-xl` git submodule supplies the grammar tools (linter, formatter, rule sorter).
`build.gradle` generates its meta-grammar parser (package `xl`) and compiles the tool classes
it needs into the plugin, as a separate source set.

The `jps-plugin` subproject holds the code that also runs in IntelliJ's build process (see
"Code generation"). It is compiled for Java 11 with no IDE classes and ships as
`lib/antlr-jps.jar`.

GitHub Actions are used for continuous integration, see files in `.github/workflows`.

## Lexing/parsing

An ANTLR v4 grammar in `src/main/antlr` is used to generate a lexer/parser. 
`ANTLRv4ParserDefinition` uses the [`antlr4-intellij-adaptor` library][2] to 
delegate parsing/lexing to these generated classes.

Syntax highlighting uses another lexer: antlr4-xl's `ANTLR4Lexer`, through
`ANTLRv4HighlightingLexer`, which reports its tokens as the plugin's token types (so the
highlighter, the brace matcher and the comment tokens work on them unchanged). The editor
restarts its highlighting lexer mid-file after every edit; xl's lexer has no modes or state, so
it restarts anywhere, where the plugin's lexer (modes, fields) didn't restart correctly.
Actions, predicates and `[...]` are target code, one uncolored token each.

## Error checking

The plugin embeds a complete version of ANTLR v4. To highlight warnings in the
editor, the file is parsed again in `ANTLRv4ExternalAnnotator`, but this time
using the "official" parser. The annotator constructs a `org.antlr.v4.Tool` 
with all the flags configured in the `Configure ANTLR...` dialog. 
`GrammarIssuesCollector` sets up an error listener, then processes all the issues
reported by the `Tool` to show them in the editor.

## Code generation

Recognizers are generated in two places, by one core, so both put the same files in the same
directories. The design and its prior art are in [doc/B2_JPS_BUILDER.md](doc/B2_JPS_BUILDER.md).

- The core is package `org.antlr.intellij.plugin.generation` in `jps-plugin`: settings lookup
  (`SettingsLookup`: exact path, wildcard, the project's `*` entry, defaults), argument
  building (`GenerationArgs`), and the tool run (`AntlrGenerator`, a `Tool` subclass that
  records each file it writes and returns messages as data).
- The Generate ANTLR Recognizer action (`RunANTLROnGrammarFile`) calls the core from the IDE.
- In plain IntelliJ (JPS) projects, `jps/AntlrSourceGeneratorBuilder`, a `ModuleLevelBuilder`
  in category `SOURCE_GENERATOR`, calls it from the build process before the Java compiler.
  It is registered through `META-INF/antlr-jps.xml` (`compileServer.plugin`, loaded only with
  the Java plugin) and found through `META-INF/services`. It registers each generated file as
  an output of its grammar, so JPS does the change detection and deletes stale output; the
  plugin stores nothing.
- The build process has no PSI and no project services. The per-grammar settings reach it
  through `jps/AntlrModelSerializerExtension`, which reads the same `.idea/misc.xml` state
  the IDE writes; `SettingsReadByTheBuildTest` keeps the two readers in agreement.
- The builder steps aside for modules imported from Gradle or Maven (`jps/ExternalBuild`) and
  for grammars whose output directory isn't a source root. For the latter it sends a custom
  builder message (`generation/BuildMessages`), and `build/OutputRootOffer` in the IDE offers
  to mark the directory as a generated source root.
- Builder tests run real builds in the test JVM with the standalone JPS build
  (`AntlrSourceGeneratorBuilderTest`). To debug in the sandbox, read
  `log/build-log/build-log.log` or use "Debug Build Process".

## Grammar tools (antlr4-xl)

antlr4-xl parses grammars with its own meta-grammar, separate from the plugin's editor grammar
in `src/main/antlr`. Both are kept; `MetaGrammarAgreementTest` checks that the two parsers
accept and reject the same grammars (set `GRAMMARS_V4` to a grammars-v4 checkout to include it).

- Lint: `lint/GrammarLintAnnotator` (an `ExternalAnnotator`) runs `xl.Linter` on the whole file
  off the read lock and shows each finding, with its fix if it has one.
  `lint/GrammarLintInspection` pairs with it for on/off, severity and Code › Inspect Code (the
  same pairing as IntelliJ's ESLint integration). A fix can expose new findings, so every fix
  re-runs the linter, and Fix All applies one fix at a time, re-linting after each.
- Format: `format/GrammarFormattingService` (an `AsyncDocumentFormattingService`) runs
  `xl.Formatter` and applies the result as a line diff (`format/TextDiffs`); a range keeps only
  the changes inside it.
- Sort: `actions/SortRulesAction` runs `xl.SortRules` in the background and applies the result
  as a diff, keeping the preview's start rule first.

Grammars with syntax errors are not linted, formatted or sorted (`xl.GrammarSyntaxException`).

## Preview window

Contrary to ANTLRWorks, the IntelliJ plugin does not run the actual generated
parser to test a grammar. Instead, it uses [ANTLR interpreters][3]. This is
mainly because the IDE classpath and the project classpath are totally different,
meaning generated parsers and their custom code and dependencies are not
available from the IDE process. 

The interpreter provides a convenient way to "run" grammars without needing 
any generated code. A major drawback of this approach is that custom code will
not be executed: `@members`, actions, predicates...

The entry point for the preview window is `PreviewPanel`, which holds the main
Swing component displayed in the tool window. `ANTLRv4ToolWindowFactory` creates
it when the tool window is first shown; it is disposed with its tool window
content and owns its editors. The model is in two project services, which never
call the UI:
- `PreviewService` holds per-grammar preview state (`PreviewState`, an immutable
  snapshot of grammars, start rule, parse result and token stream, replaced on the EDT), keyed by
  `VirtualFile`, and runs parses. It publishes what happens on the project message
  bus (`PreviewListener.TOPIC`); `PreviewPanel` subscribes with its own disposable.
- `GrammarCache` loads grammars, in order on one background thread without a read lock,
  and caches them: an entry per grammar file stays valid while the saved text of
  every file its load read (the grammar, its lexer, its imports) is unchanged, so a
  save reloads exactly the open grammars it affects.
- **Paused.** The preview doesn't load or parse a grammar with errors: errors in the editor in
  the grammar or a file its last load read (the platform's problem-file tracking,
  `WolfTheProblemSolver`, which grammars opt into with `GrammarProblemFileFilter`), or a load
  that failed anyway (`GrammarCache.Loaded.problem`, a `LoadProblem`). `PreviewService.getPause`
  says why; `pauseChanged` reports errors appearing or going. `PreviewPanel` shows an Error
  banner naming the file over the last results, dimmed. ANTLR's own messages are the editor's
  and the build's to show; the preview's load logs them at debug.
- **Out of date.** The preview loads saved text only, so unsaved changes in the grammar or a
  file its last load read leave it out of date. `PreviewService.getUnsavedInput` names the
  first such file (`FileDocumentManager.isFileModified`); `unsavedChanged` reports edits,
  saves and reverts of those files. `PreviewPanel` shows an Info banner with a Save link over
  the results, which stay as they are. A pause hides it.
- **Busy.** `PreviewService.isLoading()`/`isParsing()` say whether a grammar load or a parse
  is running or waiting; `busyChanged` reports each change. `PreviewPanel` shows them as a
  `JBLoadingPanel` spinner over the result tabs, after a 300 ms delay.

Editor tab events arrive through `GrammarEditorTabListener` (declared in plugin.xml),
which turns them into `PreviewService` calls. Everytime the
editor switches to another .g4 grammar, the preview is updated accordingly:
the input editor is replaced (`InputPanel` keeps one, and the input text per grammar),
the input text and selected rule are restored to their previous state (if any),
and the interpreter results are updated. Interpreter results include profiling
data, a list of tokens and a tree of matched rules and terminal nodes (both
in graphical form and in JTree form).

`PreviewPanel` acts as an orchestrator between inputs (grammar changed event,
input text changed event) and outputs (profiler, token list, parse tree etc.).

### Tokens tab and lexer grammars alone

- **Two results.** `PreviewState.parsingResult` (a `ParsingResult`) always has a parser: the
  profiler, the ambiguity dialog and the input hovers rely on that. The token stream is a
  separate `LexingResult` beside it, set by every parse, so a lexer grammar alone can have
  tokens without a parse. Add new kinds of result beside the others rather than making a
  field of an existing one nullable.
- **Telling a lexer grammar alone apart.** `ParsingUtils.loadGrammars` returns `{lexer,
  null}` for a lexer grammar and `null` (both grammars null in the state) when a grammar
  fails to load; a parser whose lexer is missing gets `BAD_LEXER_GRAMMAR`. Use
  `PreviewState.isLexerOnly()`, not `parserGrammar()==null`. A lexer grammar has no start
  rule, so code that checks `startRule()!=null` to decide whether to parse must also accept
  `isLexerOnly()`; `PreviewService.parse` then tokenizes with `ParsingUtils.lexText` instead.
- **The whole stream.** A parser reads tokens only as far as it gets; after the parse,
  `LexingResult.of` fills the stream to EOF, so the tab shows every token. That also lexes
  (and reports errors in) input past where the parser stopped.
- **Text that left no token.** `-> skip` drops a match inside the lexer; so does a token
  recognition error, which reports the text and carries on. Both leave a gap between
  tokens' character indexes. `TokenRows` turns gaps into "(skipped)" rows, and the spans
  recorded by `LexerErrorSpans` (an error listener every preview lexer gets in
  `ParsingUtils.createLexer`) into "(error)" rows. Which rule skipped isn't known: the lexer
  forgets the type once the action sets it to `SKIP` (doc/IN_PROGRESS.md has the plan).
- **ANTLR 4.13.2 channel names.** `Grammar.createLexerInterpreter` gives the interpreter
  `DEFAULT_TOKEN_CHANNEL`, `HIDDEN`, then `channelValueToNameList`, which is already indexed
  by channel number, so `getChannelNames()` puts user channels two places late. Read channel
  names from the grammar (`LexingResult.channelNames`).
- **Offsets.** Token indexes count code points; the editor counts UTF-16 chars. Selecting
  a token (or a tree node) in the input is off after a character outside the BMP (tabled in
  doc/IN_PROGRESS.md, "Offsets").

Since the preview panel has full control over the interpreter, it can monitor
the parsing phase to detect potential infinite loops caused by bad grammars,
thus preventing IDE freezes or OutOfMemoryExceptions.

Preview parsers of one loaded grammar share its prediction cache (`ParserDfaCache`: ATN,
DFA, `PredictionContextCache`), as instances of a generated parser share static ones. Without
it every parse starts cold, which on a large grammar is most of the parse time. Preview lexers
do the same (`LexerDfaCache`), which also saves ANTLR's ATN round trip on every lex. Every preview
parse profiles with exact ambiguity detection; measured, that costs little.

[1]: https://github.com/JetBrains/intellij-platform-gradle-plugin
[2]: https://github.com/antlr/antlr4-intellij-adaptor/
[3]: https://github.com/antlr/antlr4/blob/master/doc/interpreters.md
