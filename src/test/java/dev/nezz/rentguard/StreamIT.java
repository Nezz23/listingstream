package dev.nezz.rentguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nezz.rentguard.event.ListingEvent;
import dev.nezz.rentguard.event.SnapshotMarker;
import dev.nezz.rentguard.history.ComplianceService;
import dev.nezz.rentguard.ingest.Topics;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole pipeline against an in-JVM Kafka broker and a real PostgreSQL database: events in, history out.
 * Listings 1-5 are the cast; each snapshot changes them in a way the Type 2 history must record.
 */
@SpringBootTest
@ActiveProfiles("it")
@EmbeddedKafka(partitions = 3, topics = {Topics.LISTINGS, Topics.MARKERS, Topics.DEAD_LETTERS})
class StreamIT {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ComplianceService compliance;
    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    KafkaListenerEndpointRegistry listeners;
    @Autowired
    dev.nezz.rentguard.api.StreamController api;
    @Autowired
    dev.nezz.rentguard.api.LedgerController ledger;
    @Autowired
    dev.nezz.rentguard.ledger.LedgerKeys keys;
    @Autowired
    dev.nezz.rentguard.ai.InspectorTools tools;

    @BeforeEach
    void cleanTables() {
        for (MessageListenerContainer container : listeners.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 3); // the embedded broker gives every topic 3 partitions
        }
        jdbc.execute("truncate staged_listings, snapshots, listing_versions, dead_letters, audit_entries, audit_blocks restart identity");
    }

    private ListingEvent listing(String snapshot, long id, String minNights, String license) {
        return new ListingEvent(snapshot, Long.toString(id), "Listing " + id, "9", "Harlem", "Manhattan",
                "Entire rental unit", "Entire home/apt", "2", minNights, "365", null, license, "$100.00", "40.8",
                "-73.9");
    }

    private void publishSnapshot(String snapshot, List<String> payloads) {
        for (String p : payloads) {
            kafka.send(Topics.LISTINGS, "k", p);
        }
        kafka.send(Topics.MARKERS, snapshot, json.writeValueAsString(new SnapshotMarker(snapshot, payloads.size())));
        kafka.flush();
    }

    private void publish(String snapshot, ListingEvent... events) {
        publishSnapshot(snapshot, java.util.Arrays.stream(events).map(json::writeValueAsString).toList());
    }

    private String status(String snapshot) {
        List<String> s = jdbc.queryForList("select status from snapshots where snapshot_date = ?::date", String.class,
                snapshot);
        return s.isEmpty() ? null : s.getFirst();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private List<Map<String, Object>> versions(long listing) {
        return jdbc.queryForList("""
                select valid_from::text as valid_from, valid_to::text as valid_to, is_current, ll18_status
                from listing_versions where listing_id = ? order by valid_from""", listing);
    }

    @Test
    void theHistoryRecordsChangesDisappearancesAndReturns() {
        String d1 = "2026-01-01", d2 = "2026-02-01", d3 = "2026-03-01", d4 = "2026-04-01";
        publish(d1, listing(d1, 1, "2", null), listing(d1, 2, "2", null), listing(d1, 3, "30", null),
                listing(d1, 4, "2", "OSE-STRREG-1"), listing(d1, 5, "2", null), listing(d1, 6, "2", null));
        await(() -> "applied".equals(status(d1)));

        // 2: moves to a 45-night minimum. 3: vanishes. 7: new. Others unchanged.
        publish(d2, listing(d2, 1, "2", null), listing(d2, 2, "45", null), listing(d2, 4, "2", "OSE-STRREG-1"),
                listing(d2, 5, "2", null), listing(d2, 6, "2", null), listing(d2, 7, "2", null));
        await(() -> "applied".equals(status(d2)));

        // A partial scrape: 2 rows against 6 the last time.
        publish(d3, listing(d3, 1, "2", null), listing(d3, 2, "45", null));
        await(() -> "quarantined".equals(status(d3)));

        // 3 returns unchanged; it still gets a new version because it was gone in between.
        publish(d4, listing(d4, 1, "2", null), listing(d4, 2, "45", null), listing(d4, 3, "30", null),
                listing(d4, 4, "2", "OSE-STRREG-1"), listing(d4, 5, "2", null), listing(d4, 6, "2", null),
                listing(d4, 7, "2", null));
        await(() -> "applied".equals(status(d4)));

        assertThat(versions(1)).hasSize(1);
        assertThat(versions(2)).extracting(v -> v.get("ll18_status"))
                .containsExactly("unregistered_short_term", "long_term_30_plus");
        assertThat(versions(2).getFirst().get("valid_to")).isEqualTo(d2);
        assertThat(versions(3)).extracting(v -> v.get("valid_from")).containsExactly(d1, d4);
        assertThat(versions(3).getFirst().get("valid_to")).isEqualTo(d2);
        assertThat(versions(7)).hasSize(1);
        assertThat(jdbc.queryForObject("select reason from snapshots where snapshot_date = ?::date", String.class, d3))
                .startsWith("volume: 2 rows is a 67% drop");
        assertThat(compliance.compliance(LocalDate.parse(d4), "Manhattan"))
                .containsEntry("registered", 1L).containsEntry("long_term_30_plus", 2L)
                .containsEntry("unregistered_short_term", 4L);

        // One signed block per decision, quarantine included, and the chain checks out.
        assertThat(ledger.blocks()).extracting(b -> b.get("decision").toString().split(":")[0])
                .containsExactly("applied", "applied", "quarantined", "applied");
        var report = ledger.verify();
        assertThat(report.valid()).isTrue();
        assertThat(report.blocks()).isEqualTo(4);
        // Block 1 (d2): 2 went long-term, 3 was delisted, 7 appeared. Unchanged listings are not recorded.
        assertThat(jdbc.queryForList("""
                select listing_id || ':' || coalesce(old_status, '-') || '>' || coalesce(new_status, '-')
                from audit_entries where block_height = 1 and kind = 'status_change' order by listing_id""",
                String.class)).containsExactly("2:unregistered_short_term>long_term_30_plus",
                "3:long_term_30_plus>-", "7:->unregistered_short_term");

        // A receipt for listing 2 checks out with nothing but the public key: Merkle path, header hash, signature.
        var receipts = ledger.receipts(2);
        assertThat(receipts).hasSize(2);
        for (var r : receipts) {
            assertThat(dev.nezz.rentguard.ledger.MerkleTree.verify(r.entryHash(), r.proof(), r.header().merkleRoot()))
                    .isTrue();
            assertThat(r.header().hash()).isEqualTo(r.blockHash());
            assertThat(keys.verify(r.blockHash(), r.signature())).isTrue();
        }

        // Someone quietly rewrites listing 2's status change in the database: the ledger names the block and listing.
        jdbc.update("update audit_entries set new_status = 'registered' where block_height = 1 and listing_id = 2");
        var tampered = ledger.verify();
        assertThat(tampered.valid()).isFalse();
        assertThat(tampered.firstInvalidBlock()).isEqualTo(1);
        assertThat(tampered.reason()).contains("listing 2");

        // Recomputing the entry hash to cover the edit just moves the failure to the Merkle root.
        jdbc.update("update audit_entries set entry_hash = ? where block_height = 1 and listing_id = 2",
                new dev.nezz.rentguard.ledger.AuditEntry("status_change", 2L, "unregistered_short_term", "registered",
                        null).hash(LocalDate.parse(d2)));
        assertThat(ledger.verify().reason()).isEqualTo("Merkle root does not match the entries");
    }

    @Test
    void refusedEventsAreDeadLetteredAndTheSnapshotStillCompletes() {
        String d = "2026-05-01";
        ListingEvent badId = listing(d, 0, "2", null);
        badId = new ListingEvent(d, "not-a-number", badId.name(), badId.hostId(), badId.neighbourhood(),
                badId.borough(), badId.propertyType(), badId.roomType(), badId.accommodates(), "2", "365", null,
                null, "$1", "0", "0");
        publishSnapshot(d, List.of(json.writeValueAsString(listing(d, 1, "2", null)),
                json.writeValueAsString(listing(d, 2, "2", null)), json.writeValueAsString(badId)));
        await(() -> "applied".equals(status(d)));

        assertThat(jdbc.queryForObject("select count(*) from staged_listings", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select error from dead_letters", String.class))
                .singleElement().asString().contains("listing id");

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "dlt-check", true);
        props.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props)
                .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, Topics.DEAD_LETTERS);
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            assertThat(new String(records.iterator().next().headers().lastHeader("x-error").value()))
                    .contains("listing id");
        }
    }

    @Test
    void replayingTheSameEventsChangesNothing() {
        String d = "2026-06-01";
        ListingEvent[] events = {listing(d, 1, "2", null), listing(d, 2, "40", null), listing(d, 3, "2", "EXEMPT")};
        publish(d, events);
        await(() -> "applied".equals(status(d)));
        long versionsBefore = jdbc.queryForObject("select count(*) from listing_versions", Long.class);

        publish(d, events); // redelivery of the whole snapshot, marker included
        publish(d, events);
        await(() -> jdbc.queryForObject("select count(*) from staged_listings", Long.class) == 3);
        try {
            Thread.sleep(1_500); // let the duplicate batches drain through the listener
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(jdbc.queryForObject("select count(*) from staged_listings", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from listing_versions", Long.class))
                .isEqualTo(versionsBefore);
        assertThat(status(d)).isEqualTo("applied");
    }

    @Test
    void theApiPagesAndFiltersCurrentListingsAndExplainsMissingOnes() {
        String d = "2026-07-01";
        publish(d, listing(d, 11, "2", null), listing(d, 12, "40", null), listing(d, 13, "2", "OSE-STRREG-9"),
                listing(d, 14, "2", null), listing(d, 15, "2", null));
        await(() -> "applied".equals(status(d)));

        var firstPage = api.currentListings(null, null, 0, 2);
        assertThat(firstPage.totalItems()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(v -> v.getListingId()).containsExactly(11L, 12L);

        var unregistered = api.currentListings("Manhattan", "unregistered_short_term", 0, 20);
        assertThat(unregistered.items()).extracting(v -> v.getListingId()).containsExactly(11L, 14L, 15L);

        assertThat(api.history(13)).singleElement().satisfies(v -> assertThat(v.getLicense()).isEqualTo("OSE-STRREG-9"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.history(999))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.currentListings(null, null, 0, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(api.trend()).singleElement().satisfies(p -> {
            assertThat(p.listings()).isEqualTo(5);
            assertThat(p.unregisteredShortTerm()).isEqualTo(3);
        });
    }

    @Test
    void theAssistantToolsAnswerFromTheDatabase() {
        String d1 = "2026-08-01", d2 = "2026-09-01";
        publish(d1, listing(d1, 21, "2", null), listing(d1, 22, "2", null), listing(d1, 23, "40", null));
        await(() -> "applied".equals(status(d1)));
        publish(d2, listing(d2, 21, "2", null), listing(d2, 22, "2", "EXEMPT"), listing(d2, 23, "40", null));
        await(() -> "applied".equals(status(d2)));

        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) tools.call("compliance_counts", Map.of("borough", "manhattan"));
        assertThat(counts).containsEntry("snapshot", d2).containsEntry("total_listings", 3L);
        @SuppressWarnings("unchecked")
        var byStatus = (List<Map<String, Object>>) counts.get("by_status");
        assertThat(byStatus).hasSize(5); // every status, zeros included: "0 registered" is an answer too
        assertThat(byStatus).filteredOn(r -> r.get("status").equals("registered"))
                .singleElement().satisfies(r -> assertThat(((Number) r.get("listings")).longValue()).isZero());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.call("compliance_counts",
                Map.of("snapshot", "2020-01-01"))).hasMessageContaining("not known");

        @SuppressWarnings("unchecked")
        var hosts = (Map<String, Object>) tools.call("top_hosts", Map.of("limit", 1));
        assertThat((List<?>) hosts.get("hosts")).singleElement()
                .satisfies(h -> assertThat(((Number) ((Map<?, ?>) h).get("listings")).longValue()).isEqualTo(1));

        @SuppressWarnings("unchecked")
        var history = (Map<String, Object>) tools.call("listing_history", Map.of("listing_id", 22));
        assertThat((List<?>) history.get("status_periods")).hasSize(2);

        assertThat((List<?>) tools.call("status_trend", Map.of())).hasSize(2);
        assertThat((List<?>) tools.call("snapshot_decisions", Map.of())).hasSize(2);
        @SuppressWarnings("unchecked")
        var rules = (List<Map<String, Object>>) tools.call("search_rules", Map.of("query", "how do hosts register?"));
        assertThat(rules).extracting(r -> r.get("id")).contains("R2");
        assertThat((List<?>) tools.call("search_rules", Map.of("query", "the and of"))).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.call("drop_tables", Map.of()))
                .hasMessageContaining("unknown tool");
        assertThat(tools.definitions()).hasSize(6);
    }
}
