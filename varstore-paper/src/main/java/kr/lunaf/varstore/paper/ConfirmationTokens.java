package kr.lunaf.varstore.paper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Bounded, one-use confirmations. Payload contains the complete conditional mutation. */
final class ConfirmationTokens<T> {
    private final Map<String, Entry<T>> tokens = new HashMap<>();
    private final Clock clock;
    private final Duration ttl;

    ConfirmationTokens(Clock clock, Duration ttl) { this.clock = clock; this.ttl = ttl; }

    synchronized String issue(String actor, T payload) {
        tokens.entrySet().removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
        if (tokens.size() >= 256) throw new IllegalStateException("Too many pending confirmations");
        String token = UUID.randomUUID().toString();
        tokens.put(token, new Entry<>(actor, payload, clock.instant().plus(ttl)));
        return token;
    }

    synchronized Optional<T> consume(String actor, String token) {
        Entry<T> entry = tokens.get(token);
        if (entry == null) return Optional.empty();
        if (!entry.expires().isAfter(clock.instant())) { tokens.remove(token); return Optional.empty(); }
        if (!entry.actor().equals(actor)) return Optional.empty();
        tokens.remove(token);
        return Optional.of(entry.payload());
    }

    private record Entry<T>(String actor, T payload, Instant expires) { }
}
