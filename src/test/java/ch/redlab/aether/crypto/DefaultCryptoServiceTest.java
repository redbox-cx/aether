package ch.redlab.aether.crypto;

import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.SessionKey;
import ch.redlab.aether.protocol.ProtocolConstants;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the X25519 / AES-256-GCM implementation against the rules of ATP v1 (Konzeptbericht 4.2). */
class DefaultCryptoServiceTest {

    private final DefaultCryptoService crypto = new DefaultCryptoService();

    /** Both devices derive the same AES key and the same security code from the same transfer id. */
    @Test
    void bothSidesDeriveSameKeyAndCode() throws GeneralSecurityException {
        KeyPair sender = crypto.newKeyPair();
        KeyPair receiver = crypto.newKeyPair();
        UUID transferId = UUID.randomUUID();

        SessionKey senderSide = crypto.deriveSessionKey(sender, encoded(receiver), transferId);
        SessionKey receiverSide = crypto.deriveSessionKey(receiver, encoded(sender), transferId);

        assertArrayEquals(senderSide.key().getEncoded(), receiverSide.key().getEncoded());
        assertEquals(senderSide.securityCode(), receiverSide.securityCode());
        assertEquals(32, senderSide.key().getEncoded().length, "session key is AES-256");
    }

    @Test
    void differentTransferIdGivesDifferentKey() throws GeneralSecurityException {
        KeyPair sender = crypto.newKeyPair();
        KeyPair receiver = crypto.newKeyPair();

        SessionKey first = crypto.deriveSessionKey(sender, encoded(receiver), UUID.randomUUID());
        SessionKey second = crypto.deriveSessionKey(sender, encoded(receiver), UUID.randomUUID());

        assertFalse(Arrays.equals(first.key().getEncoded(), second.key().getEncoded()));
    }

    @Test
    void invalidPeerPublicKeyIsRejected() {
        KeyPair own = crypto.newKeyPair();
        byte[] garbage = new byte[32];
        new Random(1).nextBytes(garbage);

        assertThrows(GeneralSecurityException.class,
                () -> crypto.deriveSessionKey(own, garbage, UUID.randomUUID()));
    }

    @Test
    void roundTripEmptyPayload() throws GeneralSecurityException {
        assertRoundTrip(new byte[0]);
    }

    @Test
    void roundTripSingleByte() throws GeneralSecurityException {
        assertRoundTrip(new byte[] {42});
    }

    @Test
    void roundTripFullChunk() throws GeneralSecurityException {
        byte[] plaintext = new byte[ProtocolConstants.CHUNK_SIZE];
        new Random(7).nextBytes(plaintext);
        assertRoundTrip(plaintext);
    }

    @Test
    void tamperedCiphertextFailsDecryption() throws GeneralSecurityException {
        Pair pair = pair();
        Chunk chunk = pair.encryptor.encrypt(0, "secret".getBytes(), 6);
        byte[] ciphertext = chunk.ciphertext().clone();
        ciphertext[0] ^= 0x01;
        Chunk tampered = new Chunk(chunk.index(), chunk.iv(), ciphertext);

        assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(pair.receiver, tampered));
    }

    @Test
    void tamperedIvFailsDecryption() throws GeneralSecurityException {
        Pair pair = pair();
        Chunk chunk = pair.encryptor.encrypt(0, "secret".getBytes(), 6);
        byte[] iv = chunk.iv().clone();
        iv[0] ^= 0x01;
        Chunk tampered = new Chunk(chunk.index(), iv, chunk.ciphertext());

        assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(pair.receiver, tampered));
    }

    @Test
    void chunkIndexNotMatchingIvIsRejected() throws GeneralSecurityException {
        Pair pair = pair();
        Chunk chunk = pair.encryptor.encrypt(0, "secret".getBytes(), 6);
        Chunk mislabelled = new Chunk(1, chunk.iv(), chunk.ciphertext());

        assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(pair.receiver, mislabelled));
    }

    @Test
    void wrongSessionKeyFailsDecryption() throws GeneralSecurityException {
        Pair pair = pair();
        Chunk chunk = pair.encryptor.encrypt(0, "secret".getBytes(), 6);

        KeyPair other = crypto.newKeyPair();
        SessionKey wrong = crypto.deriveSessionKey(other, encoded(crypto.newKeyPair()), UUID.randomUUID());

        assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(wrong, chunk));
    }

    @Test
    void shortCiphertextIsRejected() {
        Pair pair = pair();
        Chunk tooShort = new Chunk(0, new byte[ProtocolConstants.IV_SIZE], new byte[1]);

        assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(pair.receiver, tooShort));
    }

    @Test
    void repeatedIndexIsRejected() throws GeneralSecurityException {
        ChunkEncryptor encryptor = pair().encryptor;
        encryptor.encrypt(0, new byte[1], 1);

        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(0, new byte[1], 1));
    }

    @Test
    void indexGoingBackwardsIsRejected() throws GeneralSecurityException {
        ChunkEncryptor encryptor = pair().encryptor;
        encryptor.encrypt(0, new byte[1], 1);
        encryptor.encrypt(1, new byte[1], 1);

        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(1, new byte[1], 1));
    }

    @Test
    void skippingAnIndexIsRejected() {
        ChunkEncryptor encryptor = pair().encryptor;

        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(1, new byte[1], 1));
    }

    @Test
    void lengthLargerThanChunkIsRejected() {
        ChunkEncryptor encryptor = pair().encryptor;

        assertThrows(IllegalArgumentException.class,
                () -> encryptor.encrypt(0, new byte[ProtocolConstants.CHUNK_SIZE + 1],
                        ProtocolConstants.CHUNK_SIZE + 1));
    }

    @Test
    void negativeLengthIsRejected() {
        ChunkEncryptor encryptor = pair().encryptor;

        assertThrows(IllegalArgumentException.class, () -> encryptor.encrypt(0, new byte[1], -1));
    }

    /** Consecutive chunks use different IVs, so GCM stays safe for the whole transfer. */
    @Test
    void consecutiveChunksUseDifferentIvs() throws GeneralSecurityException {
        ChunkEncryptor encryptor = pair().encryptor;
        Chunk first = encryptor.encrypt(0, new byte[8], 8);
        Chunk second = encryptor.encrypt(1, new byte[8], 8);

        assertFalse(Arrays.equals(first.iv(), second.iv()));
    }

    // ------------------------------------------------------------------ helpers

    private void assertRoundTrip(byte[] plaintext) throws GeneralSecurityException {
        Pair pair = pair();
        Chunk chunk = pair.encryptor.encrypt(0, plaintext, plaintext.length);
        byte[] decrypted = crypto.decrypt(pair.receiver, chunk);
        assertArrayEquals(plaintext, decrypted);
    }

    /** A sender encryptor and a receiver session that share the same key, as in a real transfer. */
    private Pair pair() {
        try {
            KeyPair sender = crypto.newKeyPair();
            KeyPair receiver = crypto.newKeyPair();
            UUID transferId = UUID.randomUUID();
            SessionKey senderSide = crypto.deriveSessionKey(sender, encoded(receiver), transferId);
            SessionKey receiverSide = crypto.deriveSessionKey(receiver, encoded(sender), transferId);
            return new Pair(crypto.newEncryptor(senderSide), receiverSide);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] encoded(KeyPair keyPair) {
        return keyPair.getPublic().getEncoded();
    }

    private record Pair(ChunkEncryptor encryptor, SessionKey receiver) {
    }
}
