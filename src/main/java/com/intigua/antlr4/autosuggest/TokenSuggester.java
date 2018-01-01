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

    public Collection<String> suggest(List<Integer> nextParserTransitionLabels, String remainingText) {
        logger.debug("Suggesting tokens for rule numbers: {}", nextParserTransitionLabels);
        this.origPartialToken = remainingText;
        for (int nextParserTransitionLabel : nextParserTransitionLabels) {
            int nextTokenRuleNumber = nextParserTransitionLabel - 1; // Count from 0 not from 1
            ATNState lexerState = findLexerStateByRuleNumber(nextTokenRuleNumber);
            suggest("", lexerState, remainingText);
        }
        return suggestions;
    }
    
    private ATNState findLexerStateByRuleNumber(int ruleNumber) {
        return lexer.getATN().ruleToStartState[ruleNumber];
    }

    private void suggest(String tokenSoFar, ATNState lexerState, String remainingText) {
        logger.debug(
                "SUGGEST: tokenSoFar=" + tokenSoFar + " remainingText=" + remainingText + " lexerState=" + toString(lexerState));
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

    private String toString(ATNState lexerState) {
        String ruleName = this.lexer.getRuleNames()[lexerState.ruleIndex];
        return ruleName + " " + lexerState.getClass().getSimpleName() + " " + lexerState;
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

    private void suggestViaNonEpsilonLexerTransition(String tokenSoFar, String remainingText,
            String newTokenChar, ATNState targetState) {
        String newRemainingText = (remainingText.length() > 0) ? remainingText.substring(1) : remainingText;
        suggest(tokenSoFar + newTokenChar, targetState, newRemainingText);
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
