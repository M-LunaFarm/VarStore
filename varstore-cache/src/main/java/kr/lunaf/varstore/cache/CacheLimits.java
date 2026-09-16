package kr.lunaf.varstore.cache;

/** Includes loading reservations and entry metadata, not just living values. */
public record CacheLimits(int maxEntries, long maxBytes) {
    public CacheLimits {
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("Cache limits must be positive");
    }
}
