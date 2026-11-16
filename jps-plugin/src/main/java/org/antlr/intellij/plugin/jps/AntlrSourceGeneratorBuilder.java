package org.antlr.intellij.plugin.jps;

import com.intellij.openapi.util.io.FileUtil;
import org.antlr.intellij.plugin.generation.AntlrGenerator;
import org.antlr.intellij.plugin.generation.BuildMessages;
import org.antlr.intellij.plugin.generation.GenerationArgs;
import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.antlr.intellij.plugin.generation.GrammarHeader;
import org.antlr.intellij.plugin.generation.GrammarPlace;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.builders.storage.SourceToOutputMapping;
import org.jetbrains.jps.incremental.BuilderCategory;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.FSOperations;
import org.jetbrains.jps.incremental.ModuleBuildTarget;
import org.jetbrains.jps.incremental.ModuleLevelBuilder;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.fs.CompilationRound;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.CustomBuilderMessage;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.indices.ModuleExcludeIndex;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;
import org.jetbrains.jps.util.JpsPathUtil;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates recognizers from the grammars under a module's source roots, before the Java
 * compiler runs (doc/B2_JPS_BUILDER.md).
 * <ul>
 * <li>When any grammar of a module changed, was removed, or lost a generated file, all the
 *     module's grammars are regenerated: a parser depends on its {@code tokenVocab} lexer and
 *     on imported grammars, which a per-file build would miss. Prior art: Gradle's
 *     {@code generateGrammarSource} reruns as a whole.</li>
 * <li>Every generated file is registered as an output of its grammar, so JPS deletes it when
 *     the grammar changes or goes away, and generated Java is handed to the compiler in the
 *     same build.</li>
 * <li>Arguments come from the generation core, which the Generate action also uses.</li>
 * <li>It steps aside for a module imported from Gradle or Maven, and for a grammar whose
 *     output directory isn't a source root of its module; it says so once per change.</li>
 * </ul>
 * Holds no state: JPS may build modules in parallel.
 * <p>
 * Prior art: IntelliJ's own source generators (Groovy stubs, GUI forms) are module-level
 * builders in category SOURCE_GENERATOR.
 */
public class AntlrSourceGeneratorBuilder extends ModuleLevelBuilder {
	static final String NAME = "ANTLR 4";
	private static final String GRAMMAR_SUFFIX = ".g4";
	private static final String JAVA_SUFFIX = ".java";

	public AntlrSourceGeneratorBuilder() {
		super(BuilderCategory.SOURCE_GENERATOR);
	}

	/** A grammar and the source root it is under. */
	private static final class GrammarFile {
		final File file;
		final JavaSourceRootDescriptor root;

		GrammarFile(File file, JavaSourceRootDescriptor root) {
			this.file = file;
			this.root = root;
		}
	}

	@Override
	public ExitCode build(CompileContext context,
						  ModuleChunk chunk,
						  DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
						  OutputConsumer outputConsumer) throws ProjectBuildException, IOException
	{
		ExitCode result = ExitCode.NOTHING_DONE;
		for (ModuleBuildTarget target : chunk.getTargets()) {
			ExitCode code = build(context, target, dirtyFilesHolder, outputConsumer);
			if ( code==ExitCode.ABORT ) return code;
			if ( code==ExitCode.OK ) result = code;
		}
		return result;
	}

	private ExitCode build(CompileContext context,
						   ModuleBuildTarget target,
						   DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
						   OutputConsumer outputConsumer) throws ProjectBuildException, IOException
	{
		List<GrammarFile> grammars = grammarsOf(context, target);
		if ( grammars.isEmpty() ) return ExitCode.NOTHING_DONE; // JPS deletes a removed grammar's outputs itself
		boolean changed = grammarsChanged(target, dirtyFilesHolder);
		if ( !changed && !generatedFileMissing(context, target, grammars) ) return ExitCode.NOTHING_DONE;

		JpsModule module = target.getModule();
		String externalBuild = ExternalBuild.of(module);
		if ( externalBuild!=null ) {
			// Its own build generates (Gradle's antlr plugin, Maven's antlr4 plugin); a second
			// copy from here would mean duplicate classes.
			context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.INFO,
				"Module '"+module.getName()+"' is imported from "+externalBuild+
				", whose own build generates ANTLR recognizers; none generated here."));
			return ExitCode.NOTHING_DONE;
		}

		// Grammars with the same options go to one run of the tool, which then generates a
		// lexer before the parsers that use its vocabulary. A grammar whose output directory
		// isn't a source root of the module is left alone: the compiler wouldn't see its
		// recognizer, and the user never asked for files there.
		AntlrProjectSettings projectSettings = AntlrProjectSettings.of(context.getProjectDescriptor().getProject());
		Map<List<String>, List<GrammarFile>> runs = new LinkedHashMap<>();
		Map<String, Integer> skipped = new LinkedHashMap<>(); // output root to its number of grammars
		for (GrammarFile grammar : grammars) {
			GenerationSettings settings = projectSettings.settingsFor(path(grammar.file));
			GenerationArgs args = GenerationArgs.of(settings, place(grammar, module));
			if ( isUnderSourceRootOf(module, new File(args.outputDir())) ) {
				runs.computeIfAbsent(args.asList(), o -> new ArrayList<>()).add(grammar);
			}
			else {
				skipped.merge(path(new File(args.outputRoot())), 1, Integer::sum);
			}
		}
		for (Map.Entry<String, Integer> skip : skipped.entrySet()) {
			List<String> contentRoots = new ArrayList<>();
			for (String url : module.getContentRootsList().getUrls()) contentRoots.add(JpsPathUtil.urlToPath(url));
			context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.INFO,
				"No ANTLR recognizers generated for "+skip.getValue()+" grammar(s) of module '"+module.getName()+
				"': their output directory '"+BuildMessages.displayPath(skip.getKey(), contentRoots)+
				"' isn't a source root of the module."));
			// The IDE offers to mark the directory as a generated source root.
			context.processMessage(new CustomBuilderMessage(BuildMessages.BUILDER_ID, BuildMessages.OUTPUT_NOT_SOURCE_ROOT,
				BuildMessages.outputNotSourceRoot(module.getName(), skip.getKey())));
		}
		if ( runs.isEmpty() ) return ExitCode.NOTHING_DONE;

		context.processMessage(new ProgressMessage("Generating ANTLR recognizers ["+module.getName()+"]"));

		String versionMismatch = RuntimeVersion.mismatch(module, target.isTests());
		if ( versionMismatch!=null ) {
			context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.WARNING, versionMismatch));
		}

		boolean errors = false;
		for (Map.Entry<List<String>, List<GrammarFile>> run : runs.entrySet()) {
			context.checkCanceled();
			List<String> args = new ArrayList<>(run.getKey());
			for (GrammarFile grammar : run.getValue()) args.add(grammar.file.getPath());

			AntlrGenerator.Result result = AntlrGenerator.run(args);

			for (ToolMessage message : result.messages) {
				context.processMessage(compilerMessage(message));
			}
			if ( result.failure!=null ) {
				context.processMessage(CompilerMessage.createInternalBuilderError(NAME, result.failure));
			}
			errors |= result.hasErrors();

			for (Map.Entry<String, List<File>> outputs : result.outputs.entrySet()) {
				Collection<String> source = Collections.singletonList(path(new File(outputs.getKey())));
				for (File output : outputs.getValue()) {
					outputConsumer.registerOutputFile(target, output, source);
					if ( output.getName().endsWith(JAVA_SUFFIX) ) {
						FSOperations.markDirty(context, CompilationRound.CURRENT, output); // compiled in this build
					}
				}
			}
		}
		// As the Java compiler does: stop here, so nothing compiles against stale recognizers.
		return errors ? ExitCode.ABORT : ExitCode.OK;
	}

	/** Asked of the module's model, not of the build's root index: the directory needn't exist yet. */
	private static boolean isUnderSourceRootOf(JpsModule module, File dir) {
		for (JpsModuleSourceRoot root : module.getSourceRoots()) {
			if ( root.getRootType() instanceof JavaSourceRootType && FileUtil.isAncestor(root.getFile(), dir, false) ) return true;
		}
		return false;
	}

	/** Every grammar under the target's source roots, in path order. */
	private static List<GrammarFile> grammarsOf(CompileContext context, ModuleBuildTarget target) {
		ModuleExcludeIndex excludes = context.getProjectDescriptor().getModuleExcludeIndex();
		Map<String, GrammarFile> grammars = new TreeMap<>();
		for (JavaSourceRootDescriptor root : context.getProjectDescriptor().getBuildRootIndex().getTargetRoots(target, context)) {
			if ( !root.getRootFile().isDirectory() ) continue; // a root that doesn't exist yet, like gen/
			try {
				Files.walkFileTree(root.getRootFile().toPath(), new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
						return excludes.isExcluded(dir.toFile()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
						if ( attributes.isRegularFile() && file.getFileName().toString().endsWith(GRAMMAR_SUFFIX) ) {
							grammars.putIfAbsent(path(file.toFile()), new GrammarFile(file.toFile(), root));
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException e) {
						return FileVisitResult.CONTINUE; // unreadable: not a grammar we can build
					}
				});
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return new ArrayList<>(grammars.values());
	}

	/** Whether a grammar of the target changed or was removed since the last build. */
	private static boolean grammarsChanged(ModuleBuildTarget target,
										   DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder) throws IOException
	{
		boolean[] changed = {false};
		dirtyFilesHolder.processDirtyFiles((dirtyTarget, file, root) -> {
			if ( dirtyTarget.equals(target) && file.getName().endsWith(GRAMMAR_SUFFIX) ) changed[0] = true;
			return !changed[0];
		});
		if ( changed[0] ) return true;

		for (Path removed : dirtyFilesHolder.getRemoved(target)) {
			if ( removed.toString().endsWith(GRAMMAR_SUFFIX) ) return true;
		}
		return false;
	}

	/** Whether a file generated earlier is gone (say, gen/ was deleted by hand): it must come
	 *  back, though its grammar didn't change.
	 */
	private static boolean generatedFileMissing(CompileContext context, ModuleBuildTarget target, List<GrammarFile> grammars) throws IOException {
		SourceToOutputMapping outputs = context.getProjectDescriptor().dataManager.getSourceToOutputMap(target);
		for (GrammarFile grammar : grammars) {
			Collection<String> generated = outputs.getOutputs(path(grammar.file));
			if ( generated==null ) continue;
			for (String output : generated) {
				if ( !new File(output).exists() ) return true;
			}
		}
		return false;
	}

	/** What the generation core asks about a grammar's place, answered from the JPS model. */
	private static GrammarPlace place(GrammarFile grammar, JpsModule module) {
		File dir = grammar.file.getParentFile();
		return new GrammarPlace(
			dir.getPath(),
			contentRoot(grammar.file, module).getPath(),
			() -> GrammarHeader.fileDeclaresPackage(grammar.file.getPath()) ? null : packageOf(dir, grammar.root),
			path -> path); // JPS expanded path macros when it loaded the settings
	}

	/** The module's content root that holds the file; like the IDE, its directory if none does. */
	private static File contentRoot(File file, JpsModule module) {
		File best = null;
		for (String url : module.getContentRootsList().getUrls()) {
			File root = new File(JpsPathUtil.urlToPath(url));
			if ( FileUtil.isAncestor(root, file, true) && (best==null || FileUtil.isAncestor(best, root, true)) ) {
				best = root;
			}
		}
		return best!=null ? best : file.getParentFile();
	}

	/** The package of a directory: its path below its source root, after the root's package prefix. */
	private static String packageOf(File dir, JavaSourceRootDescriptor root) {
		String relative = FileUtil.getRelativePath(root.getRootFile(), dir);
		if ( relative==null ) return null;
		relative = ".".equals(relative) ? "" : relative.replace(File.separatorChar, '.');
		String prefix = root.getPackagePrefix();
		if ( prefix==null || prefix.isEmpty() ) return relative;
		return relative.isEmpty() ? prefix : prefix+"."+relative;
	}

	private static CompilerMessage compilerMessage(ToolMessage message) {
		BuildMessage.Kind kind = message.kind==ToolMessage.Kind.ERROR ? BuildMessage.Kind.ERROR
			: message.kind==ToolMessage.Kind.WARNING ? BuildMessage.Kind.WARNING
			: BuildMessage.Kind.INFO;
		if ( message.file==null ) return new CompilerMessage(NAME, kind, message.summary);
		// ANTLR counts columns from 0, the build's messages from 1.
		return new CompilerMessage(NAME, kind, message.summary, path(new File(message.file)),
								   -1, -1, -1, message.line, message.column+1);
	}

	/** The path as the IDE writes it, with / separators: in settings, mappings and messages. */
	private static String path(File file) {
		return FileUtil.toSystemIndependentName(file.getPath());
	}

	@Override
	public List<String> getCompilableFileExtensions() {
		return Collections.singletonList("g4");
	}

	@Override
	public String getPresentableName() {
		return NAME;
	}
}
