package kr.lunaf.varstore.api;
import java.util.*;
/** Owner-scoped keyset page. Separate pages are separate database snapshots. */
public record KeyPage(List<KeyMetadata> keys,Optional<String> nextCursor) {
 public KeyPage {keys=List.copyOf(keys);Objects.requireNonNull(nextCursor);if(keys.size()>200)throw new IllegalArgumentException("Page exceeds200keys");}
}
