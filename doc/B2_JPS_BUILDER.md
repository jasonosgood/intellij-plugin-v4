# B.2 design: code generation in the JPS build

Status: done (2026-11-16); all steps are built and the owner checked steps 3 and 4 in the
sandbox. This is the design record; the settled rules are summarized in
[DECISIONS.md](DECISIONS.md) ("Code generation in the JPS build"). Scope is item B.2 of the saving and
code generation work ([DONE.md](DONE.md), items 48–52): in plain IntelliJ (JPS) projects, Build, Rebuild and run
configurations generate recognizers from grammars. Gradle and Maven projects (B.1) and the
Generate action's reporting (B.3) are separate items; where they touch this one it says so.

## The problem and its prior art
This is **build-integrated source generation**: a generator that runs before the compiler,
incrementally, as part of the build.

| System | What it does |
|---|---|
| Gradle `antlr` plugin | `generateGrammarSource` before `compileJava`; grammars in `src/main/antlr`; output in `build/generated-src`; any grammar change reruns the whole task |
| `antlr4-maven-plugin` | `generate-sources` phase; output in `target/generated-sources/antlr4`; keeps a dependency graph (`import`, `tokenVocab`) to regenerate dependents |
| IntelliJ's own JPS builders (Groovy stubs, JavaFX, GUI forms; Android's `R.java` before it moved to Gradle) | a builder in the build process, registered with `compileServer.plugin`; settings read from `.idea` by a model serializer; a common jar shared with the IDE |

What they agree on, and this design copies:
1. Generation is a build step before compilation, never an editor event.
2. Inputs are the grammars under a source root; outputs go to a directory that is a
   (generated) source root, so the compiler picks them up.
3. Settings come from project configuration the build can read without the IDE.
4. Errors go to the build's output, with file, line and column.
5. Outputs are owned by the build: it deletes the ones whose grammar changed or went away.

## What was checked (2026-11-16)
Against the SDK page "External Builder API and Plugins" (dated 2026-11-10; no deprecation
notice) and the 2026.2.1 distribution this project builds with:

- `com.intellij.compileServer.plugin` is declared by the **Java plugin** (module
  `intellij.java.compiler.impl`), `dynamic="true"`. Bundled plugins register it as
  `<compileServer.plugin classpath="groovy-jps.jar;groovy-constants-rt.jar"/>`: jar names
  relative to the plugin's `lib/`, separated by `;`.
- Builders are found through `META-INF/services/org.jetbrains.jps.incremental.BuilderService`;
  settings readers through
  `META-INF/services/org.jetbrains.jps.model.serialization.JpsModelSerializerExtension`.
- The API is in `plugins/java/lib/modules/intellij.platform.jps.build.jar` (`BuilderService`,
  `ModuleLevelBuilder`, `BuilderCategory.SOURCE_GENERATOR`, `FSOperations.markDirty`) and
  `lib/jps-model.jar` (`JpsModelSerializerExtension`, `JpsProjectExtensionSerializer`).
- Those jars are compiled for **Java 11** (class file version 55). The build process doesn't
  always run on the IDE's runtime, so builder code must target 11, not this project's 25.
- `ModuleLevelBuilder.build(context, chunk, dirtyFilesHolder, outputConsumer)`;
  `OutputConsumer.registerOutputFile(target, file, sourcePaths)`.
- Not found in the distribution: `JpsBuildTestCase` (the builder test base class). Not covered
  by the IntelliJ Platform Gradle Plugin docs: any JPS-specific dependency helper. Both were
  questions for the spike; see "Spike results".

## Decisions

### 1. Shape: a `ModuleLevelBuilder` in category `SOURCE_GENERATOR`
It takes part in each module's build, sees the dirty `.g4` files under the module's source
roots (`getCompilableFileExtensions()` returns `g4`), and runs before the Java compiler.

Prior art: the SDK doc's answer for a builder that feeds an existing target; a custom
`BuildTarget` is for output the Java targets don't consume. Consequence, as in Gradle and
Maven: **a grammar outside a source root isn't built.** The Generate action still works on it.

### 2. Addressing: outputs are keyed by the grammar that produced them
Each generated file (`.java`, `.tokens`, `.interp`) is registered against its grammar's path in
JPS's source-to-output mapping. JPS then deletes outputs of changed or removed grammars and
knows when nothing changed. The plugin stores nothing of its own.

**Granularity: when any grammar in a module is dirty, regenerate all the module's grammars.**
A parser depends on its `tokenVocab` lexer and on imported grammars, and a per-file build would
miss those. Prior art: this is Gradle's answer (task-level). Maven's dependency graph is the
finer answer; deferred until a module with many large grammars makes this slow (yardstick:
`oracle_normal.g4`). All grammars with the same settings go to one `Tool` run, so ANTLR orders
lexers before the parsers that use them (`Tool.sortGrammarByTokenVocab`).

### 3. Settings: read the existing project state through a JPS model serializer
The builder reads `ANTLRGenerationPreferences` (the `PerGrammarGenerationSettings` list, with
its exact-path, wildcard, `*` and built-in default lookup) from the project files.

- Honors "Keep stable": no change to the persisted format, no migration.
- The tabled `antlr4.properties` item stays possible: the builder asks one small interface for
  a grammar's settings, and the properties file would be a second implementation of it.
- Deviation from prior art, noted: Gradle and Maven keep settings in the build file, which is
  checked in. These live in `.idea`. That is the tabled item's argument, not B.2's.

### 4. One generation core, shared by the action and the builder
Today `RunANTLROnGrammarFile` mixes argument building with PSI, VFS, `PathMacroManager` and
`WildcardFileNameMatcher`. None of those exist in the build process. Extract a pure core, with
no IDE classes, compiled for Java 11:

- settings lookup (path, wildcard, `*`, default);
- argument building (`-o`, `-lib`, `-package`, `-Dlanguage`, `-encoding`, listener/visitor);
- running the `Tool` and returning messages as data (severity, file, line, column, text).

The Generate action and the builder both call it, so they can't disagree about where files go.
Two inputs differ by caller and are passed in: the package of the grammar's directory (IDE:
`ProjectFileIndex`; build: the JPS source root and its package prefix) and path macros
(`$PROJECT_DIR$`). "Does `@header` declare a package" is part of the first: the IDE keeps
answering it from PSI, which sees unsaved text and is already parsed (the editor's checks ask
on every change); the builder will answer it from ANTLR's own grammar AST.

Prior art: `groovy-constants-rt.jar` and `javaFX-common.jar` are exactly this: a jar on both the
IDE's and the build process's classpath.

### 5. When the builder acts
It generates for a grammar only when all hold:
- the grammar is under a source root of the module;
- its resolved output directory is under a source root of the same module;
- the module isn't imported from Gradle or Maven (B.1: that build generates; a second copy
  means duplicate classes and possibly a different ANTLR version).

Otherwise it skips, with one information message per module saying why. So a project where
`gen/` isn't a source root builds as it does today. Generated `.java` files are marked dirty for
the current round so the same build compiles them.

**`gen/` as a generated source root (owner, 2026-11-16).** The default output directory is to
be a *generated* source root, so the compiler takes it and the IDE treats its files as
generated (a warning on editing them; inspections off). When a build skips a grammar because
its output directory isn't a source root, the plugin offers to mark it: a notification with an
action, not a silent change to the module. Prior art: IntelliJ's annotation processing marks
its "generated sources directory" this way.

### 6. Thread ownership
The builder holds no mutable state. JPS may build independent modules in parallel, so each
generation creates its own `Tool`; nothing is cached between runs. The IDE side is unchanged.

### 7. Time and on-disk formats
No clocks and no new files. Change detection is JPS's (stamps and its own mapping); settings
stay in the existing XML.

### 8. Failure
- ANTLR errors become build errors with file, line and column, and the module's build stops
  there, so the compiler never runs on stale generated code. Warnings become build warnings.
- An exception from the `Tool` becomes one build error with the stack trace in the build log.
- Cancellation is checked between grammars. The `Tool` itself can't be interrupted
  (DECISIONS.md, "Grammar loads").
- Version drift: the builder generates with the plugin's bundled ANTLR (4.13.2). If the
  module's classpath has a different `antlr4-runtime`, warn once, in the tool's own words
  ("ANTLR Tool version X used for code generation does not match the current runtime version Y").
- Rebuild deletes registered outputs before regenerating. A project that checks in `gen/` will
  see that as a diff only if the output differs.

### 9. Observability
- Users: the Build tool window.
- Developers: `build-log/build-log.log` under the sandbox's log directory; "Debug Build
  Process" attaches a debugger to the build process.
- Tests and the agent: the core is plain JUnit (settings and arguments in, files and messages
  out). Builder tests on a real JPS build if the spike finds the test framework; otherwise a
  scripted sandbox build of `semver`, checked through the build log.

### 10. Packaging
- A Gradle subproject (the first: a `settings.gradle` is added) holding the core and the
  builder, compiled with `--release 11`, with the JPS jars as `compileOnly`.
- Its jar ships in the plugin's `lib/`. `plugin.xml` gains
  `<depends optional="true" config-file="antlr-jps.xml">com.intellij.java</depends>`, and
  `antlr-jps.xml` holds the `compileServer.plugin` entry. IDEs without the Java plugin are
  unaffected; the plugin stays dynamically loadable (the extension point is dynamic).
- The `classpath` attribute must name the ANTLR tool's jars by file name (`antlr4-4.13.2.jar`,
  `antlr4-runtime-4.13.2.jar`, `ST4-4.3.4.jar`, `antlr-runtime-3.5.3.jar`), which carry
  versions. A test asserts that every name in it exists in the built distribution, so an ANTLR
  upgrade can't silently break the builder.

## Steps
Each tested, checked in the sandbox and committed on its own.

0. **Spike.** `settings.gradle`, the subproject at Java 11 against the JPS jars, a builder that
   only prints a message, packaged and seen in a sandbox Build. Settles the two unknowns: how
   the subproject gets the JPS API with IntelliJ Platform Gradle Plugin 2.x, and whether
   `JpsBuildTestCase` is obtainable. If either fails, stop and rethink before step 1.
1. **Extract the core** from `RunANTLROnGrammarFile`; the Generate action uses it. No behavior
   change; characterization tests first. Built (2026-11-16): package
   `org.antlr.intellij.plugin.generation` in `jps-plugin` (`GenerationSettings`,
   `GrammarPlace`, `GenerationArgs`, `AntlrGenerator`, `ToolMessage`). The Generate action
   now prints the tool's messages when the run ends, not as they come; the run is modal, so
   nothing shows the difference. Options keep a fixed order (they came from a `HashMap`).
2. **Settings serializer**: the builder's process loads `ANTLRGenerationPreferences`. The
   settings lookup (exact path, wildcard, `*`, default) moves to the core here, not in step 1:
   it uses the IDE's `WildcardFileNameMatcher`, which the build process may not have, so the
   move needs its own tests against that matcher. Built (2026-11-16):
   - The build process does have `WildcardFileNameMatcher` (it ships in the JPS model jar), so
     `SettingsLookup` in the core uses that same class for both sides; nothing is reimplemented.
   - The state is in `.idea/misc.xml`. `AntlrModelSerializerExtension` loads it into the JPS
     project as `AntlrProjectSettings`. JPS expands path macros (`$PROJECT_DIR$`) on load, in
     file patterns and directories alike.
   - The builder reads the XML by hand, so the format now has two readers.
     `SettingsReadByTheBuildTest` serializes the IDE's store and checks the builder reads the
     same values, and that both sides have the same defaults.
   - Until step 3 the spike builder's warning also shows each grammar's package and output
     directory, to check the settings in a sandbox build.
3. **The builder**: generate, register outputs, mark generated sources dirty, report messages.
   Built (2026-11-16), tested by real builds in `AntlrSourceGeneratorBuilderTest`:
   - **When it generates:** a grammar of the module changed or was removed, or a generated
     file is gone (so deleting `gen/` by hand heals on the next build). Then all the module's
     grammars are regenerated (decision 2).
   - **Which files belong to which grammar** comes from the tool itself: `AntlrGenerator`
     subclasses `Tool` and notes every file it opens for writing. No guessing from names.
   - **Same-build compilation:** generated `.java` files are marked dirty for the current
     round. This only has an effect when the output directory is a source root (step 4).
   - **`@header` packages** are read with ANTLR's own parser (`GrammarHeader`), with the
     regex the IDE uses.
   - **Errors** carry the grammar's path, line and column, and stop the module's build.
   - **Not handled: a settings change alone.** Changing a grammar's package or output
     directory doesn't make the grammar dirty, so the next Build does nothing and the old
     output stays. Rebuild Project fixes it. A fix would store the arguments with the outputs
     and compare; prior art is javac option changes forcing a rebuild in JPS.
   - **A test trap:** JPS treats a source file newer than the build's start as changed during
     the build and builds it again next time. Tests that set file times must stay in the past.
4. **Conditions and warnings** (decision 5, version drift).
   Built (2026-11-16):
   - **Gradle and Maven modules** are recognized as the Gradle JPS plugin recognizes its
     own: the module file's `ExternalSystem` component or `external.system.id` attribute, and
     the Maven importer's older marker (`ExternalBuild`). Any build tool named there counts.
   - **Output directory not a source root:** the grammar is left alone. The check asks the
     module's model, not the build's root index, because `gen/` needn't exist yet. This holds
     for every target language: a Python output directory must be a source root too. That is
     odd for a non-Java target but keeps one rule: no files where the user didn't ask.
   - **Both are said once per change**, as an information message in the build output: only
     in a build where a grammar changed (so also the first build and a rebuild).
   - **The offer:** the builder also sends a custom builder message; `OutputRootOffer` in the
     IDE shows a notification with "Mark as generated sources root" and "Don't offer again"
     (per project), at most once per directory per session. Both messages name the
     directory relative to the module, and marking creates the directory if it's missing.
     After marking, JPS sees the module's roots changed and the next build generates; a test
     covers that.
   - **Version drift:** `RuntimeVersion` finds the ANTLR runtime on the module's compile
     classpath (the jar holding `RuntimeMetaData`), reads its version from the manifest, and
     warns when major.minor differs from the tool's, as the runtime's own check does.
   - The IDE side uses Java plugin classes, so the main project now compiles against the
     bundled Java plugin; those classes are only referenced from `antlr-jps.xml`.
5. README, `<change-notes>`, ARCHITECTURE.md; this doc's decisions summarized in DECISIONS.md.
   Done (2026-11-16).

B.5 (the "cannot find tokens file" editor error) is independent of all of this and can go first.

## Spike results (step 0, 2026-11-16)
- **Getting the JPS API in Gradle: settled.** The subproject `jps-plugin` applies
  `org.jetbrains.intellij.platform.base` and depends on `bundledPlugin 'com.intellij.java'`;
  it compiles with `--release 11`. The `.module` variant of the Gradle plugin is wrong here: it
  instruments the classes and ships the jar as `lib/modules/intellij-plugin-v4.jps-plugin.jar`.
  With `.base`, `runtimeOnly project(':jps-plugin')` puts a plain `lib/antlr-jps.jar` in the
  sandbox, which is what `compileServer.plugin` names.
- **Builder tests: settled, differently than planned.** `JpsBuildTestCase` isn't published
  for current IDEs (`com.jetbrains.intellij.idea:jps-build-test` stops at 2022.2.5). The
  standalone build is (`com.jetbrains.intellij.tools:jps-build-standalone`, one version per
  IDE build). `AntlrSourceGeneratorBuilderTest` writes a small project to a temp directory and
  runs a real build in the test JVM with `Standalone.runBuild`; the builder is found through
  `META-INF/services`, as in the IDE. It needs file logging off
  (`jps.use.default.file.logging=false`) and the NIO `--add-opens` the IDE gives its build
  process. The dependency's version is the IDE's build number (`262.9437.185`), a second place
  to change when `ideaVersion` changes.
- **Not yet seen: the builder running in the IDE's own build process**, which is the only
  thing that proves the `compileServer.plugin` registration. Sandbox check: open a plain
  (non-Gradle) Java project with a `.g4` under a source root, Build > Rebuild Project, and
  expect the warning "spike: build process saw changed grammars in …" in the Build tool window.

## Answers from the owner (2026-11-16)
1. **On by default**, under the conditions of decision 5.
2. **Settings** come from the existing project state (decision 3); `antlr4.properties` stays
   a later item.
3. **Targets other than Java:** generate whatever `language` says; only Java output is compiled.
4. **`gen/` is marked a generated source root** (decision 5).
