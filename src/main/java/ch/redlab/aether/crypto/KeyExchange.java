package ch.redlab.aether.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;

/**
 * X25519 key agreement (Konzeptbericht 4.2). Creates ephemeral key pairs, reads the public key of the
 * other device from its X.509 encoding and computes the shared secret both devices end up with.
 * <p>
 * The algorithm name and encodings are fixed here so the two devices always match bit for bit.
 */
final class KeyExchange {

    private static final String ALGORITHM = "X25519";

    private KeyExchange() {
    }

    /** Creates a fresh ephemeral X25519 key pair for one transfer. */
    static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " not available", e);
        }
    }

    /**
     * Reads a public key from its X.509 encoding as received on the wire.
     *
     * @throws GeneralSecurityException if the bytes are not a valid X25519 public key
     */
    static PublicKey decodePublicKey(byte[] x509) throws GeneralSecurityException {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509));
        } catch (RuntimeException e) {
            // KeyFactory may wrap malformed input in an unchecked exception; report it as checked.
            throw new InvalidKeyException("Invalid public key", e);
        }
    }

    /**
     * Computes the 32-byte shared secret from our private key and the peer public key.
     *
     * @throws InvalidKeyException      if the agreement yields an all-zero secret (invalid peer point)
     * @throws GeneralSecurityException if the key agreement fails
     */
    static byte[] sharedSecret(PrivateKey own, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
        agreement.init(own);
        agreement.doPhase(peer, true);
        byte[] secret = agreement.generateSecret();
        if (isAllZero(secret)) {
            Arrays.fill(secret, (byte) 0);
            throw new InvalidKeyException("Shared secret is all zero");
        }
        return secret;
    }

    private static boolean isAllZero(byte[] bytes) {
        int result = 0;
        for (byte b : bytes) {
            result |= b;
        }
        return result == 0;
    }
}
