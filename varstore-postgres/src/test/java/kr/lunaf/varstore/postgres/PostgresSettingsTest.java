package kr.lunaf.varstore.postgres;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PostgresSettingsTest {
    private PostgresSettings settings(String query) {
        return new PostgresSettings("jdbc:postgresql://localhost/test"+query,"user","secret","test","a","verify-full",true,2,
                Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));
    }
    @Test void dangerousUrlOptionsCannotOverrideCredentialsTlsOrDeadlines() {
        for(String option:new String[]{"sslmode=disable","%73slmode=disable","%2573slmode=disable","user=other","%70assword=exposed",
                "socketTimeout=0","connectTimeout=99999","%63onnectTimeout=99999","options=-c%20synchronous_commit%3Doff",
                "sslfactory=untrusted.Factory","socketFactory=untrusted.Factory","ApplicationName=other"}) {
            assertThrows(IllegalArgumentException.class,()->settings("?"+option),option);
        }
    }
    @Test void explicitSchemaAndCertificatePathsRemainAvailable() {
        assertDoesNotThrow(()->settings(""));
        assertDoesNotThrow(()->settings("?currentSchema=test&sslrootcert=%2Fetc%2Fca.pem&sslcert=%2Fetc%2Fclient.pem&sslkey=%2Fetc%2Fclient.pk8"));
        assertDoesNotThrow(()->settings("?%63urrentSchema=test"));
        assertThrows(IllegalArgumentException.class,()->settings("?currentSchema=one&%63urrentSchema=two"));
        assertThrows(IllegalArgumentException.class,()->settings("?%=bad"));
        assertThrows(IllegalArgumentException.class,()->settings("?currentSchema="));
    }
}
