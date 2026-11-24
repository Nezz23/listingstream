package dev.nezz.rentguard.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class Topics {

    public static final String LISTINGS = "listing-events";
    public static final String MARKERS = "snapshot-markers";
    public static final String DEAD_LETTERS = "listing-events.DLT";

    /** Keyed by listing id across 3 partitions, so the three listener threads share the load. */
    @Bean
    NewTopic listingEvents() {
        return TopicBuilder.name(LISTINGS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic snapshotMarkers() {
        return TopicBuilder.name(MARKERS).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic deadLetters() {
        return TopicBuilder.name(DEAD_LETTERS).partitions(1).replicas(1).build();
    }
}
