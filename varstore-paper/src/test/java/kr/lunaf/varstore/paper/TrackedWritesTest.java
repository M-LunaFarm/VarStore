package kr.lunaf.varstore.paper;

import java.util.UUID;
import java.util.concurrent.*;
import kr.lunaf.varstore.api.Outcome;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackedWritesTest {
    private static PaperSessions.Session session() { return new PaperSessions.Session(UUID.randomUUID(), UUID.randomUUID()); }
    @Test void freezeWaitsOnlyForSubmittedWorkAndRejectsNewWork() {
        TrackedWrites writes = new TrackedWrites(2); var session = session();
        var first = new CompletableFuture<Outcome>(); var second = new CompletableFuture<Outcome>();
        writes.submit(session, () -> first, value -> value);
        writes.submit(session, () -> second, value -> value);
        var drain = writes.freeze(session).toCompletableFuture();
        assertFalse(drain.isDone());
        assertThrows(CompletionException.class, () -> writes.submit(session, () -> CompletableFuture.completedFuture(Outcome.APPLIED), value -> value).toCompletableFuture().join());
        first.complete(Outcome.APPLIED); assertFalse(drain.isDone());
        second.complete(Outcome.CONDITION_FAILED);
        assertEquals(new TrackedWrites.Drain(1, 0, 1), drain.join());
    }
    @Test void completedFailureIsRetainedAndCannotBecomeSuccessfulDrain() {
        TrackedWrites writes = new TrackedWrites(2); var session = session();
        writes.submit(session, () -> CompletableFuture.<Outcome>failedFuture(new IllegalStateException("uncertain")), value -> value);
        assertThrows(CompletionException.class, () -> writes.freeze(session).toCompletableFuture().join());
        var replacement = new PaperSessions.Session(session.playerId(), UUID.randomUUID());
        assertEquals(new TrackedWrites.Drain(0, 0, 0), writes.freeze(replacement).toCompletableFuture().join());
    }
    @Test void closeFailsOutstandingDrainWithoutCancellingDatabaseWork() {
        TrackedWrites writes = new TrackedWrites(1); var session = session(); var database = new CompletableFuture<Outcome>();
        writes.submit(session, () -> database, value -> value); var drain = writes.freeze(session).toCompletableFuture();
        writes.close(); assertThrows(CompletionException.class, drain::join); assertFalse(database.isCancelled());
        database.complete(Outcome.APPLIED);
    }
}
