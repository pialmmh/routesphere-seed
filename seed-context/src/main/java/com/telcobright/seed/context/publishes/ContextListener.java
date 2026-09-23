package com.telcobright.seed.context.publishes;

@FunctionalInterface
public interface ContextListener {
    void on(ContextEvent event);

    ContextListener NONE = event -> { };
}
