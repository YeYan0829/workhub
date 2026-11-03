package dev.workhub.contract;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
class ApplicationContractIT {
    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Test void bootstrapHealthSecurityAndJdbc() throws Exception {
        var result=http.getForEntity("/actuator/health",String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).contains("UP");
        assertThat(http.getForEntity("/not-a-business-api",String.class).getStatusCode().value()).isEqualTo(403);
        try(var c=dataSource.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
            assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
