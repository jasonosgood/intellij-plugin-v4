package org.antlr.intellij.plugin.jps;

import org.antlr.v4.Tool;
import org.antlr.v4.runtime.RuntimeMetaData;
import org.jetbrains.jps.model.java.JpsJavaClasspathKind;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.module.JpsModule;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the ANTLR runtime on a module's classpath with the ANTLR tool this plugin
 * generates with. Generated code checks this itself, but only when it first runs, on stderr;
 * the build can say so earlier.
 * <p>
 * Prior art: {@code RuntimeMetaData.checkVersion}, whose rule (major.minor must match) and
 * wording this follows.
 */
final class RuntimeVersion {
	private static final String RUNTIME_CLASS = "org/antlr/v4/runtime/RuntimeMetaData.class";
	private static final Pattern VERSION_IN_NAME = Pattern.compile("-(\\d+(\\.\\d+)+)");

	private RuntimeVersion() {
	}

	/** The warning to show for the module, or null: no runtime found, or it matches the tool. */
	static String mismatch(JpsModule module, boolean tests) {
		String runtime = runtimeVersion(module, tests);
		if ( runtime==null ) return null;
		String tool = Tool.VERSION;
		if ( RuntimeMetaData.getMajorMinorVersion(tool).equals(RuntimeMetaData.getMajorMinorVersion(runtime)) ) return null;
		return "ANTLR Tool version "+tool+" used for code generation does not match the current runtime version "+runtime;
	}

	/** The version of the first ANTLR 4 runtime on the module's compile classpath, or null. */
	private static String runtimeVersion(JpsModule module, boolean tests) {
		for (File root : JpsJavaExtensionService.dependencies(module).recursively()
				.includedIn(JpsJavaClasspathKind.compile(tests)).classes().getRoots()) {
			// The runtime ships as antlr4-runtime-<v>.jar, or inside antlr4-<v>-complete.jar.
			if ( !root.isFile() || !root.getName().contains("antlr") ) continue;
			try (JarFile jar = new JarFile(root)) {
				if ( jar.getEntry(RUNTIME_CLASS)==null ) continue;
				String version = versionOf(jar.getManifest());
				if ( version==null ) {
					Matcher inName = VERSION_IN_NAME.matcher(root.getName());
					if ( inName.find() ) version = inName.group(1);
				}
				if ( version!=null ) return version;
			}
			catch (IOException e) {
				// not a readable jar: the compiler will say so
			}
		}
		return null;
	}

	private static String versionOf(Manifest manifest) {
		if ( manifest==null ) return null;
		Attributes attributes = manifest.getMainAttributes();
		String version = attributes.getValue("Implementation-Version");
		return version!=null ? version : attributes.getValue("Bundle-Version");
	}
}
