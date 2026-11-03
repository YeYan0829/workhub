package dev.workhub.messaging;

import com.fasterxml.jackson.databind.*;

import dev.workhub.common.Inputs;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class EnvelopeValidator {
    private final ObjectMapper mapper;

    public EnvelopeValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public UUID identity(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean matches(
            byte[] body,
            String routingKey,
            OutboxRepository.Envelope e,
            WorkJobRepository.Job job) {
        if (e == null
                || job == null
                || body.length > 16384
                || !("GITHUB_WORK".equals(job.handler())
                        ? "github.work".equals(routingKey)
                        : "RUN_AGENT".equals(job.handler())
                                ? "agent.read".equals(routingKey)
                        : "EMBED_CHUNK".equals(job.handler())
                                ? "knowledge.embedding".equals(routingKey)
                                : Set.of("MAIL_DELIVERY", "CSV_ROW").contains(job.handler())
                                ? "business.work".equals(routingKey)
                                : "knowledge.index".equals(routingKey))
                || !routingKey.equals(e.destination())
                || e.schema() != 1
                || !Objects.equals(e.jobId(), job.id())
                || !e.handler().equals(job.handler())
                || !job.kind().equals(job.handler())) return false;
        // The publisher sends these exact durable bytes, not MySQL's reformatted JSON.
        if (!Arrays.equals(body, e.wire().getBytes(StandardCharsets.UTF_8))
                || !Inputs.hash(e.wire()).equals(e.hash())) return false;
        try {
            JsonNode node = mapper.readTree(body);
            return node.isObject()
                    && node.size() == 5
                    && node.path("schemaVersion").isIntegralNumber()
                    && node.path("schemaVersion").intValue() == 1
                    && node.path("jobId").asText().equals(job.id().toString())
                    && (job.project() == null
                            ? node.path("projectId").isNull()
                            : node.path("projectId").asText().equals(job.project().toString()))
                    && node.path("kind").asText().equals(job.kind())
                    && node.path("payloadHash").asText().equals(job.payloadHash());
        } catch (Exception ex) {
            return false;
        }
    }
}
