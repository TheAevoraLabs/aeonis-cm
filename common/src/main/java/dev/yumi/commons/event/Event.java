package dev.yumi.commons.event;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;

public class Event<I, T> {
    protected final Class<T> type;
    protected final List<T> listeners = new ArrayList<>();
    private T invoker;

    @SuppressWarnings("unchecked")
    public Event(Class<T> type) {
        this.type = type;
        this.invoker = (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> {
                for (T listener : new ArrayList<>(listeners)) {
                    method.invoke(listener, args);
                }
                return null;
            }
        );
    }

    public void register(T listener) {
        this.listeners.add(listener);
    }

    public void register(I phase, T listener) {
        this.listeners.add(listener);
    }

    public void addPhaseOrdering(I first, I second) {}

    public I defaultPhaseId() {
        return null;
    }

    public T invoker() {
        return this.invoker;
    }
}
