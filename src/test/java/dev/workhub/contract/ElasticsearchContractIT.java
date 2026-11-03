package dev.workhub.contract;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.net.URI;
import org.junit.jupiter.api.*;
import java.io.StringReader;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ElasticsearchContractIT {
    private Rest5ClientTransport transport;
    private ElasticsearchClient client;
    private final String index="p0-contract-"+UUID.randomUUID();
    private static final String MAPPING="""
        {"settings":{"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"-1"},
         "mappings":{"dynamic":"strict","properties":{
          "project":{"type":"keyword"},"title":{"type":"text","similarity":"BM25"},
          "embedding":{"type":"dense_vector","dims":3,"index":false}}}}
        """;
    @BeforeEach void setup() throws Exception {
        var rest=Rest5Client.builder(URI.create("http://127.0.0.1:"+ContractSupport.env("ELASTICSEARCH_PORT","19200"))).build();
        transport=new Rest5ClientTransport(rest,new JacksonJsonpMapper());client=new ElasticsearchClient(transport);
        assertThat(client.info().version().number()).startsWith("9.5.");
        client.indices().create(c -> c.index(index).withJson(new StringReader(MAPPING)));
    }
    @AfterEach void cleanup() throws Exception {
        if(transport!=null) { try { if(client.indices().exists(e -> e.index(index)).value())client.indices().delete(d -> d.index(index)); } finally {transport.close();} }
    }
    private Map<String,Object> doc(String project,String title,List<Double> vector) {
        return Map.of("project",project,"title",title,"embedding",vector);
    }
    @Test void bm25AndExactCosineRespectProjectFilter() throws Exception {
        client.create(c -> c.index(index).id("a").document(doc("p1","WSL network timeout",List.of(1d,0d,0d))));
        client.create(c -> c.index(index).id("b").document(doc("p1","WSL unrelated database migration",List.of(0d,1d,0d))));
        client.create(c -> c.index(index).id("foreign").document(doc("p2","WSL network timeout",List.of(1d,0d,0d))));
        client.indices().refresh(r -> r.index(index));
        var lexical=client.search(s -> s.index(index).withJson(new StringReader("""
           {"query":{"bool":{"must":[{"match":{"title":"WSL timeout"}}],"filter":[{"term":{"project":"p1"}}]}}}
           """)),Map.class);
        assertThat(lexical.hits().hits()).extracting(h -> h.id()).containsExactly("a","b");
        assertThat(lexical.hits().hits().get(0).score()).isGreaterThan(lexical.hits().hits().get(1).score());
        var vector=client.search(s -> s.index(index).withJson(new StringReader("""
           {"query":{"script_score":{"query":{"term":{"project":"p1"}},"script":{
             "source":"cosineSimilarity(params.q, 'embedding') + 1.0","params":{"q":[1.0,0.0,0.0]}}}}}
           """)),Map.class);
        assertThat(vector.hits().hits()).extracting(h -> h.id()).containsExactly("a","b");
        assertThat(vector.hits().hits().get(0).score()).isCloseTo(2d,within(0.0001));
    }
    @Test void createOnlyDetectsConflictWithoutOverwrite() throws Exception {
        client.create(c -> c.index(index).id("same").document(doc("p1","first",List.of(1d,0d,0d))));
        assertThatThrownBy(() -> client.create(c -> c.index(index).id("same").document(doc("p1","second",List.of(1d,0d,0d)))))
                .satisfies(e -> {
                    // Both typed API and low-level transport errors must preserve HTTP 409.
                    if(e instanceof ElasticsearchException typed) assertThat(typed.status()).isEqualTo(409);
                    else if(e instanceof co.elastic.clients.transport.rest5_client.low_level.ResponseException http)
                        assertThat(http.getResponse().getStatusCode()).isEqualTo(409);
                    else fail("Expected a detectable HTTP 409 conflict",e);
                });
        var stored=client.get(g -> g.index(index).id("same"),Map.class);
        assertThat(stored.source().get("title")).isEqualTo("first");
    }
    @Test void bulkPartialFailureIsVisiblePerItem() throws Exception {
        var result=client.bulk(b -> b
            .operations(o -> o.create(c -> c.index(index).id("good").document(doc("p1","valid",List.of(1d,0d,0d)))))
            .operations(o -> o.create(c -> c.index(index).id("bad").document(Map.of("undeclared","fails strict mapping")))));
        assertThat(result.errors()).isTrue();assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).status()).isEqualTo(201);assertThat(result.items().get(0).error()).isNull();
        assertThat(result.items().get(1).status()).isEqualTo(400);assertThat(result.items().get(1).error()).isNotNull();
    }
    @Test void explicitRefreshMakesWritesSearchable() throws Exception {
        assertThat(client.count(c -> c.index(index)).count()).isZero();
        client.create(c -> c.index(index).id("new").document(doc("p1","refresh",List.of(1d,0d,0d))));
        assertThat(client.count(c -> c.index(index)).count()).isZero();
        client.indices().refresh(r -> r.index(index));
        assertThat(client.count(c -> c.index(index)).count()).isEqualTo(1);
    }
}
