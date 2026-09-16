package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the documented DML account can run the backend without owning its schema. */
@EnabledIfEnvironmentVariable(named="VARSTORE_TEST_JDBC_URL",matches=".+")
class RuntimeRoleTest {
    @Test void documentedRuntimeGrantsSupportStorageAndAuditButRejectDdlAndNetworkProvisioning()throws Exception {
        String baseUrl=System.getenv("VARSTORE_TEST_JDBC_URL");
        String adminUser=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");
        String adminPassword=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="role_test_"+suffix,role="runtime_"+suffix,rolePassword=UUID.randomUUID().toString();
        String url=baseUrl+(baseUrl.contains("?")?"&":"?")+"currentSchema="+schema;
        boolean roleCreated=false,schemaCreated=false;
        try(var admin=DriverManager.getConnection(baseUrl,adminUser,adminPassword)) {
            String database=admin.getCatalog();
            try {
                try(var statement=admin.createStatement()) {
                    statement.execute("CREATE ROLE "+quoted(role)+" LOGIN PASSWORD '"+rolePassword+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");roleCreated=true;
                    statement.execute("CREATE SCHEMA "+quoted(schema));schemaCreated=true;
                }
                admin.setSchema(schema);SchemaMigrator.migrate(admin,"runtime-test");
                String checksum=checksum(admin);
                // These grants mirror README.md, with the isolated schema/database substituted.
                try(var grant=admin.createStatement()) {
                    grant.execute("GRANT CONNECT ON DATABASE "+quoted(database)+" TO "+quoted(role));
                    grant.execute("GRANT USAGE ON SCHEMA "+quoted(schema)+" TO "+quoted(role));
                    grant.execute("GRANT SELECT ON vs_schema_history TO "+quoted(role));
                    grant.execute("GRANT SELECT, UPDATE ON vs_networks TO "+quoted(role));
                    grant.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON vs_variables TO "+quoted(role));
                    grant.execute("GRANT SELECT, INSERT, UPDATE ON vs_operations TO "+quoted(role));
                    grant.execute("GRANT INSERT ON vs_admin_audit TO "+quoted(role));
                    grant.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON vs_subscriptions, vs_outbox, vs_outbox_delivery TO "+quoted(role));
                    grant.execute("GRANT USAGE ON SEQUENCE vs_outbox_event_id_seq TO "+quoted(role));
                    grant.execute("GRANT USAGE ON SEQUENCE vs_admin_audit_audit_id_seq TO "+quoted(role));
                }
                var settings=new PostgresSettings(url,role,rolePassword,"runtime-test","runtime","disable",true,2,
                        Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));
                UUID operation=UUID.randomUUID();
                try(var runtime=new PostgresBackend(settings)) {
                    assertNotNull(runtime.initialize());
                    var subscription=runtime.registerSubscription(new SubscriptionSpec("runtime-events","example",SubscriptionMode.EPHEMERAL,Duration.ofMinutes(1),Duration.ofDays(1)),deadline());
                    Target<Long> target=new Target<>(new Address("runtime-test","example",ScopeKind.NETWORK,"_",Owner.system("global"),"level"),ValueType.LONG);
                    var initial=runtime.<Long>write(target,WriteKind.SET,1L,0,null,UUID.randomUUID(),deadline());
                    assertEquals(1L,runtime.get(target,deadline()).orElseThrow().value());
                    var plan=TransactionPlan.builder().requireVersion(target,initial.version().orElseThrow()).set(target,2L).build()
                            .withAudit(new AuditContext("CONSOLE","SET"));
                    assertEquals(Outcome.APPLIED,runtime.execute(plan,operation,deadline()).outcome());
                    assertTrue(runtime.execute(plan,operation,deadline()).replayed());
                    assertEquals(2L,runtime.getAll(List.of(target),deadline()).get(target.address()).orElseThrow().value());
                    assertEquals(OperationStatus.State.COMPLETED,runtime.operation("example",operation,deadline()).state());
                    var deliveries=runtime.claimEvents(subscription,10,Duration.ofSeconds(10),deadline());assertEquals(2,deliveries.size());
                    for(var delivery:deliveries)assertTrue(runtime.acknowledge(subscription,delivery.event().eventId(),delivery.leaseToken(),deadline()));
                    runtime.closeSubscription(subscription,deadline());
                    assertEquals(1,runtime.scanKeys(target.address(),"lev",java.util.Optional.empty(),50,deadline()).keys().size());
                    assertTrue(runtime.capacity(deadline()).containsKey("pendingDeliveriesInSample"));
                    runtime.probe(deadline());
                    assertTrue(runtime.diagnostics(deadline()).get("tableBytes")>0);
                }
                try(var runtime=DriverManager.getConnection(url,role,rolePassword)) {
                    SchemaMigrator.validate(runtime);
                    try(var statement=runtime.createStatement();var result=statement.executeQuery("SELECT current_user,has_schema_privilege(current_user,current_schema(),'CREATE')")) {
                        assertTrue(result.next());assertEquals(role,result.getString(1));assertFalse(result.getBoolean(2));
                    }
                    try(var statement=runtime.createStatement()) {
                        SQLException ddl=assertThrows(SQLException.class,()->statement.execute("CREATE TABLE forbidden_ddl(id integer)"));
                        assertEquals("42501",ddl.getSQLState());
                        SQLException provision=assertThrows(SQLException.class,()->statement.execute("INSERT INTO vs_networks(network_id,storage_epoch) VALUES('unauthorized','00000000-0000-0000-0000-000000000001')"));
                        assertEquals("42501",provision.getSQLState());
                    }
                    assertEquals(checksum,checksum(runtime));SchemaMigrator.validate(runtime);
                }
                try(var statement=admin.prepareStatement("SELECT count(*) FROM vs_admin_audit WHERE operation_id=?")) {
                    statement.setObject(1,operation);try(var result=statement.executeQuery()){assertTrue(result.next());assertEquals(1,result.getInt(1));}
                }
                SchemaMigrator.validate(admin);assertEquals(checksum,checksum(admin));
            }finally {
                try(var cleanup=admin.createStatement()) {
                    if(schemaCreated)cleanup.execute("DROP SCHEMA "+quoted(schema)+" CASCADE");
                    if(roleCreated){cleanup.execute("REVOKE ALL PRIVILEGES ON DATABASE "+quoted(database)+" FROM "+quoted(role));cleanup.execute("DROP ROLE "+quoted(role));}
                }
            }
        }
    }
    private static String checksum(Connection connection)throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT catalog_checksum FROM vs_schema_history WHERE version=1")) {
            assertTrue(result.next());return result.getString(1);
        }
    }
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
    private static String quoted(String identifier){return "\""+identifier.replace("\"","\"\"")+"\"";}
}
