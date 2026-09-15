package kr.lunaf.varstore.postgres;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Pool and transaction settings; credentials are deliberately omitted from toString. */
public record PostgresSettings(String jdbcUrl, String username, String password,
        String networkId, String serverId, String tlsMode, boolean strictDurability,
        int maximumPoolSize, Duration connectionTimeout, Duration lockTimeout, Duration statementTimeout) {
    public PostgresSettings {
        Objects.requireNonNull(jdbcUrl); Objects.requireNonNull(username); Objects.requireNonNull(password);
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL JDBC URL required");
        if (!networkId.matches("[a-z0-9._-]{1,64}") || !serverId.matches("[a-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid network/server ID");
        if (!tlsMode.equals("verify-full") && !tlsMode.equals("disable")) throw new IllegalArgumentException("TLS must be verify-full or explicitly disabled for local testing");
        validateUrlOptions(jdbcUrl);
        if (maximumPoolSize < 1 || maximumPoolSize > 64) throw new IllegalArgumentException("Pool size must be 1..64");
        if (connectionTimeout.toMillis() < 250 || lockTimeout.toMillis() < 1 || statementTimeout.toMillis() < 1) throw new IllegalArgumentException("Invalid timeout");
    }
    private static void validateUrlOptions(String jdbcUrl) {
        int query=jdbcUrl.indexOf('?');if(query<0)return;
        Set<String> permitted=Set.of("currentSchema","sslrootcert","sslcert","sslkey");
        Set<String> seen=new HashSet<>();
        for(String option:jdbcUrl.substring(query+1).split("&",-1)) {
            int equals=option.indexOf('=');
            if(equals<=0 || equals==option.length()-1)throw new IllegalArgumentException("JDBC URL options require nonempty names and values");
            String name;
            try{name=URLDecoder.decode(option.substring(0,equals),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException malformed){throw new IllegalArgumentException("Malformed JDBC URL option name");}
            if(!permitted.contains(name)||!seen.add(name))throw new IllegalArgumentException("JDBC URL option is not permitted; configure credentials, TLS mode and timeouts separately");
        }
    }
    @Override public String toString() { return "PostgresSettings[networkId="+networkId+",serverId="+serverId+",tlsMode="+tlsMode+",maximumPoolSize="+maximumPoolSize+"]"; }
}
