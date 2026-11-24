package dev.nezz.rentguard.ingest;

import dev.nezz.rentguard.event.EventContract;
import dev.nezz.rentguard.event.InvalidEventException;
import dev.nezz.rentguard.event.ListingEvent;
import dev.nezz.rentguard.event.ListingRecord;
import dev.nezz.rentguard.event.SnapshotMarker;
import dev.nezz.rentguard.history.SnapshotProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes listing events in batches (one batch per poll, per partition thread). Valid events are written to
 * staging in one JDBC batch; refused events go to the dead-letter topic and table with the reason. Offsets are
 * committed only after the batch is stored, so a crash means redelivery, which the staging primary key absorbs.
 */
@Component
public class ListingListener {

    private static final Logger log = LoggerFactory.getLogger(ListingListener.class);

    private final JsonMapper json;
    private final StagingRepository staging;
    private final SnapshotProcessor processor;
    private final KafkaTemplate<String, String> kafka;
    private final Counter accepted;
    private final Counter duplicates;
    private final Counter deadLettered;

    public ListingListener(JsonMapper json, StagingRepository staging, SnapshotProcessor processor,
                           KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.json = json;
        this.staging = staging;
        this.processor = processor;
        this.kafka = kafka;
        this.accepted = meters.counter("rentguard.events", "result", "staged");
        this.duplicates = meters.counter("rentguard.events", "result", "duplicate");
        this.deadLettered = meters.counter("rentguard.events", "result", "dead_lettered");
    }

    @KafkaListener(id = "listings", topics = Topics.LISTINGS, concurrency = "3", batch = "true")
    public void onListings(List<ConsumerRecord<String, String>> records) {
        List<ListingRecord> valid = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                valid.add(EventContract.validate(json.readValue(record.value(), ListingEvent.class)));
            } catch (InvalidEventException e) {
                deadLetter(record, e.getMessage(), e.snapshotDate());
            } catch (JacksonException e) {
                deadLetter(record, "malformed JSON: " + e.getOriginalMessage(), null);
            }
        }
        if (!valid.isEmpty()) {
            int inserted = staging.insert(valid);
            accepted.increment(inserted);
            duplicates.increment(valid.size() - inserted);
        }
        processor.tryApply();
    }

    @KafkaListener(id = "markers", topics = Topics.MARKERS)
    public void onMarker(String payload) {
        SnapshotMarker marker = json.readValue(payload, SnapshotMarker.class);
        staging.recordMarker(LocalDate.parse(marker.snapshotDate()), marker.expectedEvents());
        processor.tryApply();
    }

    private void deadLetter(ConsumerRecord<String, String> record, String error, LocalDate snapshot) {
        ProducerRecord<String, String> dlt = new ProducerRecord<>(Topics.DEAD_LETTERS, record.key(), record.value());
        dlt.headers().add("x-error", error.getBytes(StandardCharsets.UTF_8));
        dlt.headers().add("x-source", (record.topic() + "/" + record.partition() + "/" + record.offset())
                .getBytes(StandardCharsets.UTF_8));
        kafka.send(dlt).join();
        staging.deadLetter(snapshot, record.topic(), record.partition(), record.offset(), record.key(),
                record.value(), error);
        deadLettered.increment();
        log.warn("dead-lettered {}/{}/{}: {}", record.topic(), record.partition(), record.offset(), error);
    }
}
