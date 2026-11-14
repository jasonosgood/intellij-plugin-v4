package org.antlr.intellij.plugin.actions;

import com.intellij.openapi.project.Project;
import org.antlr.intellij.plugin.refactor.ExtractRuleNameSuggester;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class ChooseExtractedRuleName extends DialogWrapper {
	private final String suggestedName;
	private final boolean lexerRule;
	private final Set<String> existingNames;
	JBTextField nameField;
	public String ruleName;

	protected ChooseExtractedRuleName(@Nullable Project project, String suggestedName,
	                                  boolean lexerRule, Set<String> existingNames) {
		super(project, true);
		this.suggestedName = suggestedName;
		this.lexerRule = lexerRule;
		this.existingNames = existingNames;
		init();
		initValidation();
	}

	@Override
	protected void doOKAction() {
		super.doOKAction();
		ruleName = nameField.getText().trim();
	}

	@Override
	protected @Nullable ValidationInfo doValidate() {
		String name = nameField.getText().trim();
		if ( !name.matches("[A-Za-z][A-Za-z0-9_]*") ) {
			return new ValidationInfo("Not a valid rule name", nameField);
		}
		if ( lexerRule && !Character.isUpperCase(name.charAt(0)) ) {
			return new ValidationInfo("Lexer rule names must start with an upper-case letter", nameField);
		}
		if ( !lexerRule && !Character.isLowerCase(name.charAt(0)) ) {
			return new ValidationInfo("Parser rule names must start with a lower-case letter", nameField);
		}
		if ( ExtractRuleNameSuggester.isReserved(name) ) {
			return new ValidationInfo("'" + name + "' is a reserved word in ANTLR or Java", nameField);
		}
		if ( existingNames.contains(name) ) {
			return new ValidationInfo("'" + name + "' is already defined", nameField);
		}
		return null;
	}

	@Override
	protected JComponent createCenterPanel() {
		nameField = new JBTextField(suggestedName);
		double h = nameField.getSize().getHeight();
		nameField.setPreferredSize(new Dimension(250,(int)h));
		setTitle(lexerRule ? "Name the extracted lexer rule" : "Name the extracted rule");
		nameField.selectAll();
		return nameField;
	}

	@Nullable
	@Override
	public JComponent getPreferredFocusedComponent() {
		return nameField;
	}
}
