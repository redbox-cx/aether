package ch.redlab.aether.crypto;

import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.protocol.ProtocolConstants;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the chunks of one outgoing transfer with AES-256-GCM (Konzeptbericht 4.2).
 * <p>
 * The IV is 4 random bytes, drawn once per transfer, followed by the 8-byte big-endian chunk index.
 * Indexes must strictly increase, so the same IV is never used twice with the same key. Not thread-safe:
 * one encryptor belongs to the single thread that sends the transfer.
 */
final class GcmChunkEncryptor implements ChunkEncryptor {

    private static final int TAG_BITS = ProtocolConstants.TAG_SIZE * 8;
    private static final int IV_PREFIX_SIZE = ProtocolConstants.IV_SIZE - Long.BYTES;

    private final SecretKey key;
    private final byte[] ivPrefix;
    private long nextIndex;

    GcmChunkEncryptor(SecretKey key) {
        this.key = key;
        this.ivPrefix = new byte[IV_PREFIX_SIZE];
        new SecureRandom().nextBytes(ivPrefix);
    }

    @Override
    public Chunk encrypt(long index, byte[] plaintext, int length) throws GeneralSecurityException {
        if (length < 0 || length > ProtocolConstants.CHUNK_SIZE) {
            throw new IllegalArgumentException("Invalid chunk length: " + length);
        }
        if (index != nextIndex) {
            throw new IllegalStateException("Expected chunk index " + nextIndex + ", got " + index);
        }
        byte[] iv = ByteBuffer.allocate(ProtocolConstants.IV_SIZE).put(ivPrefix).putLong(index).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext, 0, length);
        nextIndex++;
        return new Chunk(index, iv, ciphertext);
    }
}
