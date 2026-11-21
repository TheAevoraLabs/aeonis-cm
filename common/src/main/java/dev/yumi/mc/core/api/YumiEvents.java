package dev.yumi.mc.core.api;

import dev.yumi.commons.event.Event;
import dev.yumi.commons.event.FilteredEvent;
import net.minecraft.resources.Identifier;

public final class YumiEvents {
    public static final EventFactory EVENTS = new EventFactory();

    public static class EventFactory {
        public <T> Event<Identifier, T> create(Class<T> type) {
            return new Event<>(type);
        }

        public <T, C> FilteredEvent<Identifier, T, C> createFiltered(Class<T> callbackClass, Class<C> contextClass) {
            return new FilteredEvent<>(callbackClass, contextClass);
        }
    }
}
