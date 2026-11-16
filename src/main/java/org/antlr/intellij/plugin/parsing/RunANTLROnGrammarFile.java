package org.antlr.intellij.plugin.parsing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PathMacroManager;
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
import org.antlr.intellij.plugin.generation.AntlrGenerator;
import org.antlr.intellij.plugin.generation.GenerationArgs;
import org.antlr.intellij.plugin.generation.GrammarHeader;
import org.antlr.intellij.plugin.generation.GrammarPlace;
import org.antlr.intellij.plugin.generation.ToolMessage;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.psi.AtAction;
import org.antlr.intellij.plugin.psi.GrammarSpecNode;
import org.antlr.v4.runtime.misc.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

import static com.intellij.psi.util.PsiTreeUtil.getChildOfType;
import static org.antlr.intellij.plugin.configdialogs.ANTLRv4GrammarPropertiesStore.getGrammarProperties;
import static org.antlr.intellij.plugin.psi.MyPsiUtils.findChildrenOfType;

// learned how to do from Grammar-Kit by Gregory Shrago
public class RunANTLROnGrammarFile extends Task.Modal {
	public static final Logger LOG = Logger.getInstance("RunANTLROnGrammarFile");

	private final VirtualFile grammarFile;
	private final Project project;
	/** What the run produced; null until it has run. */
	private volatile AntlrGenerator.Result result;

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

	/** Runs the ANTLR tool on the file with its settings; the caller reports {@link #getResult}. */
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

		result = AntlrGenerator.run(args);
		for (ToolMessage message : result.messages) {
			LOG.debug(message.toString());
		}
		if ( result.failure!=null ) {
			LOG.warn("antlr4 failed on "+vfile.getPath(), result.failure);
		}
	}

	/** What the run produced, or null if it didn't run (no grammar). */
	@Nullable
	public AntlrGenerator.Result getResult() {
		return result;
	}

	/** The ANTLR tool's options for {@code vfile}, from its settings; without the grammar itself. */
	public static List<String> getANTLRArgsAsList(Project project, VirtualFile vfile) {
		return generationArgs(project, vfile).asList();
	}

	/** Hands the generation core the grammar's settings and what only the IDE knows about its
	 *  place: its content root, the package of its directory, the project's path macros.
	 */
	private static GenerationArgs generationArgs(Project project, VirtualFile vfile) {
		ANTLRv4GrammarProperties grammarProperties = getGrammarProperties(project, vfile);
		PathMacroManager macros = PathMacroManager.getInstance(project);
		GrammarPlace place = new GrammarPlace(
			getParentDir(vfile),
			getContentRoot(project, vfile).getPath(),
			() -> hasPackageDeclarationInHeader(project, vfile) ? null : packageNameOf(project, vfile.getParent()),
			macros::expandPath);
		return GenerationArgs.of(grammarProperties.toGenerationSettings(), place);
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
						return GrammarHeader.declaresPackage(atAction.getActionBlockText());
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
		return generationArgs(project, grammarFile).outputDir();
	}
}
