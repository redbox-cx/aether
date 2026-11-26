package ch.redlab.aether.crypto;

import ch.redlab.aether.model.Chunk;

import java.security.GeneralSecurityException;

/**
 * Encrypts the chunks of one outgoing transfer. Holds the random IV prefix of that transfer.
 * <p>
 * Indexes must strictly increase (0, 1, 2, ...), so no IV is ever used twice with the same key.
 * Not thread-safe: one encryptor is used by the single thread that sends the transfer.
 */
public interface ChunkEncryptor {

    /**
     * Encrypts {@code plaintext[0..length)} as chunk number {@code index}.
     *
     * @param index     chunk number, must be exactly one higher than the previous one (first chunk: 0)
     * @param plaintext buffer with the data; only the first {@code length} bytes are used
     * @param length    number of bytes, at most {@code ProtocolConstants.CHUNK_SIZE}
     * @return the chunk with its IV and the ciphertext including the 16-byte GCM tag
     * @throws IllegalStateException    if the index does not follow the previous one
     * @throws IllegalArgumentException if length is negative or larger than a chunk
     * @throws GeneralSecurityException if encryption fails
     */
    Chunk encrypt(long index, byte[] plaintext, int length) throws GeneralSecurityException;
}
