package org.antlr.intellij.plugin.generation;

import org.antlr.v4.Tool;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;

import java.util.regex.Pattern;

/**
 * Whether a grammar's {@code @header} action declares the package of the generated code
 * itself. If it does, the package of the grammar's directory must not be passed to the tool
 * as well, or the generated files would declare two.
 */
public final class GrammarHeader {
	private static final Pattern PACKAGE_DECLARATION =
		Pattern.compile("package\\s+[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+[0-9a-z_];");

	private GrammarHeader() {
	}

	/** @param headerAction the text of an {@code @header} action, with or without its braces */
	public static boolean declaresPackage(String headerAction) {
		return PACKAGE_DECLARATION.matcher(headerAction).find();
	}

	/** Reads the grammar file with ANTLR's own parser; for callers without PSI (the build
	 *  process). A grammar that can't be read or parsed declares nothing; generating from it
	 *  reports why.
	 */
	public static boolean fileDeclaresPackage(String grammarFile) {
		try {
			Tool antlr = new Tool();
			antlr.removeListeners();
			antlr.addListener(SILENT);
			GrammarRootAST root = antlr.parseGrammar(grammarFile);
			if ( root==null ) return false;
			for (GrammarAST action : root.getNodesWithType(ANTLRParser.AT)) {
				// ^(AT scope? name ACTION)
				int children = action.getChildCount();
				if ( children<2 ) continue;
				if ( !"header".equals(action.getChild(children-2).getText()) ) continue;
				if ( declaresPackage(action.getChild(children-1).getText()) ) return true;
			}
			return false;
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static final ANTLRToolListener SILENT = new ANTLRToolListener() {
		@Override
		public void info(String msg) {
		}

		@Override
		public void error(ANTLRMessage msg) {
		}

		@Override
		public void warning(ANTLRMessage msg) {
		}
	};
}
