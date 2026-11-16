package org.antlr.intellij.plugin.parsing;

import org.antlr.intellij.plugin.ToolOutput;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.antlr.intellij.adaptor.lexer.RuleIElementType;
import org.antlr.intellij.plugin.ANTLRv4TokenTypes;
import org.antlr.intellij.plugin.configdialogs.ANTLRv4GrammarProperties;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.psi.AtAction;
import org.antlr.intellij.plugin.psi.GrammarSpecNode;
import org.antlr.v4.Tool;
import org.antlr.v4.runtime.misc.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stringtemplate.v4.misc.Misc;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import static com.intellij.psi.util.PsiTreeUtil.getChildOfType;
import static org.antlr.intellij.plugin.configdialogs.ANTLRv4GrammarPropertiesStore.getGrammarProperties;
import static org.antlr.intellij.plugin.psi.MyPsiUtils.findChildrenOfType;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

// learned how to do from Grammar-Kit by Gregory Shrago
public class RunANTLROnGrammarFile extends Task.Modal {
	public static final Logger LOG = Logger.getInstance("RunANTLROnGrammarFile");
	public static final String OUTPUT_DIR_NAME = "gen" ;
	public static final String groupDisplayId = "ANTLR 4 Parser Generation";

	private static final Pattern PACKAGE_DEFINITION_REGEX = Pattern.compile("package\\s+[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+[0-9a-z_];");

	private final VirtualFile grammarFile;
	private final Project project;

	/** Generates the recognizer for {@code grammarFile} into its output directory. Run only on
	 *  the user's request (Generate ANTLR Recognizer), never on a save: code generation belongs
	 *  to the user or the build (doc/IN_PROGRESS.md, "Saving and code generation").
	 */
	public RunANTLROnGrammarFile(VirtualFile grammarFile,
								 @Nullable final Project project,
								 @NotNull final String title,
								 final boolean canBeCancelled)
	{
		super(project, title, canBeCancelled);
		this.grammarFile = grammarFile;
		this.project = project;
	}

	@Override
	public void run(@NotNull ProgressIndicator indicator) {
		indicator.setIndeterminate(true);
		ReadAction.runBlocking(() -> antlr(grammarFile));
	}

	/** Run ANTLR tool on file according to preferences in intellij for this file.
	 *  Returns set of generated files or empty set if error.
 	 */
	private void antlr(VirtualFile vfile) {
		if ( vfile==null ) return;

		LOG.debug("antlr(\""+vfile.getPath()+"\")");
		List<String> args = getANTLRArgsAsList(project, vfile);

		String sourcePath = getParentDir(vfile);
		String fullyQualifiedInputFileName = sourcePath+File.separator+vfile.getName();
		args.add(fullyQualifiedInputFileName); // add grammar file last

		String lexerGrammarFileName = ParsingUtils.getLexerNameFromParserFileName(fullyQualifiedInputFileName);
		if ( new File(lexerGrammarFileName).exists() ) {
			// build the lexer too as the grammar surely uses it if it exists
			args.add(lexerGrammarFileName);
		}

		LOG.debug("args: " + Utils.join(args.iterator(), " "));

		Tool antlr = new Tool(args.toArray(new String[args.size()]));

		ToolOutput console = ToolOutput.getInstance(project);
		String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Calendar.getInstance().getTime());
		console.print(timeStamp+": antlr4 "+Misc.join(args.iterator(), " ")+"\n", ConsoleViewContentType.SYSTEM_OUTPUT);
		antlr.removeListeners();
		RunANTLRListener listener = new RunANTLRListener(antlr, console);
		antlr.addListener(listener);

		try {
			antlr.processGrammarsOnCommandLine();
		}
		catch (Throwable e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			String msg = sw.toString();
			Notification notification =
				new Notification(groupDisplayId,
					"can't generate parser for " + vfile.getName(),
					e.toString(),
					NotificationType.INFORMATION);
			Notifications.Bus.notify(notification, project);
			console.print(timeStamp + ": antlr4 " + msg + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
			listener.hasOutput = true; // show console below
		}

		if ( listener.hasOutput ) {
			ToolOutput.showWindow(project);
		}
	}

	public static List<String> getANTLRArgsAsList(Project project, VirtualFile vfile) {
		Map<String,String> argMap = getANTLRArgs(project, vfile);
		List<String> args = new ArrayList<>();
		for (String option : argMap.keySet()) {
			args.add(option);
			String value = argMap.get(option);
			if ( value.length()!=0 ) {
				args.add(value);
			}
		}
		return args;
	}

	private static Map<String,String> getANTLRArgs(Project project, VirtualFile vfile) {
		Map<String,String> args = new HashMap<>();
		ANTLRv4GrammarProperties grammarProperties = getGrammarProperties(project, vfile);
		String sourcePath = getParentDir(vfile);

		String package_ = grammarProperties.getPackage();
		if ( isBlank(package_) && !hasPackageDeclarationInHeader(project, vfile)) {
			package_ = packageNameOf(project, vfile.getParent());
		}
		if ( isNotBlank(package_) ) {
			args.put("-package", package_);
		}

		String language = grammarProperties.getLanguage();
		if ( isNotBlank(language) ) {
			args.put("-Dlanguage="+language, "");
		}

		// create gen dir at root of project by default, but add in package if any
		VirtualFile contentRoot = getContentRoot(project, vfile);
		String outputDirName = grammarProperties.resolveOutputDirName(project, contentRoot, package_);
		args.put("-o", outputDirName);

		String libDir = grammarProperties.resolveLibDir(project, sourcePath);
		File f = new File(libDir);
		if ( !f.isAbsolute() ) { // if not absolute file spec, it's relative to project root
			libDir = contentRoot.getPath()+File.separator+libDir;
		}
		args.put("-lib", libDir);

		String encoding = grammarProperties.getEncoding();
		if ( isNotBlank(encoding) ) {
			args.put("-encoding", encoding);
		}

		if ( grammarProperties.shouldGenerateParseTreeListener() ) {
			args.put("-listener", "");
		}
		else {
			args.put("-no-listener", "");
		}
		if ( grammarProperties.shouldGenerateParseTreeVisitor() ) {
			args.put("-visitor", "");
		}
		else {
			args.put("-no-visitor", "");
		}

		return args;
	}

	private static boolean hasPackageDeclarationInHeader(Project project, VirtualFile grammarFile) {
		return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
			PsiFile file = PsiManager.getInstance(project).findFile(grammarFile);
			GrammarSpecNode grammarSpecNode = getChildOfType(file, GrammarSpecNode.class);

			if ( grammarSpecNode != null ) {
				RuleIElementType prequelElementType = ANTLRv4TokenTypes.getRuleElementType(ANTLRv4Parser.RULE_prequelConstruct);

				for ( PsiElement prequelConstruct : findChildrenOfType(grammarSpecNode, prequelElementType) ) {
					AtAction atAction = getChildOfType(prequelConstruct, AtAction.class);

					if ( atAction!=null && atAction.getIdText().equals("header") ) {
						return PACKAGE_DEFINITION_REGEX.matcher(atAction.getActionBlockText()).find();
					}
				}
			}

			return false;
		});
	}

	private static String getParentDir(VirtualFile vfile) {
		return vfile.getParent().getPath();
	}

	/**
	 * The JVM package of a directory: its path below its source root, after that root's package
	 * prefix; "" for the root itself, null outside source roots. Same result as the deprecated
	 * {@code ProjectFileIndex.getPackageNameByDirectory}, whose replacement ({@code PackageIndex})
	 * is in the Java plugin, which this plugin doesn't require.
	 */
	@Nullable
	static String packageNameOf(Project project, VirtualFile dir) {
		return ReadAction.computeBlocking(() -> {
			ProjectFileIndex index = ProjectFileIndex.getInstance(project);
			VirtualFile root = index.getSourceRootForFile(dir);
			if ( root==null ) return null;
			String relative = VfsUtilCore.getRelativePath(dir, root, '.');
			if ( relative==null ) return null;
			String prefix = "";
			Module module = index.getModuleForFile(dir);
			if ( module!=null ) {
				for (ContentEntry entry : ModuleRootManager.getInstance(module).getContentEntries()) {
					for (SourceFolder folder : entry.getSourceFolders()) {
						if ( root.equals(folder.getFile()) ) prefix = folder.getPackagePrefix();
					}
				}
			}
			if ( prefix.isEmpty() ) return relative;
			return relative.isEmpty() ? prefix : prefix+"."+relative;
		});
	}

	private static VirtualFile getContentRoot(Project project, VirtualFile vfile) {
		return ReadAction.computeBlocking(() -> {
			VirtualFile root = ProjectRootManager.getInstance(project).getFileIndex().getContentRootForFile(vfile);

			if (root != null) {
				return root;
			}
			return vfile.getParent();
		});
	}

	public String getOutputDirName() {
		VirtualFile contentRoot = getContentRoot(project, grammarFile);
		Map<String,String> argMap = getANTLRArgs(project, grammarFile);
		String package_ = argMap.get("-package");

		return getGrammarProperties(project, grammarFile)
				.resolveOutputDirName(project, contentRoot, package_);
	}
}
