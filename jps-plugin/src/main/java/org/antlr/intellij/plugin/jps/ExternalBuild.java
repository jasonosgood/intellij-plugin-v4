package org.antlr.intellij.plugin.jps;

import org.jdom.Element;
import org.jetbrains.jps.model.JpsElementChildRole;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsSimpleElement;
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.serialization.JDomSerializationUtil;

/**
 * Which build tool a module was imported from, if any. Such a module's own build generates
 * its recognizers (Gradle's {@code antlr} plugin, Maven's {@code antlr4-maven-plugin}); a
 * second copy from this builder would mean duplicate classes, maybe from another ANTLR version.
 * <p>
 * Read from the module file as the Gradle JPS plugin reads it: the {@code ExternalSystem}
 * component's {@code externalSystem} attribute, or the older {@code external.system.id}
 * attribute of the module; and the Maven importer's older marker.
 */
final class ExternalBuild {
	private static final JpsElementChildRole<JpsSimpleElement<String>> ROLE =
		JpsElementChildRoleBase.create("ANTLR: build tool the module is imported from");
	private static final String MAVEN_MARKER = "org.jetbrains.idea.maven.project.MavenProjectsManager.isMavenModule";

	private ExternalBuild() {
	}

	/** The build tool's name as the IDE stores it ("GRADLE", "Maven"), or null for a plain module. */
	static String of(JpsModule module) {
		JpsSimpleElement<String> element = module.getContainer().getChild(ROLE);
		return element==null ? null : element.getData();
	}

	/** @param moduleTag the root element of the module's file */
	static void load(JpsModule module, Element moduleTag) {
		String name = null;
		Element component = JDomSerializationUtil.findComponent(moduleTag, "ExternalSystem");
		if ( component!=null ) name = component.getAttributeValue("externalSystem");
		if ( name==null ) name = moduleTag.getAttributeValue("external.system.id");
		if ( name==null && Boolean.parseBoolean(moduleTag.getAttributeValue(MAVEN_MARKER)) ) name = "Maven";
		if ( name!=null ) {
			module.getContainer().setChild(ROLE, JpsElementFactory.getInstance().createSimpleElement(name));
		}
	}
}
