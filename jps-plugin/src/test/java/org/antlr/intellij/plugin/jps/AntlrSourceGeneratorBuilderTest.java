package org.antlr.intellij.plugin.jps;

import org.jetbrains.jps.build.Standalone;
import org.jetbrains.jps.cmdline.JpsModelLoader;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.model.JpsElementFactory;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.java.JpsJavaSdkType;
import org.jetbrains.jps.model.serialization.JpsProjectLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs a real JPS build, in this JVM, on a small project written to a temp directory. The
 * builder is found as in the IDE's build process: through META-INF/services on the classpath.
 * <p>
 * Prior art: the platform's own builder tests (JpsBuildTestCase) do the same. That framework
 * isn't published for current IDE builds, so this uses the published standalone build.
 */
public class AntlrSourceGeneratorBuilderTest {
	private static final String JDK_NAME = "test-jdk";

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void theBuildHandsGrammarsUnderSourceRootsToTheBuilder() throws Exception {
		Path project = temp.newFolder("project").toPath();
		write(project.resolve(".idea/modules.xml"),
			"<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>\n"+
			"  <module fileurl=\"file://$PROJECT_DIR$/demo.iml\" filepath=\"$PROJECT_DIR$/demo.iml\"/>\n"+
			"</modules></component></project>\n");
		write(project.resolve(".idea/misc.xml"),
			"<project version=\"4\"><component name=\"ProjectRootManager\" version=\"2\"\n"+
			"    project-jdk-name=\""+JDK_NAME+"\" project-jdk-type=\"JavaSDK\">\n"+
			"  <output url=\"file://$PROJECT_DIR$/out\"/>\n"+
			"</component></project>\n");
		write(project.resolve("demo.iml"),
			"<module type=\"JAVA_MODULE\" version=\"4\">\n"+
			"  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">\n"+
			"    <content url=\"file://$MODULE_DIR$\">\n"+
			"      <sourceFolder url=\"file://$MODULE_DIR$/src\" isTestSource=\"false\"/>\n"+
			"    </content>\n"+
			"    <orderEntry type=\"inheritedJdk\"/>\n"+
			"    <orderEntry type=\"sourceFolder\" forTests=\"false\"/>\n"+
			"  </component>\n"+
			"</module>\n");
		write(project.resolve("src/demo/Hello.g4"),
			"grammar Hello;\ngreeting : 'hello' ID ;\nID : [a-z]+ ;\nWS : [ \\t\\r\\n]+ -> skip ;\n");
		write(project.resolve("src/demo/Main.java"),
			"package demo;\npublic class Main {}\n");

		List<BuildMessage> messages = build(project);

		List<String> errors = new ArrayList<>();
		List<String> fromBuilder = new ArrayList<>();
		for (BuildMessage message : messages) {
			if ( message.getKind()==BuildMessage.Kind.ERROR ) errors.add(message.getMessageText());
			if ( message instanceof CompilerMessage && "ANTLR 4".equals(((CompilerMessage)message).getCompilerName()) ) {
				fromBuilder.add(message.getMessageText());
			}
		}
		assertEquals("build errors", Collections.emptyList(), errors);
		assertEquals("messages from the builder: "+fromBuilder, 1, fromBuilder.size());
		assertTrue(fromBuilder.get(0), fromBuilder.get(0).contains("Hello.g4"));
		assertTrue("the Java compiler still runs", Files.exists(project.resolve("out/production/demo/demo/Main.class")));
	}

	/** Builds every module of the project from scratch and returns the build's messages. */
	private List<BuildMessage> build(Path project) throws Exception {
		JpsModelLoader loader = () -> {
			JpsModel model = JpsElementFactory.getInstance().createModel();
			model.getGlobal().addSdk(JDK_NAME, System.getProperty("java.home"),
				System.getProperty("java.version"), JpsJavaSdkType.INSTANCE);
			JpsProjectLoader.loadProject(model.getProject(), Collections.emptyMap(), project);
			return model;
		};
		List<BuildMessage> messages = Collections.synchronizedList(new ArrayList<>());
		Standalone.runBuild(loader, temp.newFolder("build-data"), true, Collections.emptySet(), true,
			Collections.emptyList(), false, messages::add);
		return messages;
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
