package org.antlr.intellij.plugin.validation;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.PluginIgnoreMissingTokensFileErrorManager;
import org.antlr.intellij.plugin.ANTLRv4FileRoot;
import org.antlr.intellij.plugin.parsing.RunANTLROnGrammarFile;
import org.antlr.intellij.plugin.psi.MyPsiUtils;
import org.antlr.intellij.plugin.resolve.TokenVocabResolver;
import org.antlr.runtime.ANTLRReaderStream;
import org.antlr.runtime.Token;
import org.antlr.v4.Tool;
import org.antlr.v4.codegen.CodeGenerator;
import org.antlr.v4.codegen.Target;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.*;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.RuleRefAST;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.stringtemplate.v4.ST;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.util.*;


public class GrammarIssuesCollector {
    private static final String LANGUAGE_ARG_PREFIX = "-Dlanguage=";

    public static final Logger LOG = Logger.getInstance(GrammarIssuesCollector.class.getName());

    /** Everything the analysis needs from PSI and project settings, captured under a read
     *  lock by {@link #snapshot} so that the slow ANTLR run in {@link #collectGrammarIssues}
     *  can happen without one (the ExternalAnnotator collectInformation/doAnnotate contract).
     */
    public static final class Input {
        final String path;
        final String name;
        final String text;
        final List<String> args;
        /** null when the user's ANTLR args already specify -lib */
        @Nullable final String libDirectory;
        /** The {@code tokenVocab} lexer grammar next to this one, as the editor shows it (saved
         *  or not); null if there's no {@code tokenVocab} or no such grammar. */
        @Nullable final String vocabPath;
        @Nullable final String vocabText;

        Input(String path, String name, String text, List<String> args, @Nullable String libDirectory,
              @Nullable String vocabPath, @Nullable String vocabText) {
            this.path = path;
            this.name = name;
            this.text = text;
            this.args = args;
            this.libDirectory = libDirectory;
            this.vocabPath = vocabPath;
            this.vocabText = vocabText;
        }
    }

    /** Must be called with read access (ExternalAnnotator.collectInformation already has it). */
    @Nullable
    public static Input snapshot(PsiFile file) {
        VirtualFile vfile = file.getVirtualFile();
        if ( vfile==null ) {
            LOG.error("doAnnotate no virtual file for "+file);
            return null;
        }
        List<String> args = RunANTLROnGrammarFile.getANTLRArgsAsList(file.getProject(), vfile);
        String libDirectory = null;
        if ( !args.contains("-lib") ) {
            // Fallback only: getANTLRArgsAsList currently always supplies -lib.
            PsiDirectory dir = file.getContainingDirectory();
            // Use the directory's real path; PsiDirectory.toString() is "PsiDirectory:<path>", not a path.
            libDirectory = dir != null ? dir.getVirtualFile().getPath() : null;
        }
        PsiFile vocab = tokenVocabGrammar(file);
        VirtualFile vocabFile = vocab==null ? null : vocab.getVirtualFile();
        return new Input(vfile.getPath(), file.getName(), file.getText(), args, libDirectory,
                         vocabFile==null ? null : vocabFile.getPath(), vocabFile==null ? null : vocab.getText());
    }

    /** The grammar named by {@code file}'s {@code tokenVocab} option, next to it, or null. */
    @Nullable
    private static PsiFile tokenVocabGrammar(PsiFile file) {
        if ( !(file instanceof ANTLRv4FileRoot root) ) return null;
        String vocabName = MyPsiUtils.findTokenVocabIfAny(root);
        if ( vocabName==null ) return null;
        return TokenVocabResolver.findRelativeFile(StringUtils.strip(vocabName, "'"), file);
    }

    /** Runs the ANTLR tool on a snapshot; touches no PSI, so call it without a read lock. */
    public static List<GrammarIssue> collectGrammarIssues(Input input) {
        String grammarFileName = input.path;
        LOG.debug("doAnnotate "+grammarFileName);
        String fileContents = input.text;
        List<String> args = new ArrayList<>(input.args); // copied: may be modified below
        GrammarIssuesCollectorToolListener listener = new GrammarIssuesCollectorToolListener();

        String languageArg = findLanguageArg(args);

        if ( languageArg!=null ) {
            String language = languageArg.substring(LANGUAGE_ARG_PREFIX.length());

            if ( !targetExists(language) ) {
                GrammarIssue issue = new GrammarIssue(null);
                issue.setAnnotation("Unknown target language '" + language + "', analysis will be done using the default target language 'Java'");
                listener.getIssues().add(issue);

                args.remove(languageArg);
            }
        }

        final Tool antlr = new Tool(args.toArray(new String[args.size()]));
        if ( input.libDirectory!=null ) {
            antlr.libDirectory = input.libDirectory;
        }

        antlr.removeListeners();
        antlr.addListener(listener);
        try {
            StringReader sr = new StringReader(fileContents);
            ANTLRReaderStream in = new ANTLRReaderStream(sr);
            in.name = input.name;
            GrammarRootAST ast = antlr.parse(input.name, in);
            if ( ast==null || ast.hasErrors ) {
                for (GrammarIssue issue : listener.getIssues()) {
                    processIssue(input.path, issue);
                }
                return listener.getIssues();
            }
            Grammar g = antlr.createGrammar(ast);
            g.fileName = grammarFileName;

            String vocabName = g.getOptionString("tokenVocab");
            if ( vocabName!=null ) { // import vocab to avoid spurious warnings
                LOG.debug("token vocab file "+vocabName);
                Grammar vocab = input.vocabPath==null || input.vocabText==null ? null
                    : loadVocabGrammar(input.vocabPath, input.vocabText, input.libDirectory, args);
                if ( vocab!=null ) {
                    g.importVocab(vocab);
                    // process() still looks for the lexer's .tokens file and, with none
                    // generated, reports an error that also stops the analysis after it. The
                    // vocabulary is already here, so ignore that error, as the preview does.
                    antlr.errMgr = new PluginIgnoreMissingTokensFileErrorManager(antlr);
                    antlr.errMgr.setFormat(antlr.msgFormat);
                }
                else {
                    // no lexer grammar beside this one: a .tokens file from elsewhere, via -lib
                    g.importTokensFromTokensFile();
                }
            }

            antlr.process(g, false);

            Map<String, GrammarAST> unusedRules = getUnusedParserRules(g);
            if ( unusedRules!=null ) {
                for (String r : unusedRules.keySet()) {
                    Token ruleDefToken = unusedRules.get(r).getToken();
                    GrammarIssue issue = new GrammarIssue(new GrammarInfoMessage(g.fileName, ruleDefToken, r));
                    listener.getIssues().add(issue);
                }
            }

            for (GrammarIssue issue : listener.getIssues()) {
                processIssue(input.path, issue);
            }
        }
        catch (Exception e) {
            LOG.error("antlr can't process "+input.name, e);
        }
        return listener.getIssues();
    }

    /** Loads the {@code tokenVocab} lexer from its text, for its tokens only: its own problems
     *  are reported in its own editor. Prior art: the preview imports the lexer's vocabulary the
     *  same way ({@code ParsingUtils.loadGrammars}), with no generated {@code .tokens} file.
     */
    @Nullable
    private static Grammar loadVocabGrammar(String vocabPath, String vocabText, @Nullable String libDirectory,
                                            List<String> args) {
        Tool antlr = new Tool(args.toArray(new String[0]));
        if ( libDirectory!=null ) {
            antlr.libDirectory = libDirectory;
        }
        antlr.removeListeners();
        antlr.addListener(new GrammarIssuesCollectorToolListener()); // discarded
        try {
            ANTLRReaderStream in = new ANTLRReaderStream(new StringReader(vocabText));
            in.name = new File(vocabPath).getName();
            GrammarRootAST ast = antlr.parse(in.name, in);
            if ( ast==null || ast.hasErrors ) return null;
            Grammar vocab = antlr.createGrammar(ast);
            vocab.fileName = vocabPath;
            antlr.process(vocab, false);
            return vocab;
        }
        catch (Exception e) {
            LOG.debug("can't load token vocab "+vocabPath, e);
            return null;
        }
    }

    @Nullable
    private static String findLanguageArg(List<String> args) {
        for ( String arg : args ) {
            if ( arg.startsWith(LANGUAGE_ARG_PREFIX) ) {
                return arg;
            }
        }

        return null;
    }

    public static void processIssue(final String grammarFilePath, GrammarIssue issue) {
        File grammarFile = new File(grammarFilePath);
        if ( issue.getMsg() == null || issue.getMsg().fileName==null ) { // weird, issue doesn't have a file associated with it
            return;
        }
        File issueFile = new File(issue.getMsg().fileName);
        if ( !grammarFile.getName().equals(issueFile.getName()) ) {
            return; // ignore errors from external files
        }
        ST msgST = null;
        if ( issue.getMsg() instanceof GrammarInfoMessage ) { // not in ANTLR so must hack it in
            Token t = ((GrammarSemanticsMessage) issue.getMsg()).offendingToken;
            issue.getOffendingTokens().add(t);
            msgST = new ST("unused parser rule <arg>");
            msgST.add("arg", t.getText());
            msgST.impl.name = "info";
        }
        else if ( issue.getMsg() instanceof GrammarSemanticsMessage ) {
            Token t = ((GrammarSemanticsMessage) issue.getMsg()).offendingToken;
            issue.getOffendingTokens().add(t);
        }
        else if ( issue.getMsg() instanceof LeftRecursionCyclesMessage) {
            List<String> rulesToHighlight = new ArrayList<>();
            LeftRecursionCyclesMessage lmsg = (LeftRecursionCyclesMessage) issue.getMsg();
            Collection<? extends Collection<Rule>> cycles =
                (Collection<? extends Collection<Rule>>)lmsg.getArgs()[0];
            for (Collection<Rule> cycle : cycles) {
                for (Rule r : cycle) {
                    rulesToHighlight.add(r.name);
                    GrammarAST nameNode = (GrammarAST)r.ast.getChild(0);
                    issue.getOffendingTokens().add(nameNode.getToken());
                }
            }
        }
        else if ( issue.getMsg() instanceof GrammarSyntaxMessage) {
            Token t = issue.getMsg().offendingToken;
            issue.getOffendingTokens().add(t);
        }
        else if ( issue.getMsg() instanceof ToolMessage ) {
            issue.getOffendingTokens().add(issue.getMsg().offendingToken);
        }

        Tool antlr = new Tool();
        if ( msgST==null ) {
            msgST = antlr.errMgr.getMessageTemplate(issue.getMsg());
        }
        String outputMsg = msgST.render();
        if ( antlr.errMgr.formatWantsSingleLineMessage() ) {
            outputMsg = outputMsg.replace('\n', ' ');
        }
        issue.setAnnotation(outputMsg);
    }

    private static Map<String,GrammarAST> getUnusedParserRules(Grammar g) {
        if ( g.ast==null || g.isLexer() ) return null;
        List<GrammarAST> ruleNodes = g.ast.getNodesWithTypePreorderDFS(IntervalSet.of(ANTLRParser.RULE_REF));
        // in case of errors, we walk AST ourselves
        // ANTLR's Grammar object might have bailed on rule defs etc...
        Set<String> ruleRefs = new HashSet<>();
        Map<String,GrammarAST> ruleDefs = new HashMap<>();
        for (GrammarAST x : ruleNodes) {
            if ( x.getParent().getType()==ANTLRParser.RULE ) {
                ruleDefs.put(x.getText(), x);
            }
            else if ( x instanceof RuleRefAST) {
                RuleRefAST r = (RuleRefAST) x;
                ruleRefs.add(r.getText());
            }
        }
        ruleDefs.keySet().removeAll(ruleRefs);
        return ruleDefs;
    }

    public static boolean targetExists(String language) {
        String targetName = "org.antlr.v4.codegen.target."+language+"Target";
        try {
            Class<? extends Target> c = Class.forName(targetName).asSubclass(Target.class);
            Constructor<? extends Target> ctor = c.getConstructor(CodeGenerator.class);
            return true;
        }
        catch (Exception e) { // ignore errors; we're detecting presence only
        }
        return false;
    }
}
