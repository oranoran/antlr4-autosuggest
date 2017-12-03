package com.intigua.antlr4.autosuggest;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;

public class ReflectionLexerAndParserFactory implements LexerAndParserFactory {

    private final Constructor<? extends Lexer> lexerCtr;
    private final Constructor<? extends Parser> parserCtr;

    public ReflectionLexerAndParserFactory(Class<? extends Lexer> lexerClass, Class<? extends Parser> parserClass) {
        lexerCtr = getConstructor(lexerClass, Lexer.class, CharStream.class);
        parserCtr = getConstructor(parserClass, Parser.class, TokenStream.class);
    }

    @Override
    public Lexer createLexer(CharStream input) {
        return create(Lexer.class, lexerCtr, input);
    }

    @Override
    public Parser createParser(TokenStream tokenStream) {
        return create(Parser.class, parserCtr, tokenStream);
    }

    private static <T> Constructor<? extends T> getConstructor(Class<? extends T> givenClass, Class<T> targetBaseClass,
            Class<?> argClass) {
        try {
            return givenClass.getConstructor(argClass);
        } catch (NoSuchMethodException | SecurityException e) {
            throw new IllegalArgumentException(
                    givenClass.getSimpleName() + " must have constructor from " + argClass.getSimpleName() + ".");
        }
    }

    private <T> T create(Class<T> targetClass, Constructor<? extends T> contructor, Object arg) {
        try {
            return contructor.newInstance(arg);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
