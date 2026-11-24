package dev.nezz.listingstream.history;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingVersionRepository extends JpaRepository<ListingVersion, Long> {

    List<ListingVersion> findByListingIdOrderByValidFrom(long listingId);

    /** Current listings with optional filters; served by the partial index on (borough, ll18_status). */
    @Query("""
            select v from ListingVersion v
            where v.current = true
              and (:borough is null or v.borough = :borough)
              and (:status is null or v.ll18Status = :status)""")
    Page<ListingVersion> findCurrent(@Param("borough") String borough, @Param("status") String status,
                                     Pageable pageable);

    long countByCurrentTrue();

    @Query("select count(distinct v.listingId) from ListingVersion v")
    long countDistinctListings();
}
