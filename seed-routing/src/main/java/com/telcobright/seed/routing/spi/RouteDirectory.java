package com.telcobright.seed.routing.spi;

/**
 * What the router must know about the product's outgoing routes — and nothing more: does a route of this name
 * exist, and is it UP now. The product answers from its own live table (routesphere: the channel's
 * {@code WeightedStatusBasedRoute}s; pay-sphere: its {@code RouteTable}). Must be cheap and thread-safe.
 */
public interface RouteDirectory {
    boolean exists(String route);
    boolean isUp(String route);

    /** Every route is known and UP — for tests and for a product without health probes. */
    RouteDirectory ALL_UP = new RouteDirectory() {
        @Override public boolean exists(String route) { return true; }
        @Override public boolean isUp(String route) { return true; }
    };
}
