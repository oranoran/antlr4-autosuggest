package com.intigua.antlr4.autosuggest;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
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
import org.antlr.v4.runtime.misc.Interval;
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
    private final Set<String> collectedSuggestions = new HashSet<>();

    private List<? extends Token> inputTokens;
    private String untokenizedText = "";
    private ATN parserAtn;
    private String[] parserRuleNames;
    private String indent = "";

    public AutoSuggester(LexerAndParserFactory lexerAndParserFactory, String input) {
        this.lexerAndParserFactory = lexerAndParserFactory;
        this.input = input;
    }

    public Collection<String> suggestCompletions() {
        tokenizeInput();
        storeParserAtnAndRuleNames();
        runParserAtnAndCollectSuggestions();
        return collectedSuggestions;
    }

    private void tokenizeInput() {
        Lexer lexer = createLexerWithUntokenizedTextDetection(); // side effect: also fills this.untokenizedText
        List<? extends Token> allTokens = lexer.getAllTokens(); 
        this.inputTokens = filterOutNonDefaultChannels(allTokens);
        if (logger.isDebugEnabled()) {
            logger.debug("TOKENS FOUND IN FIRST PASS:");
            for (Token token : inputTokens) {
                logger.debug(token.toString());
            }
        }
    }

    private static List<? extends Token> filterOutNonDefaultChannels(List<? extends Token> tokens) {
        return tokens.stream().filter(t -> t.getChannel() == 0).collect(Collectors.toList());
    }

    private void storeParserAtnAndRuleNames() {
        Parser parserForAtnOnly = lexerAndParserFactory.createParser(null);
        logger.debug("Parser rule names: " + StringUtils.join(parserForAtnOnly.getRuleNames(), ", "));
        parserAtn = parserForAtnOnly.getATN();
        parserRuleNames = parserForAtnOnly.getRuleNames();
    }

    private void runParserAtnAndCollectSuggestions() {
        ATNState initialState = parserAtn.states.get(0);
        logger.debug("Parser initial state: " + initialState);
        parseAndCollectTokenSuggestions(initialState, 0);
    }

    /**
     * Recursive through the parser ATN to process all tokens. When successful (out of tokens) - collect completion
     * suggestions.
     */
    private void parseAndCollectTokenSuggestions(ATNState parserState, int tokenListIndex) {
        indent = indent + "  ";
        try {
            logger.debug(indent + "State: " + toString(parserState) );
            logger.debug(indent + "State available transitions: " + transitionsStr(parserState));

            if (!haveMoreTokens(tokenListIndex)) { // stop condition for recursion
                suggestNextTokensForParserState(parserState);
                return;
            }
            for (Transition trans : parserState.getTransitions()) {
                if (trans.isEpsilon()) {
                    handleEpsilonTransition(trans, tokenListIndex);
                } else if (trans instanceof AtomTransition) {
                    handleAtomicTransition((AtomTransition) trans, tokenListIndex);
                } else {
                    handleSetTransition((SetTransition)trans, tokenListIndex);
                }
            }
        } finally {
            indent = indent.substring(2);
        }
    }

    private boolean haveMoreTokens(int tokenListIndex) {
        return tokenListIndex < inputTokens.size();
    }

    private void handleEpsilonTransition(Transition trans, int tokenListIndex) {
        // Epsilon transitions don't consume a token, so don't move the index
        parseAndCollectTokenSuggestions(trans.target, tokenListIndex);
    }

    private void handleAtomicTransition(AtomTransition trans, int tokenListIndex) {
        Token nextToken = inputTokens.get(tokenListIndex);
        int nextTokenType = inputTokens.get(tokenListIndex).getType();
        boolean nextTokenMatchesTransition = (trans.label == nextTokenType);
        if (nextTokenMatchesTransition) {
            logger.debug(indent + "Token " + nextToken + " following transition: " + toString(trans));
            parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
        } else {
            logger.debug(indent + "Token " + nextToken + " NOT following transition: " + toString(trans));
        }
    }

    private void handleSetTransition(SetTransition trans, int tokenListIndex) {
        Token nextToken = inputTokens.get(tokenListIndex);
        int nextTokenType = nextToken.getType();
        for (int transitionTokenType : trans.label().toList()) {
            boolean nextTokenMatchesTransition = (transitionTokenType == nextTokenType);
            if (nextTokenMatchesTransition) {
                logger.debug(indent + "Token " + nextToken + " following transition: " + toString(trans) + " to " + transitionTokenType);
                parseAndCollectTokenSuggestions(trans.target, tokenListIndex + 1);
            } else {
                logger.debug(indent + "Token " + nextToken + " NOT following transition: " + toString(trans) + " to " + transitionTokenType);
            }
        }
    }

    private void suggestNextTokensForParserState(ATNState parserState) {
        List<Integer> transitionLabels = new ArrayList<>();
        fillParserTransitionLabels(parserState, transitionLabels, new HashSet<>());
        
        TokenSuggester tokenSuggester = new TokenSuggester(createLexer());
        Collection<String> suggestions = tokenSuggester.suggest(transitionLabels, this.untokenizedText);
        parseSuggestionsAndAddValidOnes(parserState, suggestions);
        logger.debug(indent + "WILL SUGGEST TOKENS FOR STATE: " + parserState);
    }

    private void fillParserTransitionLabels(ATNState parserState, List<Integer> result, Set<TransitionWrapper> visitedTransitions) {
        for (Transition trans : parserState.getTransitions()) {
            TransitionWrapper transWrapper = new TransitionWrapper(parserState, trans);
            if (visitedTransitions.contains(transWrapper)) {
                logger.debug(indent + "Not following visited transition " + transWrapper);
                continue;
            }
            if (trans.isEpsilon()) {
                try {
                    visitedTransitions.add(transWrapper);
                    fillParserTransitionLabels(trans.target, result, visitedTransitions);
                } finally {
                    visitedTransitions.remove(transWrapper);
                }
            } else if (trans instanceof AtomTransition) {
                int label = ((AtomTransition) trans).label;
                result.add(label);
            } else if (trans instanceof SetTransition) {
                for (Interval interval : ((SetTransition) trans).label().getIntervals()) {
                    for (int i = interval.a; i <= interval.b; ++i) {
                        result.add(i);
                    }
                }
            }
        }
    }

    private void parseSuggestionsAndAddValidOnes(ATNState parserState, Collection<String> suggestions) {
        for (String suggestion : suggestions) {
            logger.debug("CHECKING suggestion: " + suggestion);
            Token addedToken = getAddedToken(suggestion);
            if (isParseableWithAddedToken(parserState, addedToken, new HashSet<TransitionWrapper>())) {
                collectedSuggestions.add(suggestion);
            } else {
                logger.debug("DROPPING non-parseable suggestion: " + suggestion);
            }
        }
    }

    private Token getAddedToken(String suggestedCompletion) {
        String completedText = this.input + suggestedCompletion;
        Lexer completedTextLexer = this.createLexer(completedText);
        completedTextLexer.removeErrorListeners();
        List<? extends Token> completedTextTokens = filterOutNonDefaultChannels(completedTextLexer.getAllTokens());
        if (completedTextTokens.size() <= inputTokens.size()) {
            return null; // Completion didn't yield whole token, could be just a token fragment
        }
        logger.debug("TOKENS IN COMPLETED TEXT: " + completedTextTokens);
        Token newToken = completedTextTokens.get(completedTextTokens.size() - 1);
        return newToken;
    }

    private boolean isParseableWithAddedToken(ATNState parserState, Token newToken, Set<TransitionWrapper> visitedTransitions) {
        if (newToken == null) {
            return false;
        }
        for (Transition parserTransition : parserState.getTransitions()) {
            if (parserTransition.isEpsilon()) { // Recurse through any epsilon transitionsStr
                TransitionWrapper transWrapper = new TransitionWrapper(parserState, parserTransition);
                if (visitedTransitions.contains(transWrapper)) {
                    continue;
                }
                visitedTransitions.add(transWrapper);
                try {
                    if (isParseableWithAddedToken(parserTransition.target, newToken, visitedTransitions)) {
                        return true;
                    }
                } finally {
                    visitedTransitions.remove(transWrapper);
                }
            } else if (parserTransition instanceof AtomTransition) {
                AtomTransition parserAtomTransition = (AtomTransition) parserTransition;
                int transitionTokenType = parserAtomTransition.label;
                if (transitionTokenType == newToken.getType()) {
                    return true;
                }
            } else if (parserTransition instanceof SetTransition) {
                SetTransition parserSetTransition = (SetTransition) parserTransition;
                for (int transitionTokenType : parserSetTransition.label().toList()) {
                    if (transitionTokenType == newToken.getType()) {
                        return true;
                    }
                }
            } else {
                throw new IllegalStateException("Unexpected: " + toString(parserTransition));
            }
        }
        return false;
    }

    private String toString(ATNState parserState) {
        String ruleName = this.parserRuleNames[parserState.ruleIndex];
        return ruleName + " " + parserState.getClass().getSimpleName() + " " + parserState;
    }

    private String toString(Transition t) {
        String nameOrLabel = t.getClass().getSimpleName();
        if (t instanceof AtomTransition) {
            nameOrLabel += ' ' + this.createLexer().getVocabulary().getDisplayName(((AtomTransition) t).label);
        }
        return nameOrLabel + " -> " + toString(t.target);
    }

    private String transitionsStr(ATNState state) {
        Stream<Transition> transitionsStream = Arrays.asList(state.getTransitions()).stream();
        List<String> transitionStrings = transitionsStream.map(this::toString).collect(Collectors.toList());
        return StringUtils.join(transitionStrings, ", ");
    }

    private static CharStream toCharStream(String text) {
        CharStream inputStream;
        try {
            inputStream = CharStreams.fromReader(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected while reading input string", e);
        }
        return inputStream;
    }

    private Lexer createLexerWithUntokenizedTextDetection() {
        Lexer lexer = createLexer();
        lexer.removeErrorListeners();
        ANTLRErrorListener newErrorListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) throws ParseCancellationException {
                untokenizedText = input.substring(charPositionInLine); // intended side effect
            }
        };
        lexer.addErrorListener(newErrorListener);
        return lexer;
    }

    private Lexer createLexer() {
        return createLexer(this.input);
    }

    private Lexer createLexer(String lexerInput) {
        return this.lexerAndParserFactory.createLexer(toCharStream(lexerInput));
    }
}
