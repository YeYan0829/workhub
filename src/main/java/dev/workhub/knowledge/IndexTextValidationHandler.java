package dev.workhub.knowledge;

import static dev.workhub.common.Ids.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.common.Inputs;
import dev.workhub.messaging.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Phase 2 local validation receipt only; this is not an Elasticsearch indexing implementation. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="workhub.retrieval.enabled", havingValue="false", matchIfMissing=true)
public class IndexTextValidationHandler implements WorkHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IndexTextValidationHandler(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String kind() {
        return "INDEX_TEXT";
    }

    public void lockBusiness(WorkJobRepository.Job job) {
        jdbc.queryForList(
                "SELECT id FROM knowledge_source WHERE id=? FOR UPDATE", bytes(job.owner()));
    }

    public Result execute(WorkJobRepository.Job job) {
        try {
            var payload = mapper.readTree(job.payload());
            if (payload.size() != 2
                    || !payload.path("sourceId").asText().equals(job.owner().toString())
                    || !payload.path("sourceRevision").isIntegralNumber()
                    || payload.path("sourceRevision").longValue() < 0)
                throw new WorkFailure(false, "INVALID_SOURCE_PAYLOAD");
            var rows =
                    jdbc.queryForList(
                            "SELECT source_revision,body_hash,active FROM knowledge_source WHERE"
                                + " id=? AND project_id=?",
                            bytes(job.owner()),
                            bytes(job.project()));
            if (rows.size() != 1) throw new WorkFailure(false, "SOURCE_NOT_FOUND");
            long requested = payload.path("sourceRevision").longValue();
            long current = ((Number) rows.get(0).get("source_revision")).longValue();
            if (current < requested) throw new WorkFailure(false, "SOURCE_REVISION_AHEAD");
            String result =
                    "phase2-validation/"
                            + job.owner()
                            + "/"
                            + requested
                            + (current == requested ? "/validated" : "/obsolete");
            return new Result(result, Inputs.hash(result));
        } catch (WorkFailure e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new WorkFailure(false, "INVALID_SOURCE_PAYLOAD");
        }
    }
}
