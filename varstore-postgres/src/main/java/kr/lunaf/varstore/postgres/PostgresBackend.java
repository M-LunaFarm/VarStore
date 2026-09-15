package kr.lunaf.varstore.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.lunaf.varstore.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Blocking implementation, exclusively called by the core's dedicated database workers. */
public final class PostgresBackend implements AutoCloseable {
    private static final String ADDRESS_WHERE="network_id=? AND namespace=? AND scope_kind=? AND scope_id=? AND owner_type=? AND owner_id=? AND variable_key=?";
    private static final String ADDRESS_COLUMNS="network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key";
    private static final String VALUE_COLUMNS="value_type,string_value,long_value,boolean_value,uuid_value,generation,revision,deleted";
    private final PostgresSettings settings;
    private final HikariDataSource pool;
    private final ThreadPoolExecutor borrowers;
    private static final int MAX_BORROWERS=64;
    private volatile UUID epoch;
    private final int maxAttempts;

    public PostgresBackend(PostgresSettings settings) { this(settings,3); }
    public PostgresBackend(PostgresSettings settings,int maxAttempts) {
        if(maxAttempts<1||maxAttempts>3)throw new IllegalArgumentException("Attempts must be 1..3");
        this.maxAttempts=maxAttempts;
        this.settings=Objects.requireNonNull(settings);
        HikariConfig config=new HikariConfig();
        // Paper may initialize DriverManager before loading this plugin. Resolve the shaded
        // driver through the plugin's classes instead of relying on global service discovery.
        config.setDriverClassName(org.postgresql.Driver.class.getName());
        config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.username());config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());config.setValidationTimeout(Math.max(250,Math.min(1000,settings.connectionTimeout().toMillis())));
        config.setInitializationFailTimeout(-1);config.setPoolName("VarStore-"+settings.serverId());config.setAutoCommit(true);
        config.addDataSourceProperty("sslmode",settings.tlsMode());config.addDataSourceProperty("ApplicationName","VarStore/"+settings.serverId());
        config.addDataSourceProperty("connectTimeout",Math.max(1,(settings.connectionTimeout().toMillis()+999)/1000));
        config.addDataSourceProperty("socketTimeout",Math.max(1,(settings.statementTimeout().toMillis()+999)/1000));
        config.addDataSourceProperty("tcpKeepAlive","true");
        pool=new HikariDataSource(config);
        AtomicInteger borrowerIds=new AtomicInteger();
        // Waiting callers do not consume database leases. Allow the full worker ceiling to
        // contend for a small pool without spuriously rejecting already-admitted requests.
        borrowers=new ThreadPoolExecutor(0,MAX_BORROWERS,30,TimeUnit.SECONDS,
                new SynchronousQueue<>(),task->{
                    Thread thread=new Thread(task,"VarStore-borrow-"+settings.serverId()+"-"+borrowerIds.incrementAndGet());
                    thread.setDaemon(true);return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized UUID initialize() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        return transaction(null,false,deadline,connection->{
            try { SchemaMigrator.validate(connection); }
            catch(SQLException error) { throw failure(ErrorCode.SCHEMA_MISMATCH,"Schema validation failed",null); }
            durability(connection);
            UUID current=lockEpoch(connection,deadline,false);
            if(epoch!=null && !epoch.equals(current)) throw failure(ErrorCode.STALE_EPOCH,"Storage epoch changed; restart required",null);
            epoch=current;return current;
        });
    }
    public void probe(long deadlineNanos) { transaction(null,false,deadlineNanos,c->{lockEpoch(c,deadlineNanos,true);durability(c);return null;}); }
    public UUID epoch() {return epoch;}
    public int activeConnections(){return pool.getHikariPoolMXBean().getActiveConnections();}
    public int idleConnections(){return pool.getHikariPoolMXBean().getIdleConnections();}
    public Map<String,Long> diagnostics(long deadline) {
        return transaction(null,false,deadline,c->{lockEpoch(c,deadline,true);Map<String,Long> data=new LinkedHashMap<>();
            try(var statement=prepare(c,"SELECT pg_total_relation_size('vs_variables'),pg_total_relation_size('vs_operations'),pg_total_relation_size('vs_admin_audit'),(SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock')",deadline);var rs=statement.executeQuery()) {
                rs.next();data.put("variablesBytes",rs.getLong(1));data.put("operationsBytes",rs.getLong(2));data.put("auditBytes",rs.getLong(3));data.put("lockWaiters",rs.getLong(4));
            }
            try(var statement=prepare(c,"SELECT COALESCE(sum(pg_table_size(c.oid)),0)::bigint,COALESCE(sum(pg_indexes_size(c.oid)),0)::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relname IN ('vs_variables','vs_operations','vs_admin_audit','vs_networks','vs_schema_history')",deadline);var rs=statement.executeQuery()){
                rs.next();data.put("tableBytes",rs.getLong(1));data.put("indexBytes",rs.getLong(2));
            }
            // Current accumulated wait across active VarStore sessions, not a lifetime counter.
            try(var statement=prepare(c,"SELECT COALESCE(sum(EXTRACT(EPOCH FROM (clock_timestamp()-l.waitstart))*1000000),0)::bigint FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE NOT l.granted AND l.waitstart IS NOT NULL AND a.datname=current_database() AND a.application_name LIKE 'VarStore/%'",deadline);var rs=statement.executeQuery()){
                rs.next();data.put("lockWaitMicros",rs.getLong(1));
            }
            return Map.copyOf(data);});
    }
    private void durability(Connection c) throws SQLException {
        if(!settings.strictDurability())return;
        try(var statement=c.createStatement();var rs=statement.executeQuery("SELECT current_setting('fsync'),current_setting('full_page_writes'),current_setting('synchronous_commit'),pg_is_in_recovery()")){
            rs.next();if(!rs.getString(1).equals("on")||!rs.getString(2).equals("on")||!Set.of("on","remote_apply").contains(rs.getString(3))||rs.getBoolean(4))
                throw failure(ErrorCode.DURABILITY_UNSAFE,"Primary database durability requirements not met",null);
        }
    }
    public <T> Optional<VersionedValue<T>> get(Target<T> target,long deadline) {
        checkNetwork(target.address());
        return transaction(null,false,deadline,c->{lockEpoch(c,deadline,true);
            try(var statement=prepare(c,"SELECT "+VALUE_COLUMNS+" FROM vs_variables WHERE "+ADDRESS_WHERE,deadline)) {
                bindAddress(statement,1,target.address());try(var rs=statement.executeQuery()){
                    if(!rs.next())return Optional.empty();Row row=row(rs,1);checkType(target,row);if(row.deleted)return Optional.empty();
                    @SuppressWarnings("unchecked") T value=(T)row.value;
                    return Optional.of(new VersionedValue<>(value,token(row)));
                }
            }
        });
    }
    public Map<Address,Optional<VersionedValue<?>>> getAll(List<Target<?>> targets,long deadline) {
        targets=List.copyOf(targets);if(targets.size()>64)throw failure(ErrorCode.INVALID_ARGUMENT,"Batch is limited to 64 keys",null);
        Map<Address,Target<?>> requested=new LinkedHashMap<>();for(var target:targets){checkNetwork(target.address());if(requested.putIfAbsent(target.address(),target)!=null)throw failure(ErrorCode.INVALID_ARGUMENT,"Duplicate batch address",null);}
        return transaction(null,false,deadline,c->{lockEpoch(c,deadline,true);Map<Address,Optional<VersionedValue<?>>> result=new LinkedHashMap<>();requested.keySet().forEach(a->result.put(a,Optional.empty()));
            if(requested.isEmpty())return Collections.unmodifiableMap(result);
            String predicates=String.join(" OR ",Collections.nCopies(requested.size(),"("+ADDRESS_WHERE+")"));
            try(var statement=prepare(c,"SELECT "+ADDRESS_COLUMNS+","+VALUE_COLUMNS+" FROM vs_variables WHERE "+predicates,deadline)){
                int parameter=1;for(var target:requested.values())parameter=bindAddress(statement,parameter,target.address());
                try(var rs=statement.executeQuery()){while(rs.next()){
                    Address address=readAddress(rs);Row row=row(rs,8);checkType(requested.get(address),row);
                    if(!row.deleted)result.put(address,Optional.of(new VersionedValue<>(row.value,token(row))));
                }}
            }return Collections.unmodifiableMap(result);
        });
    }
    public <T> WriteReceipt<T> write(Target<?> target,WriteKind kind,Object value,long delta,VersionToken expected,UUID operationId,long deadline) {
        Objects.requireNonNull(kind);Objects.requireNonNull(operationId);
        List<Condition> conditions=switch(kind){case SET_IF_ABSENT->List.of(Condition.absent(target));case COMPARE_AND_SET->List.of(Condition.version(target,Objects.requireNonNull(expected)));default->List.of();};
        Mutation mutation=switch(kind){case DELETE->Mutation.delete(target);case INCREMENT->new Mutation(target,Mutation.Kind.INCREMENT,null,delta);default->new Mutation(target,Mutation.Kind.SET,value,0);};
        TransactionReceipt result=execute(new TransactionPlan(conditions,List.of(mutation)),operationId,deadline,kind.name());
        @SuppressWarnings("unchecked") WriteReceipt<T> receipt=(WriteReceipt<T>)result.results().get(target.address());return receipt;
    }
    public TransactionReceipt execute(TransactionPlan plan,UUID operationId,long deadline){return execute(plan,operationId,deadline,"TRANSACTION");}

    private TransactionReceipt execute(TransactionPlan plan,UUID operationId,long deadline,String kind) {
        Objects.requireNonNull(plan);Objects.requireNonNull(operationId);
        TreeMap<Address,Target<?>> targets=new TreeMap<>(ProtocolCodec.addressOrder());
        for(var condition:plan.conditions())addTarget(targets,condition.target());for(var mutation:plan.mutations())addTarget(targets,mutation.target());
        if(targets.isEmpty() || targets.size()>16)throw failure(ErrorCode.INVALID_ARGUMENT,"Transactions require 1..16 keys",operationId);
        String namespace=targets.firstKey().namespace();for(var target:targets.values())if(!namespace.equals(target.address().namespace()))throw failure(ErrorCode.INVALID_ARGUMENT,"Transaction namespace mismatch",operationId);
        byte[] fingerprint=ProtocolCodec.fingerprint(kind,plan);
        return transaction(operationId,true,deadline,c->{
            lockEpoch(c,deadline,true);
            boolean reserved;
            try(var statement=prepare(c,"INSERT INTO vs_operations(network_id,namespace,operation_id,fingerprint) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",deadline)){
                statement.setString(1,settings.networkId());statement.setString(2,namespace);statement.setObject(3,operationId);statement.setBytes(4,fingerprint);reserved=statement.executeUpdate()==1;
            }
            // This MUST be a separate SQL statement: conflict resolution may have waited for a commit.
            if(!reserved)return replay(c,namespace,operationId,fingerprint,deadline);
            Savepoint variableChanges=c.setSavepoint("variable_changes");
            Map<Address,Row> before=new LinkedHashMap<>();
            for(var target:targets.values())before.put(target.address(),lockRow(c,target,deadline));
            for(var target:targets.values())checkType(target,before.get(target.address()));
            boolean passed=true;for(var condition:plan.conditions())if(!matches(condition,before.get(condition.target().address()))){passed=false;break;}
            Map<Address,WriteReceipt<?>> results=new LinkedHashMap<>();Outcome outcome=Outcome.NO_CHANGE;
            if(!passed){
                c.rollback(variableChanges);outcome=Outcome.CONDITION_FAILED;
                for(var mutation:plan.mutations())results.put(mutation.target().address(),receipt(operationId,Outcome.CONDITION_FAILED,before.get(mutation.target().address()),false));
            } else {
                for(var mutation:plan.mutations()){
                    Row old=before.get(mutation.target().address());Row changed=apply(c,mutation,old,deadline);
                    Outcome item=old==changed?Outcome.NO_CHANGE:Outcome.APPLIED;if(item==Outcome.APPLIED)outcome=Outcome.APPLIED;
                    results.put(mutation.target().address(),receipt(operationId,item,changed,mutation.kind()==Mutation.Kind.DELETE));
                }
                // Absence-only condition locks and missing DELETEs do not establish persistent key types.
                for(var target:targets.values())if(before.get(target.address()).revision==0 && !results.containsKey(target.address()))deletePlaceholder(c,target.address(),deadline);
                for(var mutation:plan.mutations())if(mutation.kind()==Mutation.Kind.DELETE && before.get(mutation.target().address()).revision==0)deletePlaceholder(c,mutation.target().address(),deadline);
            }
            TransactionReceipt result=new TransactionReceipt(operationId,outcome,results,false);
            try(var statement=prepare(c,"UPDATE vs_operations SET outcome=?,result_payload=?,completed_at=clock_timestamp() WHERE network_id=? AND namespace=? AND operation_id=?",deadline)){
                statement.setString(1,outcome.name());statement.setBytes(2,ProtocolCodec.receipt(result));statement.setString(3,settings.networkId());statement.setString(4,namespace);statement.setObject(5,operationId);statement.executeUpdate();
            }
            if(plan.audit().isPresent())audit(c,plan.audit().get(),result,before,namespace,deadline);
            return result;
        });
    }
    public OperationStatus operation(String namespace,UUID operationId,long deadline) {
        if(namespace==null||!namespace.matches("[a-z0-9._-]{1,64}"))throw failure(ErrorCode.INVALID_ARGUMENT,"Invalid namespace",operationId);
        return transaction(operationId,false,deadline,c->{lockEpoch(c,deadline,true);
            try(var statement=prepare(c,"SELECT result_payload,result_expired,completed_at FROM vs_operations WHERE network_id=? AND namespace=? AND operation_id=?",deadline)){
                statement.setString(1,settings.networkId());statement.setString(2,namespace);statement.setObject(3,operationId);
                try(var rs=statement.executeQuery()){
                    if(!rs.next())return new OperationStatus(operationId,OperationStatus.State.NOT_OBSERVED_YET,Optional.empty());
                    if(rs.getBoolean(2))return new OperationStatus(operationId,OperationStatus.State.RESULT_EXPIRED,Optional.empty());
                    if(rs.getTimestamp(3)==null)return new OperationStatus(operationId,OperationStatus.State.IN_PROGRESS,Optional.empty());
                    return new OperationStatus(operationId,OperationStatus.State.COMPLETED,Optional.of(ProtocolCodec.receipt(rs.getBytes(1),true)));
                }
            }
        });
    }
    private TransactionReceipt replay(Connection c,String namespace,UUID operation,byte[] fingerprint,long deadline)throws SQLException{
        try(var statement=prepare(c,"SELECT fingerprint,result_payload,result_expired,completed_at FROM vs_operations WHERE network_id=? AND namespace=? AND operation_id=?",deadline)){
            statement.setString(1,settings.networkId());statement.setString(2,namespace);statement.setObject(3,operation);
            try(var rs=statement.executeQuery()){
                if(!rs.next())throw new SQLException("Conflicting operation disappeared");
                if(!java.security.MessageDigest.isEqual(fingerprint,rs.getBytes(1)))throw failure(ErrorCode.IDEMPOTENCY_KEY_REUSED,"Operation ID was already used for different content",operation);
                if(rs.getBoolean(3))throw failure(ErrorCode.ALREADY_PROCESSED_RESULT_EXPIRED,"Operation was already processed; full result expired",operation);
                if(rs.getTimestamp(4)==null)throw new SQLException("Operation has no committed result");
                return ProtocolCodec.receipt(rs.getBytes(2),true);
            }
        }
    }
    private Row lockRow(Connection c,Target<?> target,long deadline)throws SQLException{
        try(var statement=prepare(c,"INSERT INTO vs_variables("+ADDRESS_COLUMNS+",value_type,generation,revision,deleted,last_writer) VALUES(?,?,?,?,?,?,?,?,?,0,true,?) ON CONFLICT DO NOTHING",deadline)){
            int p=bindAddress(statement,1,target.address());statement.setString(p++,target.type().name());statement.setObject(p++,UUID.randomUUID());statement.setString(p,settings.serverId());statement.executeUpdate();
        }
        try(var statement=prepare(c,"SELECT "+VALUE_COLUMNS+" FROM vs_variables WHERE "+ADDRESS_WHERE+" FOR UPDATE",deadline)){
            bindAddress(statement,1,target.address());try(var rs=statement.executeQuery()){if(!rs.next())throw new SQLException("Locked variable disappeared");return row(rs,1);}
        }
    }
    private Row apply(Connection c,Mutation mutation,Row old,long deadline)throws SQLException{
        Object value;boolean deleted=false;
        switch(mutation.kind()){
            case DELETE->{if(old.deleted)return old;value=null;deleted=true;}
            case SET->{value=mutation.value();if(!old.deleted && Objects.equals(value,old.value))return old;}
            case INCREMENT->{if(old.deleted)throw failure(ErrorCode.MISSING_VALUE,"Cannot increment a missing variable",null);if(mutation.delta()==0)return old;
                try{value=Math.addExact((Long)old.value,mutation.delta());}catch(ArithmeticException error){throw failure(ErrorCode.NUMERIC_OVERFLOW,"LONG overflow",null);}}
            default->throw new IllegalStateException("Unsupported mutation");
        }
        if(old.revision==Long.MAX_VALUE)throw failure(ErrorCode.NUMERIC_OVERFLOW,"Revision overflow",null);
        Row next=new Row(old.type,value,old.generation,old.revision+1,deleted);
        try(var statement=prepare(c,"UPDATE vs_variables SET string_value=?,long_value=?,boolean_value=?,uuid_value=?,revision=?,deleted=?,updated_at=clock_timestamp(),last_writer=? WHERE "+ADDRESS_WHERE,deadline)){
            statement.setObject(1,next.type==ValueType.STRING?value:null,Types.VARCHAR);statement.setObject(2,next.type==ValueType.LONG?value:null,Types.BIGINT);
            statement.setObject(3,next.type==ValueType.BOOLEAN?value:null,Types.BOOLEAN);statement.setObject(4,next.type==ValueType.UUID?value:null,Types.OTHER);
            statement.setLong(5,next.revision);statement.setBoolean(6,next.deleted);statement.setString(7,settings.serverId());bindAddress(statement,8,mutation.target().address());
            if(statement.executeUpdate()!=1)throw new SQLException("Locked variable disappeared during update");
        }return next;
    }
    private void deletePlaceholder(Connection c,Address address,long deadline)throws SQLException{
        try(var statement=prepare(c,"DELETE FROM vs_variables WHERE "+ADDRESS_WHERE+" AND revision=0 AND deleted",deadline)){bindAddress(statement,1,address);statement.executeUpdate();}
    }
    private boolean matches(Condition condition,Row row){
        return switch(condition.kind()){
            case EXISTS->!row.deleted;case ABSENT->row.deleted;case VERSION->!row.deleted&&token(row).equals(condition.version());
            case LONG_RANGE->!row.deleted && ((Long)row.value)>=condition.minimum()&&((Long)row.value)<=condition.maximum();
        };
    }
    private WriteReceipt<?> receipt(UUID operation,Outcome outcome,Row row,boolean delete){
        return new WriteReceipt<>(operation,outcome,row.revision==0?Optional.empty():Optional.of(token(row)),delete||row.deleted?Optional.empty():Optional.of(row.value),false);
    }
    private void audit(Connection c,AuditContext audit,TransactionReceipt receipt,Map<Address,Row> before,String namespace,long deadline)throws SQLException{
        for(var entry:receipt.results().entrySet())try(var statement=prepare(c,"INSERT INTO vs_admin_audit(network_id,namespace,operation_id,actor,action,target,outcome,before_version,after_version) VALUES(?,?,?,?,?,?,?,?,?)",deadline)){
            statement.setString(1,settings.networkId());statement.setString(2,namespace);statement.setObject(3,receipt.operationId());statement.setString(4,audit.actor());statement.setString(5,audit.action());
            statement.setString(6,String.join("/",entry.getKey().fields()));statement.setString(7,receipt.outcome().name());Row old=before.get(entry.getKey());
            statement.setString(8,old.revision==0?null:token(old).toString());statement.setString(9,entry.getValue().version().map(Object::toString).orElse(null));statement.executeUpdate();
        }
    }
    private UUID lockEpoch(Connection c,long deadline,boolean requireInitialized)throws SQLException{
        if(requireInitialized&&epoch==null)throw failure(ErrorCode.NOT_READY,"Backend is not initialized",null);
        try(var statement=prepare(c,"SELECT storage_epoch,current_setting('fsync'),current_setting('full_page_writes'),current_setting('synchronous_commit'),pg_is_in_recovery() FROM vs_networks WHERE network_id=? FOR SHARE",deadline)){
            statement.setString(1,settings.networkId());try(var rs=statement.executeQuery()){
                if(!rs.next())throw failure(ErrorCode.NOT_READY,"Network is not provisioned",null);
                if(settings.strictDurability() && (!rs.getString(2).equals("on") || !rs.getString(3).equals("on") || !Set.of("on","remote_apply").contains(rs.getString(4)) || rs.getBoolean(5)))throw failure(ErrorCode.DURABILITY_UNSAFE,"Primary database durability requirements not met",null);
                UUID current=rs.getObject(1,UUID.class);if(epoch!=null&&!epoch.equals(current))throw failure(ErrorCode.STALE_EPOCH,"Storage epoch changed; restart required",null);return current;
            }
        }
    }
    private void addTarget(Map<Address,Target<?>> targets,Target<?> target){checkNetwork(target.address());Target<?> old=targets.putIfAbsent(target.address(),target);if(old!=null&&old.type()!=target.type())throw failure(ErrorCode.TYPE_MISMATCH,"Conflicting target types",null);}
    private void checkNetwork(Address address){if(!settings.networkId().equals(address.networkId()))throw failure(ErrorCode.INVALID_ARGUMENT,"Address network does not match configured network",null);}
    private static void checkType(Target<?> target,Row row){if(target.type()!=row.type)throw failure(ErrorCode.TYPE_MISMATCH,"Stored type does not match requested type",null);}
    private VersionToken token(Row row){return new VersionToken(epoch,row.generation,row.revision);}
    private static Row row(ResultSet rs,int p)throws SQLException{
        ValueType type=ValueType.valueOf(rs.getString(p));Object value=switch(type){case STRING->rs.getString(p+1);case LONG->rs.getObject(p+2,Long.class);case BOOLEAN->rs.getObject(p+3,Boolean.class);case UUID->rs.getObject(p+4,UUID.class);};
        return new Row(type,value,rs.getObject(p+5,UUID.class),rs.getLong(p+6),rs.getBoolean(p+7));
    }
    private record Row(ValueType type,Object value,UUID generation,long revision,boolean deleted){}
    private static Address readAddress(ResultSet rs)throws SQLException{return new Address(rs.getString(1),rs.getString(2),ScopeKind.valueOf(rs.getString(3)),rs.getString(4),new Owner(rs.getString(5),rs.getString(6)),rs.getString(7));}
    private static int bindAddress(PreparedStatement statement,int p,Address address)throws SQLException{for(String field:address.fields())statement.setString(p++,field);return p;}
    private PreparedStatement prepare(Connection c,String sql,long deadline)throws SQLException{
        long remaining=remainingMillis(deadline);
        c.setNetworkTimeout(Runnable::run,(int)Math.min(Integer.MAX_VALUE,remaining));
        try(var timeout=c.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")){
            timeout.setString(1,Long.toString(Math.max(1,Math.min(remaining,settings.statementTimeout().toMillis()))));timeout.setString(2,Long.toString(Math.max(1,Math.min(remaining,settings.lockTimeout().toMillis()))));timeout.execute();
        }
        return c.prepareStatement(sql);
    }
    private static long remainingMillis(long deadline){long remaining=deadline-System.nanoTime();if(remaining<=0)throw failure(ErrorCode.REQUEST_TIMEOUT,"Request deadline elapsed before commit",null);return Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining));}
    private interface Transaction<T>{T run(Connection c)throws SQLException;}
    private static final class RetryableTransaction extends RuntimeException {
        private static final long serialVersionUID=1L;
    }
    private <T>T transaction(UUID operation,boolean write,long deadline,Transaction<T> action){
        for(int attempt=1;;attempt++){
            try{return transactionOnce(operation,write,deadline,action);}
            catch(RetryableTransaction retry){
                if(attempt>=maxAttempts)throw failure(ErrorCode.STORAGE_UNAVAILABLE,"Transaction rolled back after bounded retries",operation);
                long delay=java.util.concurrent.ThreadLocalRandom.current().nextLong(5L<<attempt,10L<<attempt);
                if(remainingMillis(deadline)<=delay)throw failure(ErrorCode.REQUEST_TIMEOUT,"Insufficient deadline for transaction retry",operation);
                try{Thread.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure(ErrorCode.STORAGE_UNAVAILABLE,"Database worker interrupted after rollback",operation);}
            }
        }
    }
    private <T>T transactionOnce(UUID operation,boolean write,long deadline,Transaction<T> action){
        Connection connection=null;boolean commitStarted=false;boolean committed=false;
        try{
            remainingMillis(deadline);connection=borrow(deadline);remainingMillis(deadline);
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            T result=action.run(connection);long commitRemaining=remainingMillis(deadline);connection.setNetworkTimeout(Runnable::run,(int)Math.min(Integer.MAX_VALUE,commitRemaining));commitStarted=true;connection.commit();committed=true;return result;
        }catch(VarStoreException error){throw error.withOperationId(operation);}
        catch(SQLException error){
            if(!commitStarted && Set.of("40001","40P01").contains(error.getSQLState()==null?"":error.getSQLState()) && connection!=null){
                try{connection.rollback();throw new RetryableTransaction();}catch(SQLException rollbackFailure){/* Retry only after rollback is confirmed. */}
            }
            ErrorCode code=commitStarted&&write?ErrorCode.UNKNOWN_COMMIT_OUTCOME:ErrorCode.STORAGE_UNAVAILABLE;
            // SQL parameter values are deliberately not propagated through driver error text.
            throw new VarStoreException(code,"Database operation failed"+(error.getSQLState()==null?"":" (SQLSTATE "+error.getSQLState()+")"),operation);
        }finally{
            if(connection!=null){if(!committed)try{connection.rollback();}catch(SQLException ignored){/* Never turn an unconfirmed commit into success. */}
                try{connection.close();}catch(SQLException ignored){/* Pool discards broken connection; confirmed result remains authoritative. */}}
        }
    }
    /** Hikari has a pool-wide acquisition timeout; bound each caller without mutating that shared setting. */
    private Connection borrow(long deadline)throws SQLException{
        remainingMillis(deadline);
        CompletableFuture<Connection> handoff=new CompletableFuture<>();
        try{
            borrowers.execute(()->{
                try{
                    Connection connection=pool.getConnection();
                    // A timeout/interrupt wins by completing the handoff exceptionally. Late leases are returned.
                    if(!handoff.complete(connection))closeUnused(connection);
                }catch(SQLException | RuntimeException failure){handoff.completeExceptionally(failure);}
            });
        }catch(RejectedExecutionException rejected){
            throw failure(borrowers.isShutdown()?ErrorCode.SHUTTING_DOWN:ErrorCode.OVERLOADED,
                    "Connection acquisition capacity is unavailable",null);
        }
        try{
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new TimeoutException();
            return handoff.get(remaining,TimeUnit.NANOSECONDS);
        }catch(TimeoutException timeout){
            abandon(handoff);
            throw failure(ErrorCode.REQUEST_TIMEOUT,"Request deadline elapsed acquiring a connection",null);
        }catch(InterruptedException interrupted){
            abandon(handoff);Thread.currentThread().interrupt();
            throw failure(ErrorCode.STORAGE_UNAVAILABLE,"Connection acquisition interrupted before SQL",null);
        }catch(ExecutionException failed){
            if(failed.getCause() instanceof SQLException sql)throw sql;
            throw new SQLException("Connection acquisition failed");
        }
    }
    private static void abandon(CompletableFuture<Connection> handoff){
        if(!handoff.completeExceptionally(new CancellationException("Connection request abandoned"))){
            // A successful handoff can race with get's timeout. The abandoning caller owns its cleanup.
            try{Connection connection=handoff.getNow(null);if(connection!=null)closeUnused(connection);}
            catch(CompletionException | CancellationException ignored){/* No lease was delivered. */}
        }
    }
    private static void closeUnused(Connection connection){
        try{connection.close();}catch(SQLException ignored){/* Hikari invalidates a broken returned lease. */}
    }
    private static VarStoreException failure(ErrorCode code,String message,UUID operation){return new VarStoreException(code,message,operation);}
    @Override public void close(){
        borrowers.shutdownNow();
        pool.close();
        try{borrowers.awaitTermination(Math.min(1000,settings.connectionTimeout().toMillis()),TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
