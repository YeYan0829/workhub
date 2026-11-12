package dev.workhub.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.agent.ModelAdmission;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Controlled comparison: independent logical buckets versus one real shared provider quota. */
@EnabledIfEnvironmentVariable(named = "WORKHUB_BENCH_MODEL_ADMISSION", matches = "true")
class ModelAdmissionComparisonIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void independentAndSharedBucketsAgainstTheSamePhysicalProvider() throws Exception {
        Path root =
                Path.of("target/model-admission-comparison", UUID.randomUUID().toString())
                        .toAbsolutePath();
        Files.createDirectories(root);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int repetition = 1; repetition <= 3; repetition++) {
            results.add(run(root, false, repetition));
            results.add(run(root, true, repetition));
        }
        JSON.writeValue(
                root.resolve("summary.json").toFile(),
                Map.of(
                        "fixtureProvider",
                        true,
                        "governorRate",
                        2,
                        "governorBurst",
                        1,
                        "providerRate",
                        2.5,
                        "providerBurst",
                        2,
                        "baseline",
                        "two independent logical buckets simulate per-instance accounting",
                        "results",
                        results));
        for (var result : results) {
            assertThat(((Number) result.get("httpRequests")).intValue()).isEqualTo(12);
            if (Boolean.TRUE.equals(result.get("shared"))) {
                assertThat(((Number) result.get("provider429")).intValue()).isZero();
                assertThat(((Number) result.get("sharedIntervalViolations")).longValue()).isZero();
            } else {
                assertThat(((Number) result.get("provider429")).intValue()).isPositive();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(Path root, boolean shared, int repetition) throws Exception {
        String label = (shared ? "shared" : "independent") + "-" + repetition;
        Path directory = root.resolve(label);
        Files.createDirectories(directory);
        List<ModelAdmission> gates = new ArrayList<>();
        List<Process> children = new ArrayList<>();
        List<Map<String, Object>> arrivals = new CopyOnWriteArrayList<>();
        String common = "compare-" + UUID.randomUUID();
        List<String> scopes =
                shared ? List.of(common, common) : List.of(common + "-0", common + "-1");
        long began = System.nanoTime();
        try (var provider = new MockWebServer()) {
            provider.setDispatcher(
                    new Dispatcher() {
                        double tokens = 2;
                        long at = System.nanoTime();

                        @Override
                        public synchronized MockResponse dispatch(RecordedRequest request) {
                            long now = System.nanoTime();
                            tokens = Math.min(2, tokens + (now - at) * 2.5 / 1_000_000_000d);
                            at = now;
                            int status = tokens >= 1 ? 200 : 429;
                            if (status == 200) tokens--;
                            arrivals.add(
                                    Map.of(
                                            "elapsedMs",
                                            (now - began) / 1_000_000d,
                                            "path",
                                            request.getPath(),
                                            "status",
                                            status));
                            return new MockResponse().setResponseCode(status).setBody("fixture");
                        }
                    });
            provider.start();
            for (String scope : scopes.stream().distinct().toList()) {
                var gate = ModelAdmissionIT.gate(scope, 2, 1);
                gates.add(gate);
                ModelAdmissionIT.initialize(gate, scope, 2, 1);
            }
            for (int worker = 0; worker < 2; worker++) {
                children.add(
                        new ProcessBuilder(
                                        Path.of(System.getProperty("java.home"), "bin/java")
                                                .toString(),
                                        "-Xmx192m",
                                        "-XX:ActiveProcessorCount=1",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        ModelAdmissionIT.class.getName(),
                                        scopes.get(worker),
                                        directory.toString(),
                                        String.valueOf(worker),
                                        provider.url("/model?worker=" + worker).toString())
                                .redirectErrorStream(true)
                                .redirectOutput(
                                        directory.resolve("child-" + worker + ".log").toFile())
                                .start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!(Files.exists(directory.resolve("ready-0"))
                    && Files.exists(directory.resolve("ready-1")))) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(20);
            }
            Files.createFile(directory.resolve("go"));
            for (var child : children) {
                assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isZero();
            }
            List<Map<String, Object>> permits = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                permits.addAll(
                        JSON.readValue(
                                directory.resolve("permits-" + worker + ".json").toFile(),
                                List.class));
            }
            permits.sort(Comparator.comparingLong(p -> ((Number) p.get("permitAt")).longValue()));
            long violations = 0;
            for (int first = 0; first < permits.size(); first++) {
                for (int last = first; last < permits.size(); last++) {
                    long elapsed =
                            ((Number) permits.get(last).get("permitAt")).longValue()
                                    - ((Number) permits.get(first).get("permitAt")).longValue();
                    if (last - first + 1 > 1 + 2 * elapsed / 1000d + 0.002) violations++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("label", label);
            result.put("shared", shared);
            result.put("httpRequests", provider.getRequestCount());
            result.put(
                    "provider429",
                    arrivals.stream().filter(a -> a.get("status").equals(429)).count());
            result.put("sharedIntervalViolations", violations);
            result.put("providerArrivals", arrivals);
            result.put("permits", permits);
            JSON.writeValue(directory.resolve("result.json").toFile(), result);
            return result;
        } finally {
            for (var child : children) if (child.isAlive()) child.destroyForcibly();
            for (var gate : gates) gate.close();
        }
    }
}
