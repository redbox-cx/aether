package ch.redlab.aether.core;

import ch.redlab.aether.model.Transfer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An incoming transfer waiting for the user's decision.
 * The UI calls accept/reject, the receiving thread waits in {@link #await(Duration)}.
 */
public class IncomingRequest {

    private final Transfer transfer;
    private final CompletableFuture<Boolean> decision = new CompletableFuture<>();

    public IncomingRequest(Transfer transfer) {
        this.transfer = transfer;
    }

    /** Sender name, file name, size and security code for the dialog. */
    public Transfer getTransfer() {
        return transfer;
    }

    public void accept() {
        decision.complete(true);
    }

    public void reject() {
        decision.complete(false);
    }

    /**
     * Blocks until the user decided.
     *
     * @return true = accepted, false = rejected, null = no answer within the timeout
     */
    public Boolean await(Duration timeout) throws InterruptedException {
        try {
            return decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Late clicks after the timeout are ignored.
            decision.complete(false);
            return null;
        } catch (ExecutionException e) {
            return false;
        }
    }
}
