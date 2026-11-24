package dev.nezz.rentguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.nezz.rentguard.history.ReconciliationService;
import dev.nezz.rentguard.ingest.Topics;
import dev.nezz.rentguard.replay.ReplayService;
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
@SpringBootTest(properties = "rentguard.data-dir=data")
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
    @Autowired
    dev.nezz.rentguard.ledger.LedgerVerifier ledger;

    @Test
    void theStreamReproducesTheBatchWarehouseExactly() throws InterruptedException {
        assumeTrue(Files.isDirectory(Path.of("data")) && !replay.snapshotFiles().isEmpty(),
                "scrape files not present");
        listeners.getListenerContainers().forEach(
                c -> org.springframework.kafka.test.utils.ContainerTestUtils.waitForAssignment(c, 3));
        jdbc.execute("truncate staged_listings, snapshots, listing_versions, dead_letters, audit_entries, audit_blocks restart identity");

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

        var audit = ledger.verify();
        assertThat(audit.valid()).as(audit.reason()).isTrue();
        assertThat(audit.blocks()).isEqualTo(10);
        System.out.printf("LEDGER blocks=%d entries=%d verifyMillis=%d%n", audit.blocks(), audit.entries(),
                audit.millis());
        jdbc.query("select b.snapshot_date, b.entry_count, e.detail from audit_blocks b join audit_entries e "
                + "on e.block_height = b.height and e.position = 0 order by b.height",
                rs -> {
                    System.out.printf("LEDGER %s entries=%d %s%n", rs.getDate(1), rs.getInt(2), rs.getString(3));
                });
    }
}
