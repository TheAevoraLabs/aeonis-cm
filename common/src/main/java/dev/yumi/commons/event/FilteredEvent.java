package dev.yumi.commons.event;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FilteredEvent<I, T, C> {
    private final Class<T> callbackClass;
    private final Class<C> contextClass;
    private final List<T> globalListeners = new ArrayList<>();
    private final Map<C, List<T>> contextListeners = new ConcurrentHashMap<>();
    private I defaultPhase;

    public FilteredEvent(Class<T> callbackClass, Class<C> contextClass) {
        this.callbackClass = callbackClass;
        this.contextClass = contextClass;
    }

    public void register(T callback) {
        this.globalListeners.add(callback);
    }

    public void register(I phase, T callback) {
        this.globalListeners.add(callback);
    }

    public void addPhaseOrdering(I first, I second) {
        this.defaultPhase = first;
    }

    public I defaultPhaseId() {
        return defaultPhase;
    }

    public ContextualizedEvent<I, T, C> forContext(C context) {
        return forContext(context, false);
    }

    @SuppressWarnings("unchecked")
    public ContextualizedEvent<I, T, C> forContext(C context, boolean replace) {
        if (replace) {
            contextListeners.remove(context);
        }
        return new ContextualizedEvent<>() {
            @Override
            public T invoker() {
                return (T) Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        for (T listener : new ArrayList<>(globalListeners)) {
                            method.invoke(listener, args);
                        }
                        List<T> ctx = contextListeners.get(context);
                        if (ctx != null) {
                            for (T listener : new ArrayList<>(ctx)) {
                                method.invoke(listener, args);
                            }
                        }
                        return null;
                    }
                );
            }

            @Override
            public void register(T callback) {
                contextListeners.computeIfAbsent(context, k -> new ArrayList<>()).add(callback);
            }

            @Override
            public void register(I phase, T callback) {
                register(callback);
            }
        };
    }
}
