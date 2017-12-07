package com.intigua.antlr4.autosuggest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given an ATN state and the lexer ATN, suggests auto-completion texts.
 */
class TokenSuggester {
    private static final Logger logger = LoggerFactory.getLogger(TokenSuggester.class);

    private final Lexer lexer;
    private final Set<String> suggestions = new TreeSet<String>();
    private final List<Integer> visitedLexerStates = new ArrayList<>();
    private String origPartialToken;

    public TokenSuggester(Lexer lexer) {
        this.lexer = lexer;
    }

    public Collection<String> suggest(ATNState parserState, String remainingText) {
        this.origPartialToken = remainingText;
        ATNState lexerState = toLexerState(parserState);
        if (lexerState == null) {
            return suggestions;
        } else if (lexerState.getTransitions().length == 0) { // at end of token
            suggestViaParserTransition(parserState, remainingText);
        } else {
            suggest("", lexerState, remainingText);
        }
        return suggestions;
    }

    private ATNState toLexerState(ATNState parserState) {
        int stateIndexInLexerAtn = lexer.getATN().states.indexOf(parserState);
        if (stateIndexInLexerAtn < 0) {
            logger.debug("No lexer state matches parser state " + parserState + ", not suggesting completions.");
            return null;
        }
        ATNState lexerState = lexer.getATN().states.get(stateIndexInLexerAtn);
        return lexerState;
    }

    private void suggest(String tokenSoFar, ATNState lexerState, String remainingText) {
        logger.debug(
                "SUGGEST: tokenSoFar=" + tokenSoFar + " remainingText=" + remainingText + " lexerState=" + lexerState);
        if (visitedLexerStates.contains(lexerState.stateNumber)) {
            return; // avoid infinite loop and stack overflow
        }
        visitedLexerStates.add(lexerState.stateNumber);
        try {
            Transition[] transitions = lexerState.getTransitions();
            boolean tokenNotEmpty = tokenSoFar.length() > 0;
            boolean noMoreCharactersInToken = (transitions.length == 0);
            if (tokenNotEmpty && noMoreCharactersInToken) {
                addSuggestedToken(tokenSoFar);
                return;
            }
            for (Transition trans : transitions) {
                suggestViaLexerTransition(tokenSoFar, remainingText, trans);
            }
        } finally {
            visitedLexerStates.remove(visitedLexerStates.size() - 1);
        }
    }

    private void suggestViaLexerTransition(String tokenSoFar, String remainingText, Transition trans) {
        if (trans.isEpsilon()) {
            suggest(tokenSoFar, trans.target, remainingText);
        } else if (trans instanceof AtomTransition) {
            String newTokenChar = getAddedTextFor((AtomTransition) trans);
            if (remainingText.isEmpty() || remainingText.startsWith(newTokenChar)) {
                logger.debug("LEXER TOKEN: " + newTokenChar + " remaining=" + remainingText);
                suggestViaNonEpsilonLexerTransition(tokenSoFar, remainingText, newTokenChar, trans.target);
            } else {
                logger.debug("NONMATCHING LEXER TOKEN: " + newTokenChar + " remaining=" + remainingText);
            }
        } else if (trans instanceof SetTransition) {
            List<Integer> symbols = ((SetTransition) trans).label().toList();
            for (Integer symbol : symbols) {
                String newTokenChar = new String(Character.toChars(symbol));
                if (remainingText.isEmpty() || remainingText.startsWith(newTokenChar)) {
                    suggestViaNonEpsilonLexerTransition(tokenSoFar, remainingText, newTokenChar, trans.target);
                }
            }
        }
    }

    private void suggestViaNonEpsilonLexerTransition(String tokenSoFar, String remainingText, String newTokenChar,
            ATNState targetState) {
        String newRemainingText = (remainingText.length() > 0) ? remainingText.substring(1) : remainingText;
        suggest(tokenSoFar + newTokenChar, targetState, newRemainingText);
    }

    private void suggestViaParserTransition(ATNState parserState, String remainingText) {
        for (Transition transition : parserState.getTransitions()) {
            if (transition.isEpsilon()) {
                suggestViaParserTransition(transition.target, remainingText);
            } else if (transition instanceof AtomTransition) {
                ATNState lexerState = toLexerState(((AtomTransition) transition).target);
                suggest("", lexerState, remainingText);
            }
        }
    }

    private void addSuggestedToken(String tokenToAdd) {
        String justTheCompletionPart = chopOffCommonStart(tokenToAdd, this.origPartialToken);
        suggestions.add(justTheCompletionPart);
    }

    private String chopOffCommonStart(String a, String b) {
        int charsToChopOff = Math.min(b.length(), a.length());
        return a.substring(charsToChopOff);
    }

    private String getAddedTextFor(AtomTransition transition) {
        return new String(Character.toChars(transition.label));
    }
}
