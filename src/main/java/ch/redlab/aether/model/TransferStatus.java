package ch.redlab.aether.model;

/** Lifecycle of a transfer (Konzeptbericht 2.1, entity "Übertragung"). */
public enum TransferStatus {
    /** Request sent / received, waiting for the receiver to decide. */
    REQUESTED,
    ACCEPTED,
    REJECTED,
    /** Chunks are being transferred. */
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED;

    /** True if the transfer is over and will not change anymore. */
    public boolean isFinal() {
        return this == REJECTED || this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
