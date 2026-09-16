package kr.lunaf.varstore.api;
import java.util.Objects;
/** Metadata only; listing does not expose stored values. */
public record KeyMetadata(String key,ValueType type,VersionToken version) {
 public KeyMetadata {new VarKey<>(key,type);Objects.requireNonNull(version);}
}
