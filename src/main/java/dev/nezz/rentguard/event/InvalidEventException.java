package dev.nezz.rentguard.event;

import java.time.LocalDate;

/** An event the contract refuses. Carries the snapshot it claimed, when that much could be read. */
public class InvalidEventException extends RuntimeException {

    private final transient LocalDate snapshotDate;

    public InvalidEventException(String reason, LocalDate snapshotDate) {
        super(reason);
        this.snapshotDate = snapshotDate;
    }

    public LocalDate snapshotDate() {
        return snapshotDate;
    }
}
