package ch.redlab.aether.model;

/**
 * One encrypted block of the file (at most 64 KiB plaintext).
 *
 * @param index      sequence number, starting at 0
 * @param iv         12-byte AES-GCM IV (4 random bytes per transfer + 8 bytes index)
 * @param ciphertext encrypted data including the 16-byte GCM tag
 */
public record Chunk(long index, byte[] iv, byte[] ciphertext) {
}
