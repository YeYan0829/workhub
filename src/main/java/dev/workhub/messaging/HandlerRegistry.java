package dev.workhub.messaging;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HandlerRegistry {
    private final Map<String, WorkHandler> handlers = new HashMap<>();

    public HandlerRegistry(List<WorkHandler> handlers) {
        for (var handler : handlers)
            if (this.handlers.putIfAbsent(handler.kind(), handler) != null)
                throw new IllegalArgumentException("Duplicate handler: " + handler.kind());
    }

    public WorkHandler require(String kind) {
        var handler = handlers.get(kind);
        if (handler == null) throw new WorkFailure(false, "UNKNOWN_HANDLER");
        return handler;
    }
}
