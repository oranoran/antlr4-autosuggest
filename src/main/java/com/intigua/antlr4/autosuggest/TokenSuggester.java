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

    public TokenSuggester(Lexer lexer) {
        this.lexer = lexer;
    }

    public Collection<String> suggest(ATNState parserState, String remainingText) {
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

    private void suggest(String completionSoFar, ATNState lexerState, String remainingText) {
        if (visitedLexerStates.contains(lexerState.stateNumber)) {
            return; // avoid infinite loop and stack overflow
        }
        visitedLexerStates.add(lexerState.stateNumber);
        try {
            Transition[] transitions = lexerState.getTransitions();
            if (transitions.length == 0 && completionSoFar.length() > 0) {
                suggestions.add(completionSoFar);
                return;
            }
            for (Transition trans : transitions) {
                suggestViaLexerTransition(completionSoFar, remainingText, trans);
            }
        } finally {
            visitedLexerStates.remove(visitedLexerStates.size() - 1);
        }
    }

    private void suggestViaLexerTransition(String completionSoFar, String remainingText, Transition trans) {
        if (trans.isEpsilon()) {
            suggest(completionSoFar, trans.target, remainingText);
        } else if (trans instanceof AtomTransition) {
            String transitionToken = getAddedTextFor((AtomTransition) trans);
            if (transitionToken.startsWith(remainingText)) {
                suggestViaNonEpsilonLexerTransition(completionSoFar, remainingText, transitionToken, trans.target);
            }
        } else if (trans instanceof SetTransition) {
            List<Integer> symbols = ((SetTransition) trans).label().toList();
            for (Integer symbol : symbols) {
                String transitionToken = new String(Character.toChars(symbol));
                if (transitionToken.startsWith(remainingText)) {
                    suggestViaNonEpsilonLexerTransition(completionSoFar, remainingText, transitionToken, trans.target);
                }
            }
        }
    }

    private void suggestViaNonEpsilonLexerTransition(String completionSoFar, String remainingText,
            String transitionToken, ATNState targetState) {
        String newTransitionToken = chopOffCommonStart(transitionToken, remainingText);
        String newRemainingText = chopOffCommonStart(remainingText, transitionToken);
        suggest(completionSoFar + newTransitionToken, targetState, newRemainingText);
    }

    private void suggestViaParserTransition(ATNState parserState, String remainingText) {
        for (Transition transition : parserState.getTransitions()) {
            if (transition.isEpsilon()) {
                suggestViaParserTransition(transition.target, remainingText);
            }
            else if (transition instanceof AtomTransition) {
                ATNState lexerState = toLexerState(((AtomTransition) transition).target);
                suggest("", lexerState, remainingText);
            }
        }
    }

    private String chopOffCommonStart(String remainingText, String transitionToken) {
        int charsToChopOff = Math.min(transitionToken.length(), remainingText.length());
        return remainingText.substring(charsToChopOff);
    }

    private String getAddedTextFor(AtomTransition transition) {
        return new String(Character.toChars(transition.label));
    }
}
