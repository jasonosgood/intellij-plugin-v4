package org.antlr.intellij.plugin.jps;

import org.antlr.intellij.plugin.generation.GenerationSettings;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.incremental.BuilderCategory;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ModuleBuildTarget;
import org.jetbrains.jps.incremental.ModuleLevelBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spike (doc/B2_JPS_BUILDER.md, step 0): generates nothing. It reports the changed grammars
 * of each module as a build warning, to prove that the build process loads this jar and hands
 * it the grammars under source roots. Step 3 replaces the body of {@link #build}.
 * <p>
 * Prior art: IntelliJ's own source generators (Groovy stubs, GUI forms) are module-level
 * builders in category SOURCE_GENERATOR, which runs before the Java compiler.
 */
public class AntlrSourceGeneratorBuilder extends ModuleLevelBuilder {
	private static final String NAME = "ANTLR 4";
	private static final String GRAMMAR_SUFFIX = ".g4";

	public AntlrSourceGeneratorBuilder() {
		super(BuilderCategory.SOURCE_GENERATOR);
	}

	@Override
	public ExitCode build(CompileContext context,
						  ModuleChunk chunk,
						  DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget> dirtyFilesHolder,
						  OutputConsumer outputConsumer) throws IOException
	{
		AntlrProjectSettings projectSettings = AntlrProjectSettings.of(context.getProjectDescriptor().getProject());
		List<String> grammars = new ArrayList<>();
		dirtyFilesHolder.processDirtyFiles((target, file, root) -> {
			if ( file.getName().endsWith(GRAMMAR_SUFFIX) ) {
				GenerationSettings settings = projectSettings.settingsFor(file.getPath().replace(File.separatorChar, '/'));
				grammars.add(file.getPath()+" (package '"+settings.pkg+"', output '"+settings.outputDir+"')");
			}
			return true;
		});
		if ( grammars.isEmpty() ) return ExitCode.NOTHING_DONE;

		context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.WARNING,
			"spike: build process saw changed grammars in "+chunk.getPresentableShortName()+": "+grammars));
		return ExitCode.NOTHING_DONE;
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
