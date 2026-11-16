package org.antlr.intellij.plugin.build;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import org.antlr.intellij.plugin.generation.BuildMessages;
import org.jetbrains.jps.model.java.JavaSourceRootProperties;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.util.ArrayList;
import java.util.List;

public class OutputRootOfferTest extends AntlrFixtureTestCase {

	public void testMarksTheDirectoryAsAGeneratedSourceRoot() throws Exception {
		ContentEntry content = ModuleRootManager.getInstance(getModule()).getContentEntries()[0];
		String url = content.getUrl()+"/gen/main"; // neither exists yet, like gen/ before the first build
		assertNull(VirtualFileManager.getInstance().findFileByUrl(content.getUrl()+"/gen"));
		try {
			OutputRootOffer.markGeneratedSourceRoot(getModule(), url);

			VirtualFile created = VirtualFileManager.getInstance().findFileByUrl(url);
			assertNotNull("the missing directory is created, parents too", created);
			assertTrue(created.isDirectory());
			SourceFolder marked = sourceFolder(url);
			assertNotNull("gen is a source root", marked);
			assertEquals(JavaSourceRootType.SOURCE, marked.getRootType());
			JavaSourceRootProperties properties = marked.getJpsElement().getProperties(JavaSourceRootType.SOURCE);
			assertNotNull(properties);
			assertTrue("marked as generated", properties.isForGeneratedSources());
		}
		finally { // the light fixture's module is shared with other tests
			ModuleRootModificationUtil.updateModel(getModule(), model -> {
				for (ContentEntry entry : model.getContentEntries()) {
					for (SourceFolder folder : entry.getSourceFolders()) {
						if ( url.equals(folder.getUrl()) ) entry.removeSourceFolder(folder);
					}
				}
			});
			VirtualFile gen = VirtualFileManager.getInstance().findFileByUrl(content.getUrl()+"/gen");
			if ( gen!=null ) WriteAction.run(() -> gen.delete(this));
		}
	}

	public void testOffersOncePerDirectoryAndIgnoresOtherBuilders() {
		OutputRootOffer offer = new OutputRootOffer(getProject());
		String contentRoot = VfsUtilCore.urlToPath(ModuleRootManager.getInstance(getModule()).getContentRootUrls()[0]);
		String text = BuildMessages.outputNotSourceRoot(getModule().getName(), contentRoot+"/gen");

		offer.messageReceived("someone-else", BuildMessages.OUTPUT_NOT_SOURCE_ROOT, text);
		assertEquals(0, offers().size());

		offer.messageReceived(BuildMessages.BUILDER_ID, BuildMessages.OUTPUT_NOT_SOURCE_ROOT, text);
		offer.messageReceived(BuildMessages.BUILDER_ID, BuildMessages.OUTPUT_NOT_SOURCE_ROOT, text);
		List<Notification> offers = offers();
		try {
			assertEquals("the second build doesn't ask again", 1, offers.size());
			assertTrue("the directory is shown relative to the module: "+offers.get(0).getContent(),
				offers.get(0).getContent().contains("their output directory 'gen' isn't"));
			assertEquals(2, offers.get(0).getActions().size());
		}
		finally {
			offers.forEach(Notification::expire);
		}
	}

	private SourceFolder sourceFolder(String url) {
		for (ContentEntry entry : ModuleRootManager.getInstance(getModule()).getContentEntries()) {
			for (SourceFolder folder : entry.getSourceFolders()) {
				if ( url.equals(folder.getUrl()) ) return folder;
			}
		}
		return null;
	}

	private List<Notification> offers() {
		List<Notification> offers = new ArrayList<>();
		for (Notification notification : NotificationsManager.getNotificationsManager()
				.getNotificationsOfType(Notification.class, getProject())) {
			if ( "ANTLR recognizers not generated".equals(notification.getTitle()) ) offers.add(notification);
		}
		return offers;
	}
}
