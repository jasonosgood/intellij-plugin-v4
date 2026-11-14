package org.antlr.intellij.plugin;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.validation.CreateRuleFix;
import org.antlr.intellij.plugin.validation.AddTokenDefinitionFix;
import org.antlr.intellij.plugin.validation.GrammarIssue;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ErrorType;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Optional;

public class ANTLRv4ExternalAnnotatorTest {

    @Test
    public void shouldRegisterTokenDefinitionQuickFix() {
        // when:
        Optional<IntentionAction> fix = ANTLRv4ExternalAnnotator.getFixForIssue(
            new TextRange(0, 0), new GrammarIssue(new ANTLRMessage(ErrorType.IMPLICIT_TOKEN_DEFINITION)), null);

        // then:
        Assert.assertTrue(fix.isPresent());
        Assert.assertTrue(fix.get() instanceof AddTokenDefinitionFix);
    }

    @Test
    public void shouldRegisterCreateRuleQuickFix() {
        // given:
        PsiFile file = Mockito.mock(PsiFile.class);
        Mockito.when(file.getText()).thenReturn("sample text");

        // when:
        Optional<IntentionAction> fix = ANTLRv4ExternalAnnotator.getFixForIssue(
            new TextRange(0, 0), new GrammarIssue(new ANTLRMessage(ErrorType.UNDEFINED_RULE_REF)), file);

        // then:
        Assert.assertTrue(fix.isPresent());
        Assert.assertTrue(fix.get() instanceof CreateRuleFix);
    }
}