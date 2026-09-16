package kr.lunaf.varstore.api.events;
import java.time.Duration;
import java.util.Objects;
/** A new subscription starts at activation; a durable ID preserves previously pending deliveries. */
public record SubscriptionSpec(String subscriberId,String namespace,SubscriptionMode mode,Duration leaseDuration,Duration retention) {
 public SubscriptionSpec {if(subscriberId==null||!subscriberId.matches("[a-z0-9._-]{1,64}")||namespace==null||!namespace.matches("[a-z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid subscriber or namespace");Objects.requireNonNull(mode);validateLease(leaseDuration);if(retention==null||retention.compareTo(Duration.ofHours(1))<0||retention.compareTo(Duration.ofDays(30))>0)throw new IllegalArgumentException("Retention must be1hour..30days");}
 public static void validateLease(Duration duration){if(duration==null||duration.compareTo(Duration.ofSeconds(10))<0||duration.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("Lease must be10seconds..10minutes");}
}
