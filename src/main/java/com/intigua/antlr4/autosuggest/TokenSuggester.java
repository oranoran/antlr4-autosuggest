package com.intigua.antlr4.autosuggest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AtomTransition;
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
        } else if (lexerState.getTransitions().length == 0) {
            for (Transition transiton : parserState.getTransitions()) {
                if (transiton instanceof AtomTransition) {
                    lexerState = toLexerState(((AtomTransition) transiton).target);
                    suggest("", lexerState, remainingText);
                }
            }
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

    private void suggest(String completionSoFar, ATNState state, String remainingText) {
        if (visitedLexerStates.contains(state.stateNumber)) {
            return; // avoid infinite loop and stack overflow
        }
        visitedLexerStates.add(state.stateNumber);
        try {
            Transition[] transitions = state.getTransitions();
            if (transitions.length == 0 && completionSoFar.length() > 0) {
                suggestions.add(completionSoFar);
                return;
            }
            for (Transition trans : transitions) {
                if (trans.isEpsilon()) {
                    suggest(completionSoFar, trans.target, remainingText);
                } else if (trans instanceof AtomTransition) {
                    String transitionToken = getAddedTextFor((AtomTransition) trans);
                    if (transitionToken.startsWith(remainingText)) {
                        String newTransitionToken = chopOffCommonStart(transitionToken, remainingText);
                        String newRemainingText = chopOffCommonStart(remainingText, transitionToken);
                        suggest(completionSoFar + newTransitionToken, trans.target, newRemainingText);
                    }
                }
            }
        } finally {
            visitedLexerStates.remove(visitedLexerStates.size() - 1);
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
