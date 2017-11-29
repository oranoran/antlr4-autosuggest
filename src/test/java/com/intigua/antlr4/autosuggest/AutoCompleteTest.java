package com.intigua.antlr4.autosuggest;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

import java.util.Arrays;
import java.util.Collection;

import org.antlr.runtime.RecognitionException;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import com.intigua.antlr4.autosuggest.AutoComplete;
import com.intigua.antlr4.autosuggest.LexerAndParserFactory;

public class AutoCompleteTest {

	private LexerAndParserFactory lexerAndParserFactory;
	private Collection<String> suggestedCompletions;

	@Test
	public void suggest_withEmpty_shouldSuggestFirstToken() {
		givenGrammar("rule: 'AB' 'CD'").whenInput("").thenExpect("AB");
	}

	@Test
	public void suggest_withSingleTokenComingUp_shouldSuggestSingleToken() {
		givenGrammar("rule: 'AB' 'CD'").whenInput("AB").thenExpect("CD");
	}

	@Test
	public void suggest_withTokenAndAHalf_shouldCompleteTheToken() {
		givenGrammar("rule: 'AB' 'CD'").whenInput("ABC").thenExpect("D");
	}

	@Test
	public void suggest_withCompleteExpression_shouldNotSuggestAnything() {
		givenGrammar("rule: 'AB' 'CD'").whenInput("ABCD").thenExpect();
	}

	@Test
	public void suggest_withParens_shouldSuggest() {
		givenGrammar("rule: ('AB') ('CD')").whenInput("AB").thenExpect("CD");
	}

	@Test
	public void suggest_withOptional_shouldSuggest() {
		givenGrammar("rule: 'A'? 'B'").whenInput("").thenExpect("A", "B");
	}

	@Test
	public void suggest_withAlternativeTokens_shouldSuggest() {
		givenGrammar("rule: 'A' | 'B'").whenInput("").thenExpect("A", "B");
	}

	@Test
	public void suggest_withAlternativeParserRules_shouldSuggest() {
		givenGrammar("rule: a | b", "a: 'A'", "b: 'B'").whenInput("").thenExpect("A", "B");
	}

	@Test
	public void suggest_withTokenRange_shouldNotSuggest() {
		givenGrammar("rule: A", "A: [A-Z]").whenInput("").thenExpect();
	}

	@Test
	public void suggest_withTokenRangeInFragment_shouldNotSuggest() {
		givenGrammar("rule: A", "fragment A: [A-Z]").whenInput("").thenExpect();
	}

	@Test
	public void suggest_afterSkippedToken_shouldSuggest() {
		givenGrammar("rule: 'A' 'B'", "WS: [ \\t] -> skip").whenInput("A ").thenExpect("B");
	}

	@Test
	public void suggest_beforeSkippedToken_shouldSuggest() {
		givenGrammar("rule: 'A' 'B'", "WS: [ \\t] -> skip").whenInput("A").thenExpect("B");
	}

	@Test
	public void suggest_whenCompletionIsWildcard_shouldNotSuggest() {
		givenGrammar("rule: A", "A: 'A'*").whenInput("").thenExpect();
	}

	@Test
	public void suggest_whenCompletionIsPlus_shouldSuggestOne() {
		givenGrammar("rule: A", "A: 'A'+").whenInput("").thenExpect("A");
	}

	@Test
	public void suggest_whenWildcardInMiddleOfToken_shouldSuggestWithoutIt() {
		givenGrammar("rule: A", "A: 'A' 'B'* 'C'").whenInput("A").thenExpect("C");
	}

	@Test
	public void suggest_whenPlusInMiddleOfToken_shouldSuggestWithOneInstance() {
		givenGrammar("rule: A", "A: 'A' 'B'+ 'C'").whenInput("A").thenExpect("BC");
	}

	@Test
	public void suggest_whenCompletionIsAFragment_shouldNotSuggest() {
		givenGrammar("rule: 'A' B", "fragment B: 'B'").whenInput("A").thenExpect();
	}

	@Test
	public void suggest_withTwoRulesOneMatching_shouldSuggestMatchingRule() {
		givenGrammar("r0: r1 | r2", "r1: 'AB'", "r2: 'CD'").whenInput("A").thenExpect("B");
	}

	@Test
	public void suggest_withTwoRulesBothMatching_shouldSuggestBoth() {
		givenGrammar("r0: r1 | r2", "r1: 'AB'", "r2: 'AC'").whenInput("A").thenExpect("B", "C");
	}

	// @Test
	// public void suggest_withMultipleParseOptions_shouldSuggestAll() {
	// // Currently failing due to weird AST created by antlr4. Parser state 11
	// // has B completion token, while lexer state 11 actually generates C.
	// givenGrammar("r0: r1 | r1; r1: ('A' 'B') 'C'", "r2: 'A' ('B'
	// 'C')").whenInput("A").thenExpect("B", "BC");
	// }

	private AutoCompleteTest givenGrammar(String... grammarLines) {
		this.lexerAndParserFactory = loadGrammar(grammarLines);
		return this;
	}

	private AutoCompleteTest whenInput(String input) {
		this.suggestedCompletions = new AutoComplete(this.lexerAndParserFactory, input).suggestCompletions();
		return this;
	}

	private void thenExpect(String... expectedCompletions) {
		assertThat(this.suggestedCompletions, containsInAnyOrder(expectedCompletions));
	}

	private LexerAndParserFactory loadGrammar(String... grammarlines) {
		String firstLine = "grammar testgrammar;\n";
		String grammarText = firstLine + StringUtils.join(Arrays.asList(grammarlines), ";\n") + ";\n";
		LexerGrammar lg;
		try {
			lg = new LexerGrammar(grammarText);
			Grammar g = new Grammar(grammarText);
			return new LexerAndParserFactory() {

				@Override
				public Parser createParser(TokenStream tokenStream) {
					return g.createParserInterpreter(tokenStream);
				}

				@Override
				public Lexer createLexer(CharStream input) {
					return lg.createLexerInterpreter(input);
				}
			};
		} catch (RecognitionException e) {
			throw new IllegalArgumentException(e);
		}
	}
}
