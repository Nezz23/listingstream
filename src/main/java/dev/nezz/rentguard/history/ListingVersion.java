package dev.nezz.rentguard.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** Read model over the history table; rows are only ever written by the set-based merge. */
@Entity
@Immutable
@Table(name = "listing_versions")
public class ListingVersion {

    @Id
    @Column(name = "version_id")
    private Long versionId;
    @Column(name = "listing_id")
    private long listingId;
    private String name;
    @Column(name = "host_id")
    private Long hostId;
    private String neighbourhood;
    private String borough;
    @Column(name = "property_type")
    private String propertyType;
    @Column(name = "room_type")
    private String roomType;
    private Integer accommodates;
    @Column(name = "minimum_nights")
    private Integer minimumNights;
    @Column(name = "maximum_nights")
    private Integer maximumNights;
    private String license;
    @Column(name = "ll18_status")
    private String ll18Status;
    @Column(name = "valid_from")
    private LocalDate validFrom;
    @Column(name = "valid_to")
    private LocalDate validTo;
    @Column(name = "is_current")
    private boolean current;

    protected ListingVersion() {
    }

    public Long getVersionId() {
        return versionId;
    }

    public long getListingId() {
        return listingId;
    }

    public String getName() {
        return name;
    }

    public Long getHostId() {
        return hostId;
    }

    public String getNeighbourhood() {
        return neighbourhood;
    }

    public String getBorough() {
        return borough;
    }

    public String getPropertyType() {
        return propertyType;
    }

    public String getRoomType() {
        return roomType;
    }

    public Integer getAccommodates() {
        return accommodates;
    }

    public Integer getMinimumNights() {
        return minimumNights;
    }

    public Integer getMaximumNights() {
        return maximumNights;
    }

    public String getLicense() {
        return license;
    }

    public String getLl18Status() {
        return ll18Status;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public boolean isCurrent() {
        return current;
    }
}
