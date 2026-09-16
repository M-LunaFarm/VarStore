package kr.lunaf.varstore.tools;

import kr.lunaf.varstore.api.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded offline inspection only. No database connection or write operation exists in this class. */
public final class CsvDryRun {
 public static final int MAX_BYTES=8*1024*1024,MAX_ROWS=10000;
 private static final List<String> HEADER=List.of("network_id","namespace","scope_kind","scope_id","owner_type","owner_id","key","type","value");
 private CsvDryRun(){}
 public record Report(int rows,int validRows,int invalidRows,int collisions,int existingAddresses,int existingTypeConflicts,int normalizedUuids,Map<ValueType,Integer> types,List<String> errors,List<String> samples) {
  public Report{types=Map.copyOf(types);errors=List.copyOf(errors);samples=List.copyOf(samples);}
  public boolean valid(){return invalidRows==0&&collisions==0&&existingAddresses==0;}
 }
 @FunctionalInterface public interface ExistingType {Optional<ValueType> find(Address address)throws IOException;}
 public static Report inspect(Path path)throws IOException {return inspect(path,address->Optional.empty());}
 public static Report inspect(Path path,ExistingType existing)throws IOException {
  Objects.requireNonNull(existing);
  byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_BYTES+1);}if(bytes.length>MAX_BYTES)throw new IOException("CSV exceeds 8 MiB limit");
  String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException malformed){throw new IOException("CSV must be valid UTF-8");}
  List<List<String>> rows=parse(text);if(rows.isEmpty()||!rows.removeFirst().equals(HEADER))throw new IOException("CSV header must be "+String.join(",",HEADER));
  Set<Address> addresses=new HashSet<>();Map<ValueType,Integer> types=new EnumMap<>(ValueType.class);List<String> errors=new ArrayList<>(),samples=new ArrayList<>();int invalid=0,collisions=0,valid=0,normalized=0,existingAddresses=0,existingTypeConflicts=0;
  for(int i=0;i<rows.size();i++) {
   List<String> row=rows.get(i);
   try {
    if(row.size()!=9)throw new IllegalArgumentException("COLUMN_COUNT");
    String ownerId=row.get(5);boolean ownerNormalized=false;
    if(row.get(4).equals("PLAYER")){String canonical=uuid(ownerId).toString();ownerNormalized=!canonical.equals(ownerId);ownerId=canonical;}
    Address address=new Address(row.get(0),row.get(1),ScopeKind.valueOf(row.get(2)),row.get(3),new Owner(row.get(4),ownerId),row.get(6));
    ValueType type=ValueType.valueOf(row.get(7));String raw=row.get(8);Object value=switch(type){case STRING->raw;case LONG->{if(!raw.matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException();yield Long.parseLong(raw);}case BOOLEAN->{if(!raw.equals("true")&&!raw.equals("false"))throw new IllegalArgumentException();yield Boolean.valueOf(raw);}case UUID->uuid(raw);};type.validate(value);
    if(!addresses.add(address)){collisions++;if(errors.size()<100)errors.add("row="+(i+2)+" DUPLICATE_ADDRESS");continue;}
    Optional<ValueType> stored=existing.find(address);
    if(stored.isPresent()){existingAddresses++;boolean mismatch=stored.get()!=type;if(mismatch)existingTypeConflicts++;if(errors.size()<100)errors.add("row="+(i+2)+(mismatch?" EXISTING_TYPE_CONFLICT":" EXISTING_ADDRESS"));}
    valid++;types.merge(type,1,Integer::sum);if(ownerNormalized)normalized++;if(type==ValueType.UUID&&!value.toString().equals(raw))normalized++;
    if(samples.size()<10)samples.add("row="+(i+2)+" addressHash="+hash(address)+" type="+type+" encodedBytes="+type.encodedBytes(value)+" value=[REDACTED]");
   }catch(IllegalArgumentException|VarStoreException failure){invalid++;if(errors.size()<100)errors.add("row="+(i+2)+" INVALID_ADDRESS_TYPE_OR_VALUE");}
  }
  return new Report(rows.size(),valid,invalid,collisions,existingAddresses,existingTypeConflicts,normalized,types,errors,samples);
 }
 private static UUID uuid(String text){if(!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(text);}
 private static String hash(Address address){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\u0000",address.fields()).getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static List<List<String>> parse(String text)throws IOException {
  List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,closed=false,started=false;
  for(int i=0;i<text.length();i++){
   char ch=text.charAt(i);
   if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}else field.append(ch);continue;}
   if(ch=='"'){if(started||closed)throw new IOException("Unexpected CSV quote");quoted=true;started=true;continue;}
   if(ch==','||ch=='\n'||ch=='\r'){
    row.add(field.toString());field.setLength(0);started=false;closed=false;if(row.size()>9)throw new IOException("CSV has too many columns");
    if(ch!=','){if(ch=='\r'){if(i+1>=text.length()||text.charAt(i+1)!='\n')throw new IOException("Bare CR is unsupported");i++;}rows.add(List.copyOf(row));row.clear();if(rows.size()>MAX_ROWS+1)throw new IOException("CSV exceeds 10000 rows");}continue;
   }
   if(closed)throw new IOException("Characters after CSV closing quote");started=true;field.append(ch);
  }
  if(quoted)throw new IOException("Unclosed CSV quote");if(started||closed||!row.isEmpty()){row.add(field.toString());rows.add(List.copyOf(row));}if(rows.size()>MAX_ROWS+1)throw new IOException("CSV exceeds 10000 rows");return rows;
 }
}
