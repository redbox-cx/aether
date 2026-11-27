package ch.redlab.aether.crypto;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the 6-digit security code (Konzeptbericht 4.2, UC4). */
class SecurityCodeTest {

    @Test
    void codeHasExactlySixDigits() {
        for (int i = 0; i < 100; i++) {
            String code = SecurityCode.of(publicKey(), publicKey());
            assertEquals(6, code.length(), "code: " + code);
            assertTrue(code.chars().allMatch(Character::isDigit), "code: " + code);
        }
    }

    /** The code does not depend on the order of the two keys, so both devices show the same one. */
    @Test
    void codeIsSymmetric() {
        for (int i = 0; i < 100; i++) {
            byte[] a = publicKey();
            byte[] b = publicKey();
            assertEquals(SecurityCode.of(a, b), SecurityCode.of(b, a));
        }
    }

    @Test
    void codeIsStableForSameKeys() {
        byte[] a = publicKey();
        byte[] b = publicKey();
        assertEquals(SecurityCode.of(a, b), SecurityCode.of(a, b));
    }

    private static byte[] publicKey() {
        KeyPair keyPair = KeyExchange.newKeyPair();
        return keyPair.getPublic().getEncoded();
    }
}
