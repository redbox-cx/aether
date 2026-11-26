package ch.redlab.aether.core;

import ch.redlab.aether.crypto.ChunkEncryptor;
import ch.redlab.aether.crypto.CryptoService;
import ch.redlab.aether.model.Chunk;
import ch.redlab.aether.model.SessionKey;
import ch.redlab.aether.protocol.ProtocolConstants;

import javax.crypto.AEADBadTagException;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Test double for {@link CryptoService} until the real implementation exists.
 * Does not encrypt: the "ciphertext" is the plaintext followed by a 16-byte tag of zeros.
 * A changed tag byte is reported like a real GCM tag failure, so tampering can be tested.
 */
class FakeCryptoService implements CryptoService {

    static final String SECURITY_CODE = "123456";

    @Override
    public KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SessionKey deriveSessionKey(KeyPair own, byte[] peerPublicKey, UUID transferId)
            throws GeneralSecurityException {
        if (peerPublicKey.length == 0) {
            throw new GeneralSecurityException("Empty peer key");
        }
        return new SessionKey(new SecretKeySpec(new byte[32], "AES"), SECURITY_CODE);
    }

    @Override
    public ChunkEncryptor newEncryptor(SessionKey key) {
        return new ChunkEncryptor() {
            private long next;

            @Override
            public Chunk encrypt(long index, byte[] plaintext, int length) {
                if (index != next) {
                    throw new IllegalStateException("Expected index " + next + ", got " + index);
                }
                if (length < 0 || length > ProtocolConstants.CHUNK_SIZE) {
                    throw new IllegalArgumentException("Invalid length " + length);
                }
                next++;
                byte[] ciphertext = Arrays.copyOf(plaintext, length + ProtocolConstants.TAG_SIZE);
                Arrays.fill(ciphertext, length, ciphertext.length, (byte) 0);
                return new Chunk(index, iv(index), ciphertext);
            }
        };
    }

    @Override
    public byte[] decrypt(SessionKey key, Chunk chunk) throws GeneralSecurityException {
        if (!Arrays.equals(chunk.iv(), iv(chunk.index()))) {
            throw new AEADBadTagException("IV does not match the chunk index");
        }
        byte[] data = chunk.ciphertext();
        int length = data.length - ProtocolConstants.TAG_SIZE;
        for (int i = length; i < data.length; i++) {
            if (data[i] != 0) {
                throw new AEADBadTagException("Tag mismatch");
            }
        }
        return Arrays.copyOf(data, length);
    }

    /** 4 bytes "random" prefix (zeros here) + 8 bytes index, like the real format. */
    static byte[] iv(long index) {
        return ByteBuffer.allocate(ProtocolConstants.IV_SIZE).putInt(0).putLong(index).array();
    }
}
