package kr.lunaf.varstore.api.events;
import java.time.Instant;
import java.util.*;
/** Acknowledgments require this exact lease token; redelivery retains event identity. */
public record Delivery(ChangeEvent event,UUID leaseToken,int attempt,Instant leaseUntil) {
 public Delivery {Objects.requireNonNull(event);Objects.requireNonNull(leaseToken);Objects.requireNonNull(leaseUntil);if(attempt<1)throw new IllegalArgumentException("Invalid attempt");}
}
