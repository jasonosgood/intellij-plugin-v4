package org.antlr.intellij.plugin.jps;

import org.jdom.Element;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension;
import org.jetbrains.jps.model.serialization.JpsProjectExtensionSerializer;

import java.util.Collections;
import java.util.List;

/** Has the build process load {@link AntlrProjectSettings} with the project. Found through
 *  {@code META-INF/services/org.jetbrains.jps.model.serialization.JpsModelSerializerExtension}.
 */
public class AntlrModelSerializerExtension extends JpsModelSerializerExtension {
	@Override
	public void loadModuleOptions(JpsModule module, Element rootElement) {
		ExternalBuild.load(module, rootElement);
	}

	@Override
	public List<? extends JpsProjectExtensionSerializer> getProjectExtensionSerializers() {
		return Collections.singletonList(
			new JpsProjectExtensionSerializer(AntlrProjectSettings.CONFIG_FILE, AntlrProjectSettings.COMPONENT_NAME) {
				@Override
				public void loadExtension(JpsProject project, Element componentTag) {
					AntlrProjectSettings.load(project, componentTag);
				}
			});
	}
}
