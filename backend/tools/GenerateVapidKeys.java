import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A new VAPID key pair for the push notifications (guide 28, 5.2), in the format of every Web Push
 * library: base64url without padding of the 65-byte public key (04 | x | y) and of the raw 32-byte
 * private key. Run it from backend/, with no compilation (JEP 330, Java 11+):
 *
 *     java tools/GenerateVapidKeys.java
 *
 * and copy the two lines into .env. The private key is a SECRET: never in the repository, never in a chat.
 * Not part of the application: Maven does not see this folder.
 */
public class GenerateVapidKeys {

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();

        byte[] encodedPublic = new byte[65];
        encodedPublic[0] = 0x04;
        write32(publicKey.getW().getAffineX(), encodedPublic, 1);
        write32(publicKey.getW().getAffineY(), encodedPublic, 33);
        byte[] encodedPrivate = new byte[32];
        write32(privateKey.getS(), encodedPrivate, 0);

        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        System.out.println("VAPID_PUBLIC_KEY=" + base64url.encodeToString(encodedPublic));
        System.out.println("VAPID_PRIVATE_KEY=" + base64url.encodeToString(encodedPrivate));
    }

    // exactly 32 bytes, right-aligned: toByteArray() may give 33 (a sign byte) or fewer
    private static void write32(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }
}
