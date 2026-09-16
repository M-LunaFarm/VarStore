package kr.lunaf.varstore.api.events;
import java.time.Instant;
import java.util.*;
/** Session-fenced handle; RESYNC_REQUIRED must be explicitly reset before rebuilding consumer state. */
public record SubscriptionState(String subscriberId,UUID sessionToken,UUID storageEpoch,Instant leaseUntil,boolean resyncRequired) {
 public SubscriptionState {if(subscriberId==null||!subscriberId.matches("[a-z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid subscriber");Objects.requireNonNull(sessionToken);Objects.requireNonNull(storageEpoch);Objects.requireNonNull(leaseUntil);}
}
