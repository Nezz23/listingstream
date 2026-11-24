package dev.nezz.listingstream.api;

import dev.nezz.listingstream.history.ComplianceService;
import dev.nezz.listingstream.history.ListingVersion;
import dev.nezz.listingstream.history.ListingVersionRepository;
import dev.nezz.listingstream.history.ReconciliationService;
import dev.nezz.listingstream.replay.ReplayService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class StreamController {

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
        static <T> PageView<T> of(Page<T> p) {
            return new PageView<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(),
                    p.getTotalPages());
        }
    }

    private final ReplayService replay;
    private final ListingVersionRepository versions;
    private final ComplianceService compliance;
    private final ReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    public StreamController(ReplayService replay, ListingVersionRepository versions, ComplianceService compliance,
                            ReconciliationService reconciliation, JdbcTemplate jdbc) {
        this.replay = replay;
        this.versions = versions;
        this.compliance = compliance;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
    }

    @PostMapping("/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayService.ReplayStatus startReplay() {
        return replay.start();
    }

    @GetMapping("/replay")
    public ReplayService.ReplayStatus replayStatus() {
        return replay.status();
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        return jdbc.queryForList("""
                select snapshot_date, status, reason, expected_events, staged_rows,
                       (select count(*) from dead_letters d where d.snapshot_date = s.snapshot_date) as dead_letters,
                       versions_opened, versions_closed, merge_millis, decided_at
                from snapshots s order by snapshot_date""");
    }

    @GetMapping("/compliance")
    public Map<String, Long> compliance(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate snapshot,
                                        @RequestParam(required = false) String borough) {
        return compliance.compliance(snapshot, borough);
    }

    @GetMapping("/compliance/trend")
    public List<ComplianceService.TrendPoint> trend() {
        return compliance.trend();
    }

    @GetMapping("/listings")
    public PageView<ListingVersion> currentListings(@RequestParam(required = false) String borough,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        return PageView.of(versions.findCurrent(borough, status,
                PageRequest.of(page, size, Sort.by("listingId"))));
    }

    @GetMapping("/listings/{id}/history")
    public List<ListingVersion> history(@PathVariable long id) {
        List<ListingVersion> found = versions.findByListingIdOrderByValidFrom(id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no history for listing " + id);
        }
        return found;
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        return jdbc.queryForList("""
                select id, snapshot_date, source_topic, source_partition, source_offset, event_key, error, received_at
                from dead_letters order by id desc limit 100""");
    }

    @GetMapping("/reconciliation")
    public ReconciliationService.Report reconcile() {
        return reconciliation.run();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
