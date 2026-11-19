package ch.redlab.aether.model;

import javax.crypto.SecretKey;

/**
 * Key material negotiated for a single transfer. Never stored, discarded afterwards.
 *
 * @param key          AES-256 key derived from the X25519 shared secret
 * @param securityCode 6-digit code both users compare to detect a man-in-the-middle
 */
public record SessionKey(SecretKey key, String securityCode) {
}
