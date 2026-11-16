package org.antlr.intellij.plugin.actions;

import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.Nullable;

/**
 * The build tool a module was imported from, if any. Such a module's own build generates its
 * recognizers (Gradle's {@code antlr} plugin, Maven's {@code antlr4-maven-plugin}), so
 * Generate ANTLR Recognizer would write a second copy, maybe with another ANTLR version. The
 * IDE's side of the JPS builder's {@code ExternalBuild}, which reads the same marks from the
 * module file.
 * <p>
 * Deviation: this knows the build tool, not whether its build applies an ANTLR plugin; that
 * would mean reading the Gradle or Maven model. Generate Anyway covers a build that doesn't.
 */
final class ExternalBuildOf {
	private ExternalBuildOf() {
	}

	/** "Gradle", "Maven" or another build tool's id; null for a plain module. */
	@Nullable
	static String module(Module module) {
		ExternalSystemModulePropertyManager properties = ExternalSystemModulePropertyManager.getInstance(module);
		if ( properties.isMavenized() ) return "Maven";
		String id = properties.getExternalSystemId();
		if ( id==null || id.isEmpty() ) return null;
		if ( id.equalsIgnoreCase("GRADLE") ) return "Gradle";
		if ( id.equalsIgnoreCase("Maven") ) return "Maven";
		return id;
	}
}
