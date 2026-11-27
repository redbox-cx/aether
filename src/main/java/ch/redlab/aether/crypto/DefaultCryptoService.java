package ch.redlab.aether.crypto;

import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.SessionKey;
import ch.redlab.aether.protocol.ProtocolConstants;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Default {@link CryptoService} for ATP v1 (Konzeptbericht 4.2). Stateless and thread-safe; all per-transfer
 * state lives in the {@link SessionKey} and the {@link ChunkEncryptor}.
 * <p>
 * Key agreement is X25519, the session key is SHA-256(sharedSecret || transferId) used as AES-256, and chunks
 * are AES/GCM/NoPadding with a 128-bit tag. Both devices follow the same rules, so they derive the same key
 * and the same security code.
 */
public final class DefaultCryptoService implements CryptoService {

    private static final int TAG_BITS = ProtocolConstants.TAG_SIZE * 8;

    @Override
    public KeyPair newKeyPair() {
        return KeyExchange.newKeyPair();
    }

    @Override
    public SessionKey deriveSessionKey(KeyPair own, byte[] peerPublicKey, UUID transferId)
            throws GeneralSecurityException {
        PublicKey peer = KeyExchange.decodePublicKey(peerPublicKey);
        byte[] secret = KeyExchange.sharedSecret(own.getPrivate(), peer);
        try {
            MessageDigest sha = sha256();
            sha.update(secret);
            byte[] keyBytes = sha.digest(transferIdBytes(transferId));
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            String code = SecurityCode.of(own.getPublic().getEncoded(), peerPublicKey);
            return new SessionKey(key, code);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public ChunkEncryptor newEncryptor(SessionKey key) {
        return new GcmChunkEncryptor(key.key());
    }

    @Override
    public byte[] decrypt(SessionKey key, Chunk chunk) throws GeneralSecurityException {
        byte[] iv = chunk.iv();
        if (iv.length != ProtocolConstants.IV_SIZE) {
            throw new GeneralSecurityException("IV must be " + ProtocolConstants.IV_SIZE + " bytes");
        }
        if (ByteBuffer.wrap(iv, ProtocolConstants.IV_SIZE - Long.BYTES, Long.BYTES).getLong() != chunk.index()) {
            throw new GeneralSecurityException("IV does not match chunk index " + chunk.index());
        }
        int length = chunk.ciphertext().length;
        if (length < ProtocolConstants.TAG_SIZE
                || length > ProtocolConstants.CHUNK_SIZE + ProtocolConstants.TAG_SIZE) {
            throw new GeneralSecurityException("Invalid ciphertext length: " + length);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key.key(), new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(chunk.ciphertext());
    }

    /** The transfer id as 16 bytes big-endian: most significant bits first, then least significant bits. */
    private static byte[] transferIdBytes(UUID transferId) {
        return ByteBuffer.allocate(16)
                .putLong(transferId.getMostSignificantBits())
                .putLong(transferId.getLeastSignificantBits())
                .array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
