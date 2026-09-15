package kr.lunaf.varstore.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.Test;

class AdminPlanTest {
    private static final Target<String> TARGET = Target.of(new Address("test", "smoke", ScopeKind.NETWORK, "_", Owner.system("global"), "text"), VarKey.stringKey("text"));
    @Test void newValueProducesValidAuditedConditionalPlanFromLowercaseCommand() {
        var plan = AdminPlan.create("console", "set", TARGET, Optional.empty(), "new");
        assertEquals("SET", plan.audit().orElseThrow().action());
        assertEquals(Condition.Kind.ABSENT, plan.conditions().getFirst().kind());
        assertEquals("new", plan.mutations().getFirst().value());
    }
    @Test void deleteRetainsExactPreviewVersionAndActor() {
        var version = new VersionToken(UUID.randomUUID(), UUID.randomUUID(), 12);
        var plan = AdminPlan.create("console", "delete", TARGET, Optional.of(version), null);
        assertEquals(new AuditContext("console", "DELETE"), plan.audit().orElseThrow());
        assertEquals(version, plan.conditions().getFirst().version());
        assertEquals(Mutation.Kind.DELETE, plan.mutations().getFirst().kind());
    }
    @Test void invalidValueCannotReachConfirmation() {
        assertThrows(VarStoreException.class, () -> AdminPlan.create("console", "set", TARGET, Optional.empty(), "x".repeat(16385)));
        assertThrows(IllegalArgumentException.class, () -> AdminPlan.create("console", "force", TARGET, Optional.empty(), "x"));
    }
}
