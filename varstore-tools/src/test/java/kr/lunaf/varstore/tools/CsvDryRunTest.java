package kr.lunaf.varstore.tools;

import kr.lunaf.varstore.api.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CsvDryRunTest {
 @TempDir Path temporary;
 static final String HEADER="network_id,namespace,scope_kind,scope_id,owner_type,owner_id,key,type,value\n";
 Path csv(String content)throws IOException {Path file=temporary.resolve("input.csv");Files.writeString(file,content);return file;}
 @Test void validatesPrimitivesEscapesAndCanonicalUuidWithoutExposingValues()throws Exception {
  String id="AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE";
  var report=CsvDryRun.inspect(csv(HEADER+"test,example,NETWORK,_,SYSTEM,owner,secret,STRING,\"private,\"\"secret\"\"\nnext\"\n"+"test,example,SERVER,lobby,PLAYER,"+id+",id,UUID,"+id+"\n"+"test,example,NETWORK,_,SYSTEM,owner,number,LONG,-9223372036854775808\n"+"test,example,NETWORK,_,SYSTEM,owner,flag,BOOLEAN,false\n"));
  assertTrue(report.valid());assertEquals(4,report.validRows());assertEquals(2,report.normalizedUuids());assertEquals(1,report.types().get(ValueType.STRING));assertFalse(report.toString().contains("private"));assertFalse(report.toString().contains(id));assertTrue(report.samples().getFirst().contains("[REDACTED]"));
 }
 @Test void detectsCollisionsAndRejectsAmbiguousOrInvalidTypesAndScopes()throws Exception {
  var report=CsvDryRun.inspect(csv(HEADER+"test,example,NETWORK,_,SYSTEM,owner,key,LONG,1\n"+"test,example,NETWORK,_,SYSTEM,owner,key,LONG,2\n"+"test,example,NETWORK,lobby,SYSTEM,owner,bad,LONG,3\n"+"test,example,NETWORK,_,SYSTEM,owner,flag,BOOLEAN,TRUE\n"+"test,example,NETWORK,_,SYSTEM,owner,id,UUID,1-2-3-4-5\n"+"test,example,NETWORK,_,SYSTEM,owner,number,LONG,9223372036854775808\n"));
  assertFalse(report.valid());assertEquals(1,report.validRows());assertEquals(1,report.collisions());assertEquals(4,report.invalidRows());
 }
 @Test void boundsBytesRowsUtf8AndCsvGrammar()throws Exception {
  assertThrows(IOException.class,()->CsvDryRun.inspect(csv(HEADER+"x".repeat(CsvDryRun.MAX_BYTES))));
  assertThrows(IOException.class,()->CsvDryRun.inspect(csv(HEADER+"\n".repeat(CsvDryRun.MAX_ROWS+1))));
  assertThrows(IOException.class,()->CsvDryRun.inspect(csv(HEADER+"\"unclosed")));
  assertThrows(IOException.class,()->CsvDryRun.inspect(csv(HEADER+"\"closed\"unexpected")));
  Path invalid=temporary.resolve("invalid.csv");Files.write(invalid,new byte[]{(byte)0xc3,0x28});assertThrows(IOException.class,()->CsvDryRun.inspect(invalid));
 }
 @Test void optionalExistingAddressProbeReportsTypeConflictsWithoutOverwriting()throws Exception {
  var report=CsvDryRun.inspect(csv(HEADER+"test,example,NETWORK,_,SYSTEM,owner,key,LONG,1\n"+"test,example,NETWORK,_,SYSTEM,owner,key2,LONG,2\n"),address->java.util.Optional.of(address.key().equals("key")?ValueType.LONG:ValueType.STRING));
  assertFalse(report.valid());assertEquals(2,report.existingAddresses());assertEquals(1,report.existingTypeConflicts());assertEquals(2,report.validRows());
 }
 @Test void dryRunLeavesInputUnchanged()throws Exception {Path input=csv(HEADER+"test,example,NETWORK,_,SYSTEM,owner,key,STRING,secret\n");byte[] before=Files.readAllBytes(input);CsvDryRun.inspect(input);assertArrayEquals(before,Files.readAllBytes(input));}
}
