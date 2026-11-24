package dev.nezz.listingstream;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nezz.listingstream.history.ReconciliationService;
import dev.nezz.listingstream.ingest.Topics;
import dev.nezz.listingstream.replay.ReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.ActiveProfiles;

/** The producer path on a committed sample: the first 50 real listings of two scrapes. Runs in CI. */
@SpringBootTest(properties = "listingstream.data-dir=src/test/resources/fixtures")
@ActiveProfiles("it")
@EmbeddedKafka(partitions = 3, topics = {Topics.LISTINGS, Topics.MARKERS, Topics.DEAD_LETTERS})
class ReplayIT {

    @Autowired
    ReplayService replay;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void replayingTheSampleFilesStagesEveryEventAndDecidesBothSnapshots() throws InterruptedException {
        listeners.getListenerContainers().forEach(c -> ContainerTestUtils.waitForAssignment(c, 3));
        jdbc.execute("truncate staged_listings, snapshots, listing_versions, dead_letters restart identity");

        assertThat(replay.start().state()).isEqualTo("running");
        long deadline = System.currentTimeMillis() + 30_000;
        while (jdbc.queryForObject("select count(*) from snapshots where status = 'applied'", Long.class) < 2) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(100);
        }

        assertThat(replay.status().eventsPublished()).isEqualTo(100);
        assertThat(replay.status().snapshots()).extracting(ReplayService.SnapshotPublished::events)
                .containsExactly(50L, 50L);
        assertThat(jdbc.queryForObject("select count(*) from staged_listings", Long.class)).isEqualTo(100);
        // The sample is not the full dataset, so the report runs every check but cannot match the warehouse
        ReconciliationService.Report report = reconciliation.run();
        assertThat(report.checks()).isEqualTo(14);
        assertThat(report.allMatch()).isFalse();
    }
}
