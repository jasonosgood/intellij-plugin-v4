# Plugin architecture

## Build

Gradle is used to build the project. The [IntelliJ Platform Gradle Plugin][1] (2.x) is used
to pull any given version of IntelliJ to add its JARs to the classpath, build forms,
publish to the marketplace etc.

The `antlr4-xl` git submodule supplies the grammar tools (linter, formatter, rule sorter).
`build.gradle` generates its meta-grammar parser (package `xl`) and compiles the tool classes
it needs into the plugin, as a separate source set.

GitHub Actions are used for continuous integration, see files in `.github/workflows`.

## Lexing/parsing

An ANTLR v4 grammar in `src/main/antlr` is used to generate a lexer/parser. 
`ANTLRv4ParserDefinition` uses the [`antlr4-intellij-adaptor` library][2] to 
delegate parsing/lexing to these generated classes.

Syntax highlighting is also using the generated lexer (by delegation) in
`org.antlr.intellij.plugin.ANTLRv4SyntaxHighlighter.getHighlightingLexer`.

## Error checking

The plugin embeds a complete version of ANTLR v4. To highlight warnings in the
editor, the file is parsed again in `ANTLRv4ExternalAnnotator`, but this time
using the "official" parser. The annotator constructs a `org.antlr.v4.Tool` 
with all the flags configured in the `Configure ANTLR...` dialog. 
`GrammarIssuesCollector` sets up an error listener, then processes all the issues
reported by the `Tool` to show them in the editor.

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
it, and the console, when the tool window is first shown; each is disposed with
its tool window content and owns its editors. `ANTLRv4PluginController` (a project
service) holds only model state: per-grammar preview state (`PreviewState`, an
immutable snapshot of grammars, start rule and parse result, replaced on the EDT), keyed
by `VirtualFile`, and tool output (`ToolOutput`, buffered until the console exists). Loaded grammars
come from `GrammarCache` (a project service): an entry per grammar file stays valid
while the saved text of every file its load read (the grammar, its lexer, its
imports) is unchanged, so a save reloads exactly the open grammars it affects.
Loads run off the EDT, without a read lock (see the class comment). Everytime the
editor switches to another .g4 grammar, the preview is updated accordingly:
the input editor is replaced (`InputPanel` keeps one, and the input text per grammar),
the input text and selected rule are restored to their previous state (if any),
and the interpreter results are updated. Interpreter results include profiling
data, a list of tokens and a tree of matched rules and terminal nodes (both
in graphical form and in JTree form).

`PreviewPanel` acts as an orchestrator between inputs (grammar changed event,
input text changed event) and outputs (profiler, token list, parse tree etc.).

Since the preview panel has full control over the interpreter, it can monitor
the parsing phase to detect potential infinite loops caused by bad grammars,
thus preventing IDE freezes or OutOfMemoryExceptions.

[1]: https://github.com/JetBrains/intellij-platform-gradle-plugin
[2]: https://github.com/antlr/antlr4-intellij-adaptor/
[3]: https://github.com/antlr/antlr4/blob/master/doc/interpreters.md
