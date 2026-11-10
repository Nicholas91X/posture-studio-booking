package com.posturestudio.booking.notification;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;

/**
 * The keys of Web Push, on the elliptic curve P-256 (secp256r1), in the formats the standard uses
 * (guide 28, parts 3 and 5): a public key is 65 bytes, 04 | x | y ("uncompressed"); a private key is the
 * raw 32-byte number. Both travel as base64url without padding, as browsers write them.
 * Only JDK classes: no Bouncy Castle (guide 28, part 2).
 */
final class P256 {

    static final int PUBLIC_KEY_BYTES = 65;
    private static final int COORDINATE_BYTES = 32;
    private static final byte UNCOMPRESSED = 0x04;

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64URL_DECODER = Base64.getUrlDecoder();

    // the parameters of the curve, asked to the JDK by name instead of copied here as numbers
    private static final ECParameterSpec PARAMETERS = parameters();

    private P256() {
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this JVM", e);
        }
    }

    /**
     * A public key from its 65 bytes. Refused if it is not a point of the curve (guide 28, 3.3): a point
     * chosen off the curve is the start of an invalid curve attack, and in any case not a browser's key.
     */
    static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != PUBLIC_KEY_BYTES || uncompressed[0] != UNCOMPRESSED) {
            throw new IllegalArgumentException("A P-256 public key is 65 bytes starting with 04");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + COORDINATE_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES));
        if (!isOnCurve(x, y)) {
            throw new IllegalArgumentException("The point is not on the P-256 curve");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), PARAMETERS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 public key", e);
        }
    }

    static ECPrivateKey privateKey(byte[] raw) {
        if (raw.length != COORDINATE_BYTES) {
            throw new IllegalArgumentException("A P-256 private key is 32 bytes");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), PARAMETERS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 private key", e);
        }
    }

    // 04 | x | y, each coordinate on exactly 32 bytes
    static byte[] encode(ECPublicKey key) {
        byte[] encoded = new byte[PUBLIC_KEY_BYTES];
        encoded[0] = UNCOMPRESSED;
        writeUnsigned(key.getW().getAffineX(), encoded, 1);
        writeUnsigned(key.getW().getAffineY(), encoded, 1 + COORDINATE_BYTES);
        return encoded;
    }

    static byte[] encode(ECPrivateKey key) {
        byte[] encoded = new byte[COORDINATE_BYTES];
        writeUnsigned(key.getS(), encoded, 0);
        return encoded;
    }

    static String base64url(byte[] bytes) {
        return BASE64URL.encodeToString(bytes);
    }

    // accepts the padding too, which some tools add
    static byte[] fromBase64url(String text) {
        return BASE64URL_DECODER.decode(text.strip());
    }

    // y² = x³ + ax + b (mod p), and both coordinates smaller than p
    private static boolean isOnCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = PARAMETERS.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    // BigInteger.toByteArray() is signed and as short as possible: 33 bytes with a leading zero, or fewer
    // than 32. Here every number takes exactly 32 bytes, right-aligned
    private static void writeUnsigned(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - COORDINATE_BYTES);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + COORDINATE_BYTES - length, length);
    }

    private static ECParameterSpec parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this JVM", e);
        }
    }
}
