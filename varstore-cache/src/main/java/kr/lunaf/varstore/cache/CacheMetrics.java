package kr.lunaf.varstore.cache;

/** Current budget use and cumulative observations for this shared cache service. */
public record CacheMetrics(int handles, int entries, long retainedBytes, int loading,
                           long hits, long misses, long loads, long coalesced,
                           long invalidations, long discardedLoads, long evictions,
                           long failures, long rejectedLoads) {}
