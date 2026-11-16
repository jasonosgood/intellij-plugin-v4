package org.antlr.intellij.plugin.jps;

import org.jetbrains.jps.incremental.BuilderService;
import org.jetbrains.jps.incremental.ModuleLevelBuilder;

import java.util.Collections;
import java.util.List;

/** Tells the build process about this plugin's builders. Found through
 *  {@code META-INF/services/org.jetbrains.jps.incremental.BuilderService}.
 */
public class AntlrBuilderService extends BuilderService {
	@Override
	public List<? extends ModuleLevelBuilder> createModuleLevelBuilders() {
		return Collections.singletonList(new AntlrSourceGeneratorBuilder());
	}
}
