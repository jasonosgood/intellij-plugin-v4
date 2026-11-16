package org.antlr.intellij.plugin.build;

import com.intellij.compiler.server.CustomBuilderMessageHandler;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.antlr.intellij.plugin.format.GrammarFormattingService;
import org.antlr.intellij.plugin.generation.BuildMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When the JPS builder skips grammars because their output directory isn't a source root
 * (doc/B2_JPS_BUILDER.md, decision 5), offers to mark that directory as a generated source
 * root. Marking it is the user's go-ahead: from then on the build generates there and
 * compiles the result. The plugin never changes a module's roots unasked.
 * <p>
 * Registered in META-INF/antlr-jps.xml, so only loaded with the Java plugin, whose classes
 * this uses.
 * <p>
 * Prior art: the platform's annotation processing marks its "generated sources directory" as
 * a generated source root; builders talk to the IDE through custom builder messages.
 */
public final class OutputRootOffer implements CustomBuilderMessageHandler {
	private static final Logger LOG = Logger.getInstance(OutputRootOffer.class);
	private static final String DONT_OFFER = "antlr.build.dontOfferGeneratedSourceRoot";

	/** The directories already offered in this session, so every build doesn't ask again. */
	@Service(Service.Level.PROJECT)
	static final class Offered {
		final Set<String> roots = ConcurrentHashMap.newKeySet();
	}

	private final Project project;

	public OutputRootOffer(Project project) {
		this.project = project;
	}

	@Override
	public void messageReceived(@NotNull String builderId, @NotNull String messageType, @NotNull String messageText) {
		if ( !BuildMessages.BUILDER_ID.equals(builderId) || !BuildMessages.OUTPUT_NOT_SOURCE_ROOT.equals(messageType) ) return;
		String[] moduleAndRoot = BuildMessages.parseOutputNotSourceRoot(messageText);
		if ( moduleAndRoot==null ) return;
		offer(moduleAndRoot[0], moduleAndRoot[1]);
	}

	private void offer(String moduleName, String outputRoot) {
		if ( PropertiesComponent.getInstance(project).getBoolean(DONT_OFFER) ) return;
		if ( !project.getService(Offered.class).roots.add(moduleName+'\n'+outputRoot) ) return;

		// Shown relative to the module, like the builder's own message; the action keeps the full path.
		Module shown = ModuleManager.getInstance(project).findModuleByName(moduleName);
		List<String> contentRoots = new ArrayList<>();
		if ( shown!=null ) {
			for (String url : ModuleRootManager.getInstance(shown).getContentRootUrls()) contentRoots.add(VfsUtilCore.urlToPath(url));
		}
		Notification notification = new Notification(GrammarFormattingService.NOTIFICATION_GROUP,
			"ANTLR recognizers not generated",
			"The build skipped grammars of module '"+moduleName+"': their output directory '"+
			BuildMessages.displayPath(outputRoot, contentRoots)+"' isn't a source root.",
			NotificationType.INFORMATION);
		notification.addAction(NotificationAction.createSimpleExpiring("Mark as generated sources root", () -> {
			Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
			if ( module!=null ) markGeneratedSourceRoot(module, VfsUtilCore.pathToUrl(outputRoot));
		}));
		notification.addAction(NotificationAction.createSimpleExpiring("Don't offer again", () ->
			PropertiesComponent.getInstance(project).setValue(DONT_OFFER, true)));
		notification.notify(project);
	}

	/**
	 * Adds the directory to the module as a generated source root, creating it if it's missing
	 * so that it shows in the Project view right away. If it can't be created the root is
	 * still added: the build creates the directory when it generates.
	 */
	static void markGeneratedSourceRoot(Module module, String url) {
		try {
			createDirectory(url);
		}
		catch (IOException e) {
			LOG.warn("Can't create "+url, e);
		}
		ModuleRootModificationUtil.updateModel(module, model -> {
			ContentEntry holder = null;
			for (ContentEntry entry : model.getContentEntries()) {
				boolean holds = VfsUtilCore.isEqualOrAncestor(entry.getUrl(), url);
				if ( holds && (holder==null || entry.getUrl().length()>holder.getUrl().length()) ) holder = entry;
			}
			if ( holder==null ) holder = model.addContentEntry(url); // an output directory outside the module
			holder.addSourceFolder(url, JavaSourceRootType.SOURCE,
				JpsJavaExtensionService.getInstance().createSourceRootProperties("", true));
		});
	}

	/** Creates the directory and its missing parents under the nearest ancestor that exists. */
	private static void createDirectory(String url) throws IOException {
		String existing = url;
		VirtualFile ancestor;
		while ( (ancestor = VirtualFileManager.getInstance().refreshAndFindFileByUrl(existing))==null ) {
			int slash = existing.lastIndexOf('/');
			if ( slash<=0 ) return;
			existing = existing.substring(0, slash);
		}
		if ( existing.length()==url.length() ) return;
		VirtualFile parent = ancestor;
		String missing = url.substring(existing.length()+1);
		WriteAction.run(() -> VfsUtil.createDirectoryIfMissing(parent, missing));
	}
}
