package dev.nezz.rentguard.replay;

import dev.nezz.rentguard.event.ListingEvent;
import dev.nezz.rentguard.event.SnapshotMarker;
import dev.nezz.rentguard.ingest.Topics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the scrape files back into Kafka in date order, as if each scrape were arriving live: every listing as an
 * event keyed by listing id, then a marker with the snapshot's event count.
 */
@Service
public class ReplayService {

    private static final Pattern FILE = Pattern.compile("listings_(\\d{4}-\\d{2}-\\d{2})\\.jsonl\\.gz");

    public record SnapshotPublished(String snapshotDate, long events, long millis) {
    }

    public record ReplayStatus(String state, Instant startedAt, Instant finishedAt, long eventsPublished,
                               List<SnapshotPublished> snapshots, String error) {
    }

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Path dataDir;
    private final AtomicReference<ReplayStatus> status =
            new AtomicReference<>(new ReplayStatus("idle", null, null, 0, List.of(), null));

    public ReplayService(KafkaTemplate<String, String> kafka, JsonMapper json,
                         @Value("${rentguard.data-dir:data}") String dataDir) {
        this.kafka = kafka;
        this.json = json;
        this.dataDir = Path.of(dataDir);
    }

    public ReplayStatus status() {
        return status.get();
    }

    /** Starts a replay on a virtual thread; returns immediately. */
    public synchronized ReplayStatus start() {
        if ("running".equals(status.get().state())) {
            throw new IllegalStateException("a replay is already running");
        }
        List<Path> files = snapshotFiles();
        if (files.isEmpty()) {
            throw new IllegalStateException("no listings_YYYY-MM-DD.jsonl.gz files in " + dataDir.toAbsolutePath());
        }
        ReplayStatus started = new ReplayStatus("running", Instant.now(), null, 0, List.of(), null);
        status.set(started);
        Thread.ofVirtual().name("replay").start(() -> run(files, started.startedAt()));
        return started;
    }

    public List<Path> snapshotFiles() {
        try (Stream<Path> paths = Files.list(dataDir)) {
            return paths.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void run(List<Path> files, Instant startedAt) {
        List<SnapshotPublished> done = new ArrayList<>();
        long total = 0;
        try {
            for (Path file : files) {
                Matcher m = FILE.matcher(file.getFileName().toString());
                m.matches();
                String snapshot = m.group(1);
                long t0 = System.nanoTime();
                long events = publishFile(file, snapshot);
                // The marker goes out only after every event of the snapshot is acknowledged by the broker
                kafka.send(Topics.MARKERS, snapshot, json.writeValueAsString(new SnapshotMarker(snapshot, events)))
                        .join();
                total += events;
                done.add(new SnapshotPublished(snapshot, events, (System.nanoTime() - t0) / 1_000_000));
                status.set(new ReplayStatus("running", startedAt, null, total, List.copyOf(done), null));
            }
            status.set(new ReplayStatus("finished", startedAt, Instant.now(), total, List.copyOf(done), null));
        } catch (Exception e) {
            status.set(new ReplayStatus("failed", startedAt, Instant.now(), total, List.copyOf(done), e.toString()));
        }
    }

    private long publishFile(Path file, String snapshot) throws IOException {
        long count = 0;
        List<CompletableFuture<?>> pending = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode raw = json.readTree(line);
                ListingEvent event = new ListingEvent(snapshot, text(raw, "id"), text(raw, "name"),
                        text(raw, "host_id"), text(raw, "neighbourhood_cleansed"),
                        text(raw, "neighbourhood_group_cleansed"), text(raw, "property_type"),
                        text(raw, "room_type"), text(raw, "accommodates"), text(raw, "minimum_nights"),
                        text(raw, "maximum_nights"), text(raw, "instant_bookable"), text(raw, "license"),
                        text(raw, "price"), text(raw, "latitude"), text(raw, "longitude"));
                pending.add(kafka.send(Topics.LISTINGS, event.id(), json.writeValueAsString(event)));
                count++;
            }
        }
        kafka.flush();
        // join() throws if any send failed, so a lost event fails the replay instead of passing silently
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        return count;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
