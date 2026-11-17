# IntelliJ Idea Plugin for ANTLR v4 ![Java CI](https://github.com/antlr/intellij-plugin-v4/workflows/Java%20CI/badge.svg?branch=master) [![Latest version](https://img.shields.io/jetbrains/plugin/v/7358.svg?label=latest%20version)](https://plugins.jetbrains.com/plugin/7358) ![Downloads](https://img.shields.io/jetbrains/plugin/d/7358.svg)

An [IntelliJ](https://www.jetbrains.com/idea/) 2026.2+ plugin for ANTLR v4 ([plugin source at github](https://github.com/antlr/intellij-plugin-v4)).

[Plugin page at JetBrains Marketplace](http://plugins.jetbrains.com/plugin/7358?pr=idea)

This plugin is for ANTLR v4 grammars and includes ANTLR 4.13.2. Works with 
IntelliJ IDEA and other IntelliJ-based IDEs.

## Features:

- syntax highlighting
- syntax error checking
- semantic error checking
- navigation window
- goto-declaration
- find usages
- rename tokens
- rename rules
- save parse trees as svg/jpg/png; right click in parse tree view (1.9)
- comment grammar rule lines with meta-/ (1.7)
- generates recognizers in the build: in plain IntelliJ projects, Build, Rebuild and run
  configurations run ANTLR on changed grammars before compiling (see "Generating code")
- generates code on request; shortcut (ctrl-shift-G / meta-shift-G) but it's in Tools menu and popups.
- code completion for tokens, rule names;
- finds tokenVocab option for code gen if there is a tokenVocab option, don't warn about implicit tokens.
- handles separate parsers and lexers like TParser.g4 and TLexer.g4 (1.7)
- Parse tree nodes show the alternative number the parser chose to match that node. (1.7)
- has live grammar interpreter for grammar preview. Right click on rule and say "Test ANTLR Rule".
- can view parse trees for input matched in more than one way (ambiguities) (1.7)
- can view lookahead trees to show how input directed ANTLR to match a particular alternative (1.7)
- changes to grammar seen in parse tree upon save of grammar.
- refactoring: extract rule (optionally replacing every occurrence), inline rule
- grammar lint (from [antlr4-xl](https://github.com/jasonosgood/antlr4-xl)): warnings for unused rules,
  duplicate alternatives, unnecessary parentheses, nested suffixes and more, with quick fixes
  and "Fix all grammar lint findings in file". On by default; Settings › Editor › Inspections ›
  ANTLR › "Grammar lint (antlr4-xl)".
- Code › Reformat Code (whole file or selection) and Code › Sort Parser Rules, both from antlr4-xl.

You can configure the ANTLR tool options per grammar file; right-click
in a grammar or on a grammar element within the structured view.
Saving a grammar doesn't run ANTLR; the build does, or you do with
Generate ANTLR Recognizer (see "Generating code").

You can use the ctrl-key while moving the mouse and it will show you
token information in the preview editor box via tooltips. Meta-key shows parser call stack.

Errors within the preview editor are now highlighted with tooltips
and underlining just like a regular editor window. The difference
is that this window's grammar is specified in your grammar file.

ctrl-return, or whatever you have configured for the generate pop-up,
will bring up a list of things you can generate. The only one so far is
a generator to create lexical rules for any literals, referenced in the parser
 grammar, that have not been defined.

## Generating code

**In the build.** In a plain IntelliJ project (one not imported from Gradle or Maven), Build,
Rebuild and run configurations generate recognizers before the compiler runs:

- A grammar is built when it is under a source root of its module and its output directory
  (`gen` under the module's content root by default; set it with Configure ANTLR...) is a
  source root of the same module.
- If the output directory isn't a source root, nothing is generated and a notification offers
  "Mark as generated sources root". Accept it once and later builds generate there.
- When a grammar of a module changes, all of that module's grammars are regenerated, so a
  parser follows its `tokenVocab` lexer and its imports. Deleting the output directory is
  safe: the next build regenerates it.
- ANTLR's errors and warnings appear in the Build tool window with file, line and column.
- Any target language is generated; only Java output is compiled.
- A warning appears when the module's `antlr4-runtime` differs from the plugin's ANTLR
  (4.13.2) in major or minor version.
- After changing a grammar's settings (package, output directory, language) without changing
  the grammar, use Build > Rebuild Project; a plain Build doesn't notice.
- To generate on save, turn on Settings > Tools > Actions on Save > Build project.

**In Gradle and Maven projects** the plugin generates nothing, neither in the build nor from
Generate ANTLR Recognizer: use Gradle's
[`antlr` plugin](https://docs.gradle.org/current/userguide/antlr_plugin.html) or the
[`antlr4-maven-plugin`](https://www.antlr.org/api/maven-plugin/latest/), so the IDE and the
command line build the same way. A second copy from the plugin would mean duplicate classes,
maybe from another ANTLR version. If your build doesn't generate them, Generate's
notification offers "Generate anyway", and "Don't warn again" to always generate in that
project.

**On request.** Generate ANTLR Recognizer (ctrl-shift-G / meta-shift-G) runs ANTLR on the
current grammar, with the same settings, and reports in the Build tool window, as the build
does: ANTLR's errors and warnings (each one goes to its place in the grammar) and where the
files went. When it fails, the Build window opens and a notification links to the first error;
otherwise the status bar says it's done.

## Limitations

The `ANTLR Preview` window is based on a grammar interpreter, not on the actual generated parser. 
This means that things like actions and predicates will not be evaluated during live preview,
because the interpreter is language agnostic. 

For the same reasons, if your parser and/or lexer classes extend a custom implementation of the 
base parser/lexer classes, your custom code will *not* be run during live preview. 

## History

See [Releases](https://github.com/antlr/intellij-plugin-v4/releases)

## Screenshots

### Java grammar view
![Java grammar view](images/java-grammar.png)

### Find usages
![Find usages](images/findusages.png)

### Code completion
![Code completion](images/completion.png)

### Refactoring: generate rules for literals

It guesses rule names or just uses T__&lt;n>. Respects literals already defined. Use Code::Generate menu item or key equivalent.

![def-literals.png](images/def-literals.png)

### Live parse tree preview

You can test any rule in the (parser) grammar.  Right click on rule in grammar
or navigator to "Test ANTLR Rule".  Changing grammar and saving, updates
parse tree. It works with combined grammars and separated but separated
must be in same directory and named XParser.g4 and XLexer.g4.
No raw Java actions are executed obviously during interpretation in
live preview.

[![Live parse preview](http://img.youtube.com/vi/h60VapD1rOo/0.jpg)](//www.youtube.com/embed/h60VapD1rOo)

![Live preview](images/live-preview.png)
![Live preview](images/live-preview-error.png)

You can also use the ctrl key while moving the mouse in preview window to get token info.

![Live preview](images/token-tooltips.png)

The meta key while moving the mouse shows the parser call stack (path to the root of parse tree).

When there are errors, you will see the output in the small console under the input editor in case you need to cut and paste. But, for general viewing you can however the cursor over an underlined error and it will show you the message in a pop-up. Basically the input window behaves like a regular editor window except that it is subject to the grammar in your other editor.

![error-popup.png](images/error-popup.png)

Errors in the grammar itself (or in its lexer or an imported grammar) pause the preview: it keeps its last results, dimmed, under a banner naming the file with the errors, and picks up again once they are fixed. The errors are marked in the grammar's editor, and grammars with errors are underlined in the Project view.

The preview reloads a grammar when it is saved. While the grammar (or its lexer or an imported grammar) has unsaved changes, a banner says the preview is out of date; its Save link saves them.

With alt-mouse movement, you'll see parse region for rule matching token under cursor. Click and it jumps to grammar definition.

![parse-region.png](images/parse-region.png)

### Token stream

The preview's Tokens tab lists the tokens the lexer made from the input: index, type, text
(with `\n`, `\r` and `\t` escaped), line:column and channel. Tokens on another channel than
the default (`-> channel(HIDDEN)`) are greyed. Text that left no token is listed too, in
italics: `(skipped)` for text a `-> skip` rule dropped, and `(error)` in red for text no rule
matched. Turn off "Show skipped text" to see the tokens alone. Selecting a row selects its
text in the input. Start typing to find a token by its type or text; the Hierarchy tab
searches its rule nodes the same way.

A lexer grammar on its own can be previewed too: open it, type some input, and the preview
shows its tokens (there is no parse tree without a parser).

### Grammar Profiler

The profiler helps you understand which decisions in your grammar are complicated or expensive.  Profiling data is always available just like the parse tree during grammar interpretation.  The profiler cannot track code execution because it is running the grammar interpreter not executing compiled code.  It provides both a simplified set of columns and an expert set the provides a great deal more information. Clicking on a row in the profiler highlights the decision in the grammar and also highlights relevant pieces of the input, such as ambiguities or the deepest lookahead. You can sort the columns by clicking on the header row. Hover over the header row to get tooltips describing the column.

If you see ambiguities highlighted, those you should definitely take a look
 at in your grammar. If you see decisions requiring full context sensitivity,
 when viewing the expert columns, those are very expensive and could be causing
 speed problems. Note that the profiler always tries to keep up-to-date with
 the input. The profiler uses the parser interpreter but is fairly
 consistent with the speed of a generated and compiled parser but it does use
 single-stage full LL parsing which can be slower.  It needs to do that so
 that it gets full and complete profiling information. For those in the know,
 it uses `PredictionMode.LL_EXACT_AMBIG_DETECTION`. For really big files and
 slow grammars, there is an appreciable delay when displaying the parse tree or profiling information.

![parse-region.png](images/profiler.png)

### Grammar ambiguities and lookahead

A grammar that matches an input phrase more than one way is considered ambiguous and it's generally an error because we want to interpret input phrases in just one way. ANTLR resolves ambiguities on-the-fly to create a single interpretation (parse tree) but sometimes, for debugging purposes, you'd like to know how else to grammar can match some input. When you have selected an ambiguity using the profiler described in the previous section, you can right click in the preview input window like so:

![ambig-right-click.png](images/ambig-right-click.png)

It will pop up a dialog box that looks like this:

![ambig1.png](images/ambig1.png)

The darkened background node is the node for the rule containing the decision that is ambiguous.  The yellow boxes outlining nodes indicates that it differs from the chosen interpretation of the parse tree. Here's another example that shows two interpretations (of `User f() {`):

![ambig2.png](images/ambig2.png)

Even when a grammar is getting a single interpretation of the input, we often wonder why it's not choosing the path we expected.  When you right click on a lookahead sequence in the input window, it will pop up a dialog to show you how ANTLR tried to use the input to match the alternative. The trees with gray backgrounds indicate the parse trees that were attempted but that failed at some input depth. The tree with a white background chose the chosen parse tree:

![lookahead1.png](images/lookahead1.png)

### Unicode chars are no problem

![unicode.png](images/unicode.png)

### Per file ANTLR configuration

![Configuration](images/per-file-config.png)

### Color preferences

![Live preview](images/color-prefs.png)

# Contributing

We [use Gradle](https://github.com/antlr/intellij-plugin-v4/pull/295) to build. To contribute to the project, 
you need a recent version of IntelliJ IDEA (either Community or Ultimate) with the `Gradle` and `Plugin DevKit` 
plugins enabled. 

The grammar tools (lint, format, sort) come from the `antlr4-xl` git submodule, which the build
compiles into the plugin, so clone with `--recurse-submodules` or run
`git submodule update --init` first.

Use `File > Open` and select the `build.gradle` file to import the project. Once everything is imported, you can run a 
sandboxed IDE using the `runIde` task, either from the `Gradle` tool window of from the CLI:

```
cd ~/antlr/code/intellij-plugin-v4
./gradlew runIde
```

To launch unit tests, run `./gradlew check`.

To build a zipped version of the plugin and its dependencies, run `./gradlew buildPlugin`.

You can tweak the version of the IntelliJ platform used to build/test the plugin in `gradle.properties`, such as:

```
ideaVersion=IU-2026.2.1
```

Java 25 is required (IntelliJ 2026.2 runs on JBR 25).

A high level description of how the plugin works can be found in `ARCHITECTURE.md`.

# Releasing

* Search for 4.9, 4.10 etc... of ANTLR.
* Update release notes in plugin.xml
* Test with multiple IDE versions
* Run unit tests
* Upload to https://plugins.jetbrains.com/plugin/add#intellij
* Write release note in github
