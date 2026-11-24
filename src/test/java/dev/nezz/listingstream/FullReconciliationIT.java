package dev.nezz.listingstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.nezz.listingstream.history.ReconciliationService;
import dev.nezz.listingstream.ingest.Topics;
import dev.nezz.listingstream.replay.ReplayService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * Streams all 284,472 real listing events and requires every reconciliation check against the batch warehouse to
 * pass. Needs the exported scrape files in ./data (14 MB, not committed), so CI skips it.
 */
@SpringBootTest(properties = "listingstream.data-dir=data")
@ActiveProfiles("it")
@EmbeddedKafka(partitions = 3, topics = {Topics.LISTINGS, Topics.MARKERS, Topics.DEAD_LETTERS})
class FullReconciliationIT {

    @Autowired
    ReplayService replay;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

    @Test
    void theStreamReproducesTheBatchWarehouseExactly() throws InterruptedException {
        assumeTrue(Files.isDirectory(Path.of("data")) && !replay.snapshotFiles().isEmpty(),
                "scrape files not present");
        listeners.getListenerContainers().forEach(
                c -> org.springframework.kafka.test.utils.ContainerTestUtils.waitForAssignment(c, 3));
        jdbc.execute("truncate staged_listings, snapshots, listing_versions, dead_letters restart identity");

        replay.start();
        long deadline = System.currentTimeMillis() + 180_000;
        while (jdbc.queryForObject("select count(*) from snapshots where status <> 'pending'", Long.class) < 10) {
            assertThat(System.currentTimeMillis()).as("all snapshots decided in time").isLessThan(deadline);
            Thread.sleep(250);
        }

        ReconciliationService.Report report = reconciliation.run();
        assertThat(report.details()).allSatisfy(check -> assertThat(check.match()).as(check.name()).isTrue());
        assertThat(report.checks()).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from staged_listings", Long.class)).isEqualTo(284_472);
    }
}
