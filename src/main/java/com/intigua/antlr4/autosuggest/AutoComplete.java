package com.intigua.antlr4.autosuggest;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.apache.commons.lang3.StringUtils;

/**
 * Suggests completions for given text, using a given ANTLR4 grammar.
 */
public class AutoComplete {

    private final LexerAndParserFactory lexerAndParserFactory;
    private final String input;
    private final Set<String> collectedSuggestions = new HashSet<String>();

    private ATN parserAtn;
    private String indent = "";
    private List<? extends Token> tokenList;
    String untokenizedText = "";

    public AutoComplete(LexerAndParserFactory lexerAndParserFactory, String input) {
        this.lexerAndParserFactory = lexerAndParserFactory;
        this.input = input;
    }

    public Collection<String> suggestCompletions() {
        createTokenList();
        Parser parser = lexerAndParserFactory.createParser(new CommonTokenStream(createLexer()));
        System.out.println("Parser rule names: " + StringUtils.join(parser.getRuleNames(), ", "));
        parserAtn = parser.getATN();
        autoSuggestNextToken();
        return collectedSuggestions;
    }

    private CuttingErrorListener createTokenList() {
        Lexer lexer = createLexer();
        CuttingErrorListener errorListener = setErrorHandler(lexer, this.input);
        tokenList = toTokenListWithEndingCaretToken(lexer);
        System.out.println("TOKENS FOUND IN FIRST PASS:");
        for (Token token : tokenList) {
            System.out.println(token);
        }
        return errorListener;
    }

    private Lexer createLexer() {
        return createLexer(this.input);
    }

    private Lexer createLexer(String lexerInput) {
        return this.lexerAndParserFactory.createLexer(toCharStream(lexerInput));
    }

    private void autoSuggestNextToken() {
        ATNState initialState = parserAtn.states.get(0);
        System.out.println("Parser initial state: " + initialState);
        parseAndCollectTokenSuggestions(initialState, 0);
    }

    private void parseAndCollectTokenSuggestions(ATNState state, int tokenListIndex) {
        indent = indent + "  ";
        try {
            System.out.println(indent + "State: " + state);
            System.out.println(
                    indent + "State type: " + state.getClass().getSimpleName() + "(" + state.getStateType() + ")");
            System.out.println(indent + "State transitions: " + transitionsStr(state));

            for (Transition trans : state.getTransitions()) {
                if (!haveMoreTokens(tokenListIndex)) {
                    suggestTokensForState(state);
                } else if (trans.isEpsilon()) {
                    handleEpsilonTransition(trans, tokenListIndex);
                } else if (trans instanceof AtomTransition) {
                    handleAtomicTransition((AtomTransition) trans, tokenListIndex);
                    // } else if (trans instanceof SetTransition) {
                    // handleSetTransition((SetTransition) trans,
                    // tokenListIndex);
                } else {
                    throw new IllegalArgumentException("Unsupported (first pass): " + transitionStr(trans));
                }
            }
        } finally {
            indent = indent.substring(2);
        }
    }

    private boolean haveMoreTokens(int tokenListIndex) {
        if (tokenListIndex >= tokenList.size())
            return false;
        if (tokenList.get(tokenListIndex).getType() < 0)
            return false;
        return true;
    }

    private void handleEpsilonTransition(Transition trans, int tokenListIndex) {
        parseAndCollectTokenSuggestions(trans.target, tokenListIndex);
    }

    private void handleAtomicTransition(AtomTransition trans, int tokenListIndex) {
        System.out.println(indent + "Token: " + tokenList.get(tokenListIndex));
        boolean matches = trans.label == tokenList.get(tokenListIndex).getType();
        if (matches) {
            System.out.println(indent + "Following transition: " + transitionStr(trans));
            parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
        } else {
            System.out.println(indent + "NOT following transition: " + transitionStr(trans));
        }
    }

    // private void handleSetTransition(SetTransition trans, int tokenListIndex)
    // {
    // System.out.println(indent + "Token: " + tokenList.get(tokenListIndex));
    // boolean matchesAny = false;
    // for(int sym : trans.label().toList()) {
    // matchesAny = matchesAny || (sym ==
    // tokenList.get(tokenListIndex).getType());
    // }
    // if (matchesAny) {
    // System.out.println(indent + "Following transition: " +
    // transitionStr(trans));
    // parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
    // } else {
    // System.out.println(indent + "NOT following transition: " +
    // transitionStr(trans));
    // }
    // }

    private void suggestTokensForState(ATNState parserState) {
        TokenCompleter tokenCompleter = new TokenCompleter(createLexer());
        Collection<String> tokenCompletions = tokenCompleter.suggest(parserState, this.untokenizedText);
        parseSuggestedCompletionsAndAddValidOnes(parserState, tokenCompletions);
        System.out.println(indent + "WILL SUGGEST TOKENS FOR STATE: " + parserState);
    }

    private void parseSuggestedCompletionsAndAddValidOnes(ATNState parserState, Collection<String> tokenCompletions) {
        for (String completion : tokenCompletions) {
            Token newToken = getAddedToken(completion);
            if (isParseableCompletionToken(completion, parserState, newToken)) {
                collectedSuggestions.add(completion);
            } else {
                System.out.println("NOT SUGGESTING: " + completion);
            }
        }
    }

    private Token getAddedToken(String completion) {
        Lexer testLexer = this.createLexer(this.input + completion);
        testLexer.removeErrorListeners();
        List<? extends Token> lexerTokens = testLexer.getAllTokens();
        if (lexerTokens.size() <= tokenList.size()) {
            return null; // Completion didn't yield a whole token, e.g just a
                         // fragment
        }
        System.out.println("TOKENS IN VALIDATION: " + lexerTokens);
        Token newToken = lexerTokens.get(lexerTokens.size() - 1);
        return newToken;
    }

    private boolean isParseableCompletionToken(String completion, ATNState parserState, Token newToken) {
        if (newToken == null) {
            return false;
        }
        for (Transition parserTransition : parserState.getTransitions()) {
            if (parserTransition.isEpsilon()) {
                if (isParseableCompletionToken(completion, parserTransition.target, newToken)) {
                    return true;
                }
            } else if (parserTransition instanceof AtomTransition) {
                AtomTransition parserAtomTransition = (AtomTransition) parserTransition;
                if (parserAtomTransition.label == newToken.getType()) {
                    return true;
                }
            } else if (parserTransition instanceof SetTransition) {
                SetTransition parserSetTransition = (SetTransition) parserTransition;
                for (int sym : parserSetTransition.label().toList()) {
                    if (sym == newToken.getType()) {
                        return true;
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported:" + transitionStr(parserTransition));
            }
        }
        return false;
    }

    private String transitionStr(Transition t) {
        return t.getClass().getSimpleName() + "->" + t.target;
    }

    private String transitionsStr(ATNState state) {
        Function<? super Transition, ? extends Object> mapper = (t) -> transitionStr(t);
        return StringUtils.join(Arrays.asList(state.getTransitions()).stream().map(mapper).collect(Collectors.toList()),
                ",");
    }

    private CuttingErrorListener setErrorHandler(Recognizer<?, ?> recognizer, String text) {
        recognizer.removeErrorListeners();
        CuttingErrorListener newErrorListener = new CuttingErrorListener(text);
        recognizer.addErrorListener(newErrorListener);
        return newErrorListener;
    }

    private List<? extends Token> toTokenListWithEndingCaretToken(Lexer lexer) {
        return lexer.getAllTokens();
    }

    private static CharStream toCharStream(String text) {
        CharStream inputStream;
        try {
            inputStream = CharStreams.fromReader(new StringReader(text));
        } catch (IOException e) {
            throw new RuntimeException("Must be a bug", e);
        }
        return inputStream;
    }

    private final class CuttingErrorListener extends BaseErrorListener {

        private final String inputText;

        CuttingErrorListener(String inputText) {
            this.inputText = inputText;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                String msg, RecognitionException e) throws ParseCancellationException {
            untokenizedText = inputText.substring(charPositionInLine);
        }
    }

}
