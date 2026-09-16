package kr.lunaf.varstore.api.events;
import kr.lunaf.varstore.api.*;
import java.time.Instant;
import java.util.*;
/** Invalidates an address; carries no value and is not a game-item delivery instruction. */
public record ChangeEvent(long eventId,UUID operationId,Address address,VersionToken version,ChangeKind kind,String sourceServer,Instant createdAt) {
 public ChangeEvent {if(eventId<1)throw new IllegalArgumentException("Invalid event ID");Objects.requireNonNull(operationId);Objects.requireNonNull(address);Objects.requireNonNull(version);Objects.requireNonNull(kind);Objects.requireNonNull(createdAt);if(sourceServer==null||!sourceServer.matches("[a-z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid source server");}
}
