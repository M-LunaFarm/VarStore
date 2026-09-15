package kr.lunaf.varstore.api;

/** Aggregate bounded-cardinality measurements. Unavailable gauges use -1. */
public record StoreMetrics(long requests, long succeeded, long conditionFailures,
                           long storageErrors, long replays, long unknownOutcomes,
                           int queuedRequests, long queuedBytes, int activeConnections,
                           long p50Micros, long p95Micros, long p99Micros,
                           long queueWaitMicros, long lockWaitMicros,
                           long tableBytes, long indexBytes) {
    public static StoreMetrics empty() { return new StoreMetrics(0,0,0,0,0,0,0,0,0,0,0,0,0,-1,-1,-1); }
}
