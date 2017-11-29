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

import org.antlr.v4.runtime.ANTLRErrorListener;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Suggests completions for given text, using a given ANTLR4 grammar.
 */
public class AutoSuggester {
    private static final Logger logger = LoggerFactory.getLogger(AutoSuggester.class);

    private final LexerAndParserFactory lexerAndParserFactory;
    private final String input;
    private final Set<String> collectedSuggestions = new HashSet<String>();

    private List<? extends Token> inputTokens;
    private String untokenizedText = "";
    private ATN parserAtn;
    private String indent = "";

    public AutoSuggester(LexerAndParserFactory lexerAndParserFactory, String input) {
        this.lexerAndParserFactory = lexerAndParserFactory;
        this.input = input;
    }

    public Collection<String> suggestCompletions() {
        createTokenList();
        Parser parser = lexerAndParserFactory.createParser(new CommonTokenStream(createLexer()));
        logger.debug("Parser rule names: " + StringUtils.join(parser.getRuleNames(), ", "));
        parserAtn = parser.getATN();
        autoSuggestNextToken();
        return collectedSuggestions;
    }

    private void createTokenList() {
        Lexer lexer = createLexerWithUntokenizedTextDetection();
        inputTokens = lexer.getAllTokens();
        logger.debug("TOKENS FOUND IN FIRST PASS:");
        for (Token token : inputTokens) {
            logger.debug(token.toString());
        }
    }

    private Lexer createLexer() {
        return createLexer(this.input);
    }

    private Lexer createLexer(String lexerInput) {
        return this.lexerAndParserFactory.createLexer(toCharStream(lexerInput));
    }

    private void autoSuggestNextToken() {
        ATNState initialState = parserAtn.states.get(0);
        logger.debug("Parser initial state: " + initialState);
        parseAndCollectTokenSuggestions(initialState, 0);
    }

    private void parseAndCollectTokenSuggestions(ATNState state, int tokenListIndex) {
        indent = indent + "  ";
        try {
            logger.debug(indent + "State: " + state);
            logger.debug(
                    indent + "State type: " + state.getClass().getSimpleName() + "(" + state.getStateType() + ")");
            logger.debug(indent + "State transitions: " + transitionsStr(state));

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
        if (tokenListIndex >= inputTokens.size())
            return false;
        if (inputTokens.get(tokenListIndex).getType() < 0)
            return false;
        return true;
    }

    private void handleEpsilonTransition(Transition trans, int tokenListIndex) {
        parseAndCollectTokenSuggestions(trans.target, tokenListIndex);
    }

    private void handleAtomicTransition(AtomTransition trans, int tokenListIndex) {
        logger.debug(indent + "Token: " + inputTokens.get(tokenListIndex));
        boolean matches = trans.label == inputTokens.get(tokenListIndex).getType();
        if (matches) {
            logger.debug(indent + "Following transition: " + transitionStr(trans));
            parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
        } else {
            logger.debug(indent + "NOT following transition: " + transitionStr(trans));
        }
    }

    // private void handleSetTransition(SetTransition trans, int tokenListIndex)
    // {
    // logger.debug(indent + "Token: " + tokenList.get(tokenListIndex));
    // boolean matchesAny = false;
    // for(int sym : trans.label().toList()) {
    // matchesAny = matchesAny || (sym ==
    // tokenList.get(tokenListIndex).getType());
    // }
    // if (matchesAny) {
    // logger.debug(indent + "Following transition: " +
    // transitionStr(trans));
    // parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
    // } else {
    // logger.debug(indent + "NOT following transition: " +
    // transitionStr(trans));
    // }
    // }

    private void suggestTokensForState(ATNState parserState) {
        TokenSuggester tokenSuggester = new TokenSuggester(createLexer());
        Collection<String> suggestions = tokenSuggester.suggest(parserState, this.untokenizedText);
        parseSuggestionsAndAddValidOnes(parserState, suggestions);
        logger.debug(indent + "WILL SUGGEST TOKENS FOR STATE: " + parserState);
    }

    private void parseSuggestionsAndAddValidOnes(ATNState parserState, Collection<String> suggestions) {
        for (String suggestion : suggestions) {
            Token newToken = getAddedToken(suggestion);
            if (isParseableWithAddedToken(parserState, newToken)) {
                collectedSuggestions.add(suggestion);
            } else {
                logger.debug("NOT SUGGESTING: " + suggestion);
            }
        }
    }

    private Token getAddedToken(String suggestedCompletion) {
        String completedText = this.input + suggestedCompletion;
        Lexer completedTextLexer = this.createLexer(completedText);
        completedTextLexer.removeErrorListeners();
        List<? extends Token> completedTextTokens = completedTextLexer.getAllTokens();
        if (completedTextTokens.size() <= inputTokens.size()) {
            return null; // Completion didn't yield a whole token, e.g just a fragment
        }
        logger.debug("TOKENS IN COMPLETED TEXT: " + completedTextTokens);
        Token newToken = completedTextTokens.get(completedTextTokens.size() - 1);
        return newToken;
    }

    private boolean isParseableWithAddedToken(ATNState parserState, Token newToken) {
        if (newToken == null) {
            return false;
        }
        for (Transition parserTransition : parserState.getTransitions()) {
            if (parserTransition.isEpsilon()) {
                if (isParseableWithAddedToken(parserTransition.target, newToken)) {
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

    private static CharStream toCharStream(String text) {
        CharStream inputStream;
        try {
            inputStream = CharStreams.fromReader(new StringReader(text));
        } catch (IOException e) {
            throw new AssertionError("Must be a bug", e);
        }
        return inputStream;
    }

    private Lexer createLexerWithUntokenizedTextDetection() {
        Lexer lexer = this.lexerAndParserFactory.createLexer(toCharStream(this.input));
        lexer.removeErrorListeners();
        ANTLRErrorListener newErrorListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) throws ParseCancellationException {
                untokenizedText = input.substring(charPositionInLine);
            }
        };
        lexer.addErrorListener(newErrorListener);
        return lexer;
    }
}
