package kr.lunaf.varstore.api;

/** ready() is a first-readiness signal; state continues to track later failures. */
public enum StoreState { STARTING, READY, DEGRADED, DRAINING, CLOSED }
