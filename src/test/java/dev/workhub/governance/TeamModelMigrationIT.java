package dev.workhub.governance;

import static dev.workhub.common.Ids.*;
import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TeamModelMigrationIT {
  @Test
  void upgradePreservesLegacyRunsHoldsAndForeignKeysAndNewInstallIsRepeatable() {
    var cfg = new HikariConfig();
    cfg.setJdbcUrl(
        System.getenv()
            .getOrDefault(
                "TEAM_MODEL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:28306/workhub_team_model_test?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectionTimeZone=UTC")
            .replace("/workhub_team_model_test?", "/workhub_team_model_upgrade_test?"));
    cfg.setUsername(System.getenv().getOrDefault("TEAM_MODEL_DB_USER", "team_model_fixture"));
    cfg.setPassword(
        System.getenv().getOrDefault("TEAM_MODEL_DB_PASSWORD", "team-model-local-only"));
    try (var source = new HikariDataSource(cfg)) {
      var db = new JdbcTemplate(source);
      assertThat(db.queryForObject("SELECT DATABASE()", String.class))
          .isEqualTo("workhub_team_model_upgrade_test");
      // This dedicated upgrade schema is disposable. Never clean an application schema.
      var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
      flyway.clean();
      Flyway.configure().dataSource(source).target("22").load().migrate();
      UUID user = UUID.randomUUID(),
          w = UUID.randomUUID(),
          p = UUID.randomUUID(),
          run = UUID.randomUUID();
      db.update(
          "INSERT INTO app_user(id,login,display_name,password_hash) VALUES (?,?,'Legacy"
              + " fixture','unused')",
          bytes(user),
          "legacy-" + user);
      db.update("INSERT INTO workspace(id,name) VALUES (?,'Legacy fixture')", bytes(w));
      db.update(
          "INSERT INTO project(id,workspace_id,name) VALUES (?,?,'Legacy fixture')",
          bytes(p),
          bytes(w));
      db.update(
          "INSERT INTO agent_run(id,project_id,actor_id,question,model_contract,business_revision)"
              + " VALUES (?,?,?,'Legacy','{}',0)",
          bytes(run),
          bytes(p),
          bytes(user));
      db.update("INSERT INTO agent_budget VALUES ('legacy-team-migration','unchanged',1000,900)");
      for (String state : List.of("RESERVED", "UNKNOWN"))
        db.update(
            "INSERT INTO"
                + " agent_model_call(id,run_id,attempt_token,authorization_id,input_token_bound,output_token_bound,reserved_nano_usd,state)"
                + " VALUES (?,?,?,'legacy-team-migration',100,10,450,?)",
            bytes(UUID.randomUUID()),
            bytes(run),
            bytes(UUID.randomUUID()),
            state);
      Flyway.configure().dataSource(source).target("23").load().migrate();
      db.update(
          "INSERT INTO"
              + " team_model_connection(workspace_id,version,provider,default_model,allowed_models,key_cipher,enabled,request_rate,request_burst,updated_by)"
              + " VALUES (?,1,'ZAI','glm-4.7','[\"glm-4.7\"]','historical-fixture',true,4,2,?)",
          bytes(w),
          bytes(user));
      db.update(
          "INSERT INTO team_model_budget(workspace_id,currency,limit_nanos,version,updated_by)"
              + " VALUES (?,'USD',1000,1,?)",
          bytes(w),
          bytes(user));
      db.update(
          "INSERT INTO"
              + " team_model_period(workspace_id,period_start,currency,limit_nanos,pending_nanos)"
              + " VALUES (?,'2026-11-01','USD',1000,900)",
          bytes(w));
      db.update(
          "INSERT INTO"
              + " project_model_policy(project_id,max_calls,max_cost_nanos,max_millis,version,updated_by)"
              + " VALUES (?,20,1000,300000,1,?)",
          bytes(p),
          bytes(user));
      db.update(
          "INSERT INTO"
              + " team_model_run(run_id,workspace_id,project_id,connection_version,policy_version,model,currency,price_version,input_price,output_price,max_calls,max_cost_nanos,max_millis,call_count,occupied_nanos,trigger_source)"
              + " VALUES"
              + " (?,?,?,1,1,'glm-4.7','USD','frozen-price',600,2200,20,1000,300000,2,900,'MANUAL_QUESTION')",
          bytes(run),
          bytes(w),
          bytes(p));
      for (String state : List.of("RESERVED", "UNKNOWN"))
        db.update(
            "INSERT INTO"
                + " team_model_call(id,run_id,attempt_token,workspace_id,project_id,period_start,connection_version,model,currency,price_version,input_price,output_price,input_token_bound,output_token_bound,reserved_nanos,state,purpose)"
                + " VALUES"
                + " (?,?,?,?,?,'2026-11-01',1,'glm-4.7','USD','frozen-price',600,2200,100,10,450,?,'ANSWER')",
            bytes(UUID.randomUUID()),
            bytes(run),
            bytes(UUID.randomUUID()),
            bytes(w),
            bytes(p),
            state);
      db.update(
          "INSERT INTO retrieval_config(id,chunker,analyzer,identity_hash,model_contract) VALUES"
              + " (2,'legacy','legacy','legacy-embedding','{}')");
      db.update(
          "INSERT INTO embedding_budget(run_id,limit_nanousd,reserved_nanousd) VALUES"
              + " ('historical-embedding',1000,900)");
      db.update(
          "INSERT INTO"
              + " embedding_call(id,run_id,config_id,project_id,purpose,input_hash,reserved_nanousd,state)"
              + " VALUES (?,'historical-embedding',2,?,'query',?,900,'UNKNOWN')",
          bytes(UUID.randomUUID()),
          bytes(p),
          "0".repeat(64));
      var oldEmbedding = db.queryForList("SELECT * FROM embedding_call");
      var historical = db.queryForList("SELECT * FROM team_model_call ORDER BY id");
      var period = db.queryForMap("SELECT * FROM team_model_period");
      flyway.migrate();
      assertThat(db.queryForList("SELECT * FROM team_model_call ORDER BY id"))
          .usingRecursiveComparison()
          .isEqualTo(historical);
      assertThat(db.queryForList("SELECT * FROM embedding_call"))
          .usingRecursiveComparison()
          .isEqualTo(oldEmbedding);
      assertThat(
              db.queryForObject(
                  "SELECT reserved_nanousd FROM embedding_budget WHERE"
                      + " run_id='historical-embedding'",
                  Long.class))
          .isEqualTo(900);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_embedding_call", Long.class))
          .isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_embedding_connection", Long.class))
          .isZero();
      assertThat(db.queryForMap("SELECT * FROM team_model_period"))
          .usingRecursiveComparison()
          .isEqualTo(period);
      // Existing policy remains readable; a subsequent edit can omit its obsolete money field.
      db.update("UPDATE project_model_policy SET max_cost_nanos=NULL WHERE project_id=?", bytes(p));
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_execution", Long.class))
          .isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_usage", Long.class)).isZero();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT reserved_nano_usd FROM agent_budget WHERE"
                      + " authorization_id='legacy-team-migration'",
                  Long.class))
          .isEqualTo(900);
      assertThat(
              db.queryForList(
                  "SELECT state FROM agent_model_call WHERE run_id=? ORDER BY state",
                  String.class,
                  bytes(run)))
          .containsExactly("RESERVED", "UNKNOWN");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_period", Long.class))
          .isEqualTo(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_run", Long.class)).isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE"
                      + " CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='agent_model_call'",
                  Long.class))
          .isEqualTo(2);
      flyway.clean();
      flyway.migrate();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM team_model_call", Long.class)).isZero();
    }
  }
}
