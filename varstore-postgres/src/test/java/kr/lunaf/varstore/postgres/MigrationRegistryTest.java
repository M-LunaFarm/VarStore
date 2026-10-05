package kr.lunaf.varstore.postgres;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class MigrationRegistryTest {
    @Test void publishedMigrationBytesRemainUnchanged() throws Exception {
        assertHash(1, "12df16f1ded170b3f080debd26a6991040a91f0ddc8188d80903e78ca1aaa484");
        assertHash(2, "16a8645a16d9d8b7514d04fc9486e8250ecad0cba47f042fc603078dc59afdaa");
    }
    private void assertHash(int version, String expected) throws Exception {
        try(var input=SchemaMigrator.class.getResourceAsStream(SchemaMigrator.migration(version).resource())) {
            assertNotNull(input);
            assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }
    }
    @Test void unknownVersionsFailBeforeTouchingAConnection() {
        for(int version : new int[]{Integer.MIN_VALUE,0,SchemaMigrator.VERSION+1,Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->SchemaMigrator.migration(version));
            assertThrows(IllegalArgumentException.class,()->SchemaMigrator.migrateTo(null,"test",version));
        }
    }
    @Test void legacyCatalogCoverageIsRetained() {
        assertEquals(5,SchemaMigrator.migration(1).tables().size());
        assertTrue(SchemaMigrator.migration(1).sequences().isEmpty());
        assertEquals(8,SchemaMigrator.migration(2).tables().size());
        assertEquals(java.util.List.of("vs_admin_audit_audit_id_seq","vs_outbox_event_id_seq"),SchemaMigrator.migration(2).sequences());
        assertThrows(UnsupportedOperationException.class,()->SchemaMigrator.migration(2).tables().add("other"));
    }
}
