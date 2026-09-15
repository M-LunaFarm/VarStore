package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Versioned, bounded primitive encoding. No object deserialization or ambiguous delimiters. */
final class ProtocolCodec {
    private ProtocolCodec() {}
    static byte[] fingerprint(String kind, TransactionPlan plan) {
        byte[] bytes = encode(out -> {
            out.writeInt(1); string(out,kind);
            out.writeInt(plan.conditions().size());
            for (Condition condition : plan.conditions()) {
                target(out,condition.target()); string(out,condition.kind().name()); version(out,condition.version());
                out.writeLong(condition.minimum()); out.writeLong(condition.maximum());
            }
            out.writeInt(plan.mutations().size());
            for (Mutation mutation : plan.mutations()) {
                target(out,mutation.target()); string(out,mutation.kind().name()); value(out,mutation.value()); out.writeLong(mutation.delta());
            }
            out.writeBoolean(plan.audit().isPresent());
            if(plan.audit().isPresent()) { string(out,plan.audit().get().actor()); string(out,plan.audit().get().action()); }
        });
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static byte[] receipt(TransactionReceipt receipt) {
        return encode(out -> {
            out.writeInt(1); uuid(out,receipt.operationId()); string(out,receipt.outcome().name()); out.writeInt(receipt.results().size());
            for(var entry:receipt.results().entrySet()) {
                address(out,entry.getKey()); WriteReceipt<?> item=entry.getValue();
                string(out,item.outcome().name()); version(out,item.version().orElse(null)); value(out,item.value().orElse(null));
            }
        });
    }
    static TransactionReceipt receipt(byte[] bytes, boolean replayed) {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=1) throw new IOException("Unknown receipt codec version");
            UUID operation=uuid(in); Outcome outcome=Outcome.valueOf(string(in)); int count=in.readInt();
            if(count<0 || count>16) throw new IOException("Invalid result count");
            Map<Address,WriteReceipt<?>> results=new LinkedHashMap<>();
            for(int i=0;i<count;i++) {
                Address address=address(in); Outcome result=Outcome.valueOf(string(in));
                results.put(address,new WriteReceipt<>(operation,result,Optional.ofNullable(version(in)),Optional.ofNullable(value(in)),replayed));
            }
            if(in.available()!=0) throw new IOException("Trailing receipt data");
            return new TransactionReceipt(operation,outcome,results,replayed);
        } catch(IOException | IllegalArgumentException error) { throw new IllegalStateException("Stored receipt is invalid",error); }
    }
    static Comparator<Address> addressOrder() {
        return Comparator.naturalOrder();
    }
    private static void target(DataOutputStream out,Target<?> target) throws IOException { address(out,target.address());string(out,target.type().name()); }
    private static void address(DataOutputStream out,Address address) throws IOException {
        string(out,address.networkId());string(out,address.namespace());string(out,address.scopeKind().name());string(out,address.scopeId());string(out,address.owner().type());string(out,address.owner().id());string(out,address.key());
    }
    private static Address address(DataInputStream in) throws IOException { return new Address(string(in),string(in),ScopeKind.valueOf(string(in)),string(in),new Owner(string(in),string(in)),string(in)); }
    private static void version(DataOutputStream out,VersionToken token) throws IOException {
        out.writeBoolean(token!=null);if(token!=null){uuid(out,token.storageEpoch());uuid(out,token.generation());out.writeLong(token.revision());}
    }
    private static VersionToken version(DataInputStream in) throws IOException { return in.readBoolean()?new VersionToken(uuid(in),uuid(in),in.readLong()):null; }
    private static void uuid(DataOutputStream out,UUID uuid) throws IOException { out.writeLong(uuid.getMostSignificantBits());out.writeLong(uuid.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException {return new UUID(in.readLong(),in.readLong());}
    private static void value(DataOutputStream out,Object value) throws IOException {
        if(value==null)out.writeByte(0);
        else if(value instanceof String s){out.writeByte(1);string(out,s);}
        else if(value instanceof Long l){out.writeByte(2);out.writeLong(l);}
        else if(value instanceof Boolean b){out.writeByte(3);out.writeBoolean(b);}
        else if(value instanceof UUID u){out.writeByte(4);uuid(out,u);}
        else throw new IllegalArgumentException("Unsupported value type");
    }
    private static Object value(DataInputStream in) throws IOException {
        return switch(in.readUnsignedByte()){case 0->null;case 1->string(in);case 2->in.readLong();case 3->in.readBoolean();case 4->uuid(in);default->throw new IOException("Unsupported value tag");};
    }
    private static void string(DataOutputStream out,String value) throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in) throws IOException {int length=in.readInt();if(length<0||length>65536)throw new IOException("Invalid string size");byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);}
    private interface Encoder {void encode(DataOutputStream out) throws IOException;}
    private static byte[] encode(Encoder encoder) {try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){encoder.encode(out);out.flush();return bytes.toByteArray();}catch(IOException error){throw new UncheckedIOException(error);}}
}
