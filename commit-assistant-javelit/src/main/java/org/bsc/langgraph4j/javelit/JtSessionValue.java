package org.bsc.langgraph4j.spring.ai.commit.javelit;

import io.javelit.core.Jt;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public record JtSessionValue<T>(String key )  {

    @Override
    public String key() {
        return key;
    }

    @SuppressWarnings("unchecked")
    public Optional<T> value() {
        return Optional.ofNullable( (T) Jt.sessionState().get( key() ));
    }

    @SuppressWarnings("unchecked")
    public T computeIfAbsent( Function<String, T> getter ) {
        return (T)Jt.sessionState().computeIfAbsent( key(), getter);
    }

    public void setValue( T value ) {
        Jt.sessionState().put( key(), value );
    }

    @SuppressWarnings("unchecked")
    public Optional<T> clear() {
        return Optional.ofNullable((T)Jt.sessionState().remove( key() ));
    }

    public <R> R ifNotPresentOrElseGet(Function<JtSessionValue<T>, R> eval, Supplier<R> defaultValue ) {
        if(value().isEmpty()) {
            return eval.apply(this);
        }
        return defaultValue.get();
    }

    public <R> R ifNotPresentOrElse(Function<JtSessionValue<T>, R> eval, R defaultValue ) {
        if(value().isEmpty()) {
            return eval.apply(this);
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "SessionValue{key='%s', value=%s}".formatted(key, value().orElse(null));
    }

}
