package kr.lunaf.varstore.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ConfirmationTokensTest {
    @Test void confirmationsBindActorAndPayloadAndAreConsumedOnce() {
        var tokens = new ConfirmationTokens<String>(Clock.systemUTC(), Duration.ofSeconds(60));
        String token = tokens.issue("console", "set key value expected-version");
        assertTrue(tokens.consume("other", token).isEmpty());
        assertEquals("set key value expected-version", tokens.consume("console", token).orElseThrow());
        assertTrue(tokens.consume("console", token).isEmpty());
    }
    @Test void expiryRejectsConfirmationAtDeadline() {
        MutableClock clock = new MutableClock();
        var tokens = new ConfirmationTokens<String>(clock, Duration.ofSeconds(60));
        String token = tokens.issue("console", "delete expected-version");
        clock.now = clock.now.plusSeconds(60);
        assertTrue(tokens.consume("console", token).isEmpty());
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
