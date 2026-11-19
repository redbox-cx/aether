package ch.redlab.aether.model;

import java.nio.file.Path;
import java.util.UUID;

/**
 * One file transfer between two devices (1:1).
 * Fields are updated by background threads and read by the UI, hence volatile.
 */
public class Transfer {

    private final UUID id;
    private final Direction direction;
    private final String peerName;
    private final FileInfo file;

    private volatile TransferStatus status = TransferStatus.REQUESTED;
    private volatile long bytesTransferred;
    private volatile String securityCode;
    private volatile String message;
    private volatile Path savedFile;

    public Transfer(UUID id, Direction direction, String peerName, FileInfo file) {
        this.id = id;
        this.direction = direction;
        this.peerName = peerName;
        this.file = file;
    }

    public UUID getId() {
        return id;
    }

    public Direction getDirection() {
        return direction;
    }

    /** Name of the other device (receiver for outgoing, sender for incoming). */
    public String getPeerName() {
        return peerName;
    }

    public FileInfo getFile() {
        return file;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    /** Progress between 0.0 and 1.0, e.g. for a ProgressBar. */
    public double getProgress() {
        return file.size() == 0 ? 1.0 : (double) bytesTransferred / file.size();
    }

    /** 6-digit security code, available after the key exchange. */
    public String getSecurityCode() {
        return securityCode;
    }

    public void setSecurityCode(String securityCode) {
        this.securityCode = securityCode;
    }

    /** Optional detail for the UI, e.g. the reject reason or an error code. */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /** Final location of a received file (incoming transfers only). */
    public Path getSavedFile() {
        return savedFile;
    }

    public void setSavedFile(Path savedFile) {
        this.savedFile = savedFile;
    }
}
