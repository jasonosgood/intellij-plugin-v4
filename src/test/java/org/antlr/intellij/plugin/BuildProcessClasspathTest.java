package org.antlr.intellij.plugin;

import org.antlr.intellij.plugin.jps.AntlrSourceGeneratorBuilder;
import org.antlr.v4.Tool;
import org.antlr.v4.runtime.Parser;
import org.junit.Test;
import org.stringtemplate.v4.ST;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * META-INF/antlr-jps.xml names, by file name, the jars from the plugin's lib/ that the build
 * process loads. The names carry versions, and nothing fails at build time when one goes
 * stale: the builder would only fail in a user's build, with a NoClassDefFoundError. This
 * checks the names against the jars the classes actually come from, which are the files the
 * plugin ships in lib/.
 */
public class BuildProcessClasspathTest {
	@Test
	public void everyJarTheBuilderNeedsIsNamed() throws Exception {
		List<String> named = namedJars();

		List<String> needed = new ArrayList<>();
		for (Class<?> c : Arrays.asList(
			AntlrSourceGeneratorBuilder.class, // the builder and the generation core
			Tool.class,                        // the ANTLR tool
			Parser.class,                      // its runtime
			ST.class,                          // StringTemplate, for code generation
			org.antlr.runtime.Token.class))    // the ANTLR 3 runtime that the tool and StringTemplate use
		{
			needed.add(jarOf(c));
		}

		assertEquals(needed, named);
	}

	/** The file name of the jar a class was loaded from, read off its resource URL
	 *  ({@code jar:file:/.../name.jar!/pkg/Name.class}); the test class loader gives no code source.
	 */
	private static String jarOf(Class<?> c) {
		String url = String.valueOf(c.getResource(c.getSimpleName()+".class"));
		int end = url.indexOf("!/");
		assertTrue(c.getName()+" isn't loaded from a jar: "+url, end>0);
		return new File(url.substring(0, end)).getName();
	}

	private static List<String> namedJars() throws Exception {
		String xml;
		try (InputStream in = BuildProcessClasspathTest.class.getResourceAsStream("/META-INF/antlr-jps.xml")) {
			xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher classpath = Pattern.compile("<compileServer\\.plugin\\s+classpath=\"([^\"]*)\"").matcher(xml);
		assertTrue("no compileServer.plugin in antlr-jps.xml", classpath.find());
		return Arrays.asList(classpath.group(1).split(";"));
	}
}
