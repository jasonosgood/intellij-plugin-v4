package org.antlr.intellij.plugin.editor;

import org.antlr.intellij.plugin.AntlrFixtureTestCase;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.junit.Test;
import org.junit.Assert;

public class Issue559Test extends AntlrFixtureTestCase {

    @Test
    public void test_shouldNotClosePanel() {

        Project project = getProject();
        VirtualFile[] vFiles = new VirtualFile[]{openFile("T1.g4"), openFile("T2.g4")};

        // Setup
        ANTLRv4PluginController controller = ANTLRv4PluginController.getInstance(project);
        PreviewPanel panel = new PreviewPanel(project); // light tests run no tool window factories
        Disposer.register(getTestRootDisposable(), panel);
        controller.attachPreviewPanel(panel);
        ToolWindow previewWindow = controller.previewWindowForTests = new MockToolWindow();
        FileEditorManager source = FileEditorManager.getInstance(project);

        // Close file 1
        controller.editorFileClosed(source, vFiles[0]);
        Assert.assertTrue(previewWindow.isVisible());
        Assert.assertTrue(panel.isEnabled());

        // Close file 2
        controller.editorFileClosed(source, vFiles[1]);
        Assert.assertFalse(previewWindow.isVisible());
        Assert.assertFalse(panel.isEnabled());

    }

    private VirtualFile openFile(String file) {
        VirtualFile vf = myFixture.configureByFile(file).getVirtualFile();
        myFixture.openFileInEditor(vf);
        return vf;
    }

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/editor";
    }

}