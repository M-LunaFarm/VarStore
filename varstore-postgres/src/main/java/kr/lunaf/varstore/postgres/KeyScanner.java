package kr.lunaf.varstore.postgres;
import kr.lunaf.varstore.api.*;
import java.io.*;
import java.sql.*;
import java.util.*;

final class KeyScanner {
 private KeyScanner(){}
 static KeyPage scan(Connection c,OutboxRepository.Sql sql,Address scope,String prefix,Optional<String> cursor,int limit,UUID epoch,long deadline)throws SQLException {
  if(prefix==null||!prefix.matches("[a-z0-9._/-]{0,128}")||limit<1||limit>200)throw invalid();
  Objects.requireNonNull(cursor);String after=cursor.map(encoded->decode(encoded,scope,prefix,epoch)).orElse("");
  List<KeyMetadata> values=new ArrayList<>();
  String query="SELECT variable_key,value_type,generation,revision FROM vs_variables WHERE network_id=? AND namespace=? AND scope_kind=? AND scope_id=? AND owner_type=? AND owner_id=? AND NOT deleted AND variable_key LIKE ? ESCAPE '!' AND variable_key>? ORDER BY variable_key COLLATE \"C\" LIMIT ?";
  try(var statement=sql.prepare(c,query,deadline)) {
   String[] fields=scope.fields();for(int i=0;i<6;i++)statement.setString(i+1,fields[i]);statement.setString(7,prefix.replace("_","!_")+"%");statement.setString(8,after);statement.setInt(9,limit+1);
   try(var rows=statement.executeQuery()){while(rows.next())values.add(new KeyMetadata(rows.getString(1),ValueType.valueOf(rows.getString(2)),new VersionToken(epoch,rows.getObject(3,UUID.class),rows.getLong(4))));}
  }
  boolean more=values.size()>limit;if(more)values.removeLast();
  return new KeyPage(values,more?Optional.of(encode(scope,prefix,values.getLast().key(),epoch)):Optional.empty());
 }
 private static String encode(Address scope,String prefix,String last,UUID epoch) {
  try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)) {
   out.writeInt(1);String[] fields=scope.fields();for(int i=0;i<6;i++)out.writeUTF(fields[i]);out.writeUTF(prefix);out.writeUTF(last);out.writeLong(epoch.getMostSignificantBits());out.writeLong(epoch.getLeastSignificantBits());out.flush();return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
  }catch(IOException impossible){throw new UncheckedIOException(impossible);}
 }
 private static String decode(String encoded,Address scope,String prefix,UUID epoch) {
  if(encoded.length()>4096)throw invalid();
  try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded)))) {
   if(in.readInt()!=1)throw invalid();String[] fields=scope.fields();for(int i=0;i<6;i++)if(!fields[i].equals(in.readUTF()))throw invalid();if(!prefix.equals(in.readUTF()))throw invalid();String last=in.readUTF();UUID tokenEpoch=new UUID(in.readLong(),in.readLong());
   if(!epoch.equals(tokenEpoch))throw new VarStoreException(ErrorCode.STALE_EPOCH,"Cursor belongs to an older storage epoch");
   if(in.available()!=0||!last.matches("[a-z0-9._/-]{1,128}")||!last.startsWith(prefix))throw invalid();return last;
  }catch(IOException|IllegalArgumentException malformed){throw invalid();}
 }
 private static VarStoreException invalid(){return new VarStoreException(ErrorCode.INVALID_ARGUMENT,"Invalid owner-scoped scan, prefix, page size or cursor");}
}
