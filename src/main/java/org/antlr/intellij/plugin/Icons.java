package org.antlr.intellij.plugin;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public class Icons {
	public static final Icon FILE = IconLoader.getIcon("/icons/org/antlr/intellij/plugin/antlr.png", Icons.class);
	public static final Icon LEXER_RULE = IconLoader.getIcon("/icons/org/antlr/intellij/plugin/lexer-rule.png", Icons.class);
	public static final Icon PARSER_RULE = IconLoader.getIcon("/icons/org/antlr/intellij/plugin/parser-rule.png", Icons.class);
	public static final Icon MODE = IconLoader.getIcon("/icons/org/antlr/intellij/plugin/mode.png", Icons.class);

	public static Icon getToolWindow() {
		return IconLoader.getIcon("/icons/org/antlr/intellij/plugin/toolWindowAntlr.svg", Icons.class);
	}
}
