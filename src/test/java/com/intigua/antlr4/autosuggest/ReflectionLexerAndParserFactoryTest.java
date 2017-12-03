package com.intigua.antlr4.autosuggest;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.junit.Test;

public class ReflectionLexerAndParserFactoryTest {

    static class TestGrammarLexer extends Lexer {

        public TestGrammarLexer(CharStream input) {
        }

        @Override
        public String[] getRuleNames() {
            return null;
        }

        @Override
        public String getGrammarFileName() {
            return null;
        }

        @Override
        public ATN getATN() {
            return null;
        }

    };

    static class TestGrammarParser extends Parser {

        public TestGrammarParser(TokenStream tokenStream) {
            super(tokenStream);
        }

        @Override
        public String[] getTokenNames() {
            return null;
        }

        @Override
        public String[] getRuleNames() {
            return null;
        }

        @Override
        public String getGrammarFileName() {
            return null;
        }

        @Override
        public ATN getATN() {
            return null;
        }

    }

    @Test
    public void create_succeeds() {
        LexerAndParserFactory factory = new ReflectionLexerAndParserFactory(TestGrammarLexer.class,
                TestGrammarParser.class);
        Lexer createdLexer = factory.createLexer(null);
        Parser createdParser = factory.createParser(null);
        assertThat(createdLexer, instanceOf(TestGrammarLexer.class));
        assertThat(createdParser, instanceOf(TestGrammarParser.class));
    }
}
