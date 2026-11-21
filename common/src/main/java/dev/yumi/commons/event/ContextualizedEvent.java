package dev.yumi.commons.event;

public interface ContextualizedEvent<I, T, C> {
    T invoker();
    void register(T callback);
    void register(I phase, T callback);
}
