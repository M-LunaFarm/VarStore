package kr.lunaf.varstore.paper;

import java.util.Locale;
import java.util.Optional;
import kr.lunaf.varstore.api.AuditContext;
import kr.lunaf.varstore.api.Target;
import kr.lunaf.varstore.api.TransactionPlan;
import kr.lunaf.varstore.api.VersionToken;

/** Constructs exactly the immutable, audited operation shown in a confirmation preview. */
final class AdminPlan {
    private AdminPlan() { }
    static <T> TransactionPlan create(String actor, String action, Target<T> target, Optional<VersionToken> expected, T value) {
        TransactionPlan.Builder builder = TransactionPlan.builder();
        if (expected.isPresent()) builder.requireVersion(target, expected.get()); else builder.requireAbsent(target);
        switch (action) {
            case "set" -> builder.set(target, value);
            case "delete" -> builder.delete(target);
            default -> throw new IllegalArgumentException("Unsupported administrator action");
        }
        return builder.build().withAudit(new AuditContext(actor, action.toUpperCase(Locale.ROOT)));
    }
}
