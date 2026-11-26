package ch.redlab.aether.crypto;

import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.SessionKey;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.UUID;

/**
 * End-to-end encryption of one transfer (F-07). This is the only part of {@code crypto} that {@code core} uses.
 * <p>
 * Both devices must compute exactly the same values, so implementations follow these rules:
 * <ul>
 *     <li>Key pair: X25519, a new one for every transfer.</li>
 *     <li>Public keys on the wire: X.509 encoding ({@code PublicKey.getEncoded()}).</li>
 *     <li>Session key: SHA-256(sharedSecret || transferId), where transferId is 16 bytes big-endian
 *         (most significant bits first). Used as AES-256 key.</li>
 *     <li>Security code: sort both encoded public keys with {@code Arrays.compareUnsigned}, take SHA-256 of
 *         the concatenation, read the first 4 bytes as unsigned big-endian number, modulo 1,000,000,
 *         formatted with 6 digits and leading zeros.</li>
 *     <li>Chunks: AES/GCM/NoPadding, 128-bit tag, no additional authenticated data.
 *         IV = 4 random bytes per transfer || chunk index as 8 bytes big-endian.</li>
 * </ul>
 * Every cryptographic failure is reported as {@link GeneralSecurityException};
 * {@code core} answers it with ERROR (DECRYPT_FAILED). Implementations are stateless and thread-safe.
 */
public interface CryptoService {

    /** Creates a new ephemeral X25519 key pair for one transfer. Its private key never leaves this device. */
    KeyPair newKeyPair();

    /**
     * Combines our key pair with the public key of the other device.
     *
     * @param own           key pair created by {@link #newKeyPair()} for this transfer
     * @param peerPublicKey public key of the other device, X.509 encoded as received in the message
     * @param transferId    id of the transfer, binds the session key to it
     * @return AES-256 session key and the 6-digit security code (identical on both devices)
     * @throws GeneralSecurityException if the peer key is invalid or the key agreement fails
     */
    SessionKey deriveSessionKey(KeyPair own, byte[] peerPublicKey, UUID transferId)
            throws GeneralSecurityException;

    /** Creates the encryptor for the sending side of one transfer. Use one encryptor per transfer. */
    ChunkEncryptor newEncryptor(SessionKey key);

    /**
     * Decrypts one chunk and verifies that it was not modified.
     * Also checks that the IV is 12 bytes long and ends with the chunk index.
     *
     * @return the plaintext, at most {@code ProtocolConstants.CHUNK_SIZE} bytes
     * @throws GeneralSecurityException if the chunk was tampered with, has an invalid IV or uses another key
     */
    byte[] decrypt(SessionKey key, Chunk chunk) throws GeneralSecurityException;
}
