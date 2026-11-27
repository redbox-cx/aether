package ch.redlab.aether.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Computes the 6-digit security code that both users compare to detect a man-in-the-middle
 * (Konzeptbericht 4.2, UC4). Both devices feed in the same two public keys and get the same code.
 */
final class SecurityCode {

    private static final long MODULO = 1_000_000L;

    private SecurityCode() {
    }

    /**
     * Derives the code from the two X.509 encoded public keys. The keys are sorted first, so the
     * order in which they are passed does not matter: {@code of(a, b)} equals {@code of(b, a)}.
     *
     * @return exactly 6 digits, with leading zeros
     */
    static String of(byte[] ownPublicKey, byte[] peerPublicKey) {
        byte[] first = ownPublicKey;
        byte[] second = peerPublicKey;
        if (Arrays.compareUnsigned(first, second) > 0) {
            byte[] swap = first;
            first = second;
            second = swap;
        }
        MessageDigest sha = sha256();
        sha.update(first);
        byte[] hash = sha.digest(second);
        long prefix = ((long) (hash[0] & 0xFF) << 24)
                | ((long) (hash[1] & 0xFF) << 16)
                | ((long) (hash[2] & 0xFF) << 8)
                | (hash[3] & 0xFF);
        return String.format(Locale.ROOT, "%06d", prefix % MODULO);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
