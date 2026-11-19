package ch.redlab.aether.core;

import ch.redlab.aether.model.Transfer;

/** Receives events about incoming and running transfers. */
public interface TransferListener {

    /**
     * Someone wants to send us a file. The UI shows the dialog (F-04) and calls
     * {@link IncomingRequest#accept()} or {@link IncomingRequest#reject()} (F-05).
     * Without an answer the request is rejected automatically after 120 s.
     */
    void onIncomingRequest(IncomingRequest request);

    /** Status, progress or security code of a transfer changed. */
    void onTransferUpdated(Transfer transfer);
}
