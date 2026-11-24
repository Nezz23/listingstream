package dev.nezz.rentguard.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The service's ECDSA P-256 signing key, loaded from {@code rentguard.ledger.key-dir} or created there on first start.
 * Signatures use the fixed-length r||s (P1363) encoding so a browser can check them with WebCrypto as-is.
 */
@Component
public class LedgerKeys {

    static final String ALGORITHM = "SHA256withECDSAinP1363Format";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public LedgerKeys(@Value("${rentguard.ledger.key-dir}") Path dir) {
        try {
            Path priv = dir.resolve("ledger-private.pk8.b64");
            Path pub = dir.resolve("ledger-public.x509.b64");
            KeyFactory ec = KeyFactory.getInstance("EC");
            if (!Files.exists(priv)) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = gen.generateKeyPair();
                Files.createDirectories(dir);
                Files.writeString(priv, b64(pair.getPrivate().getEncoded()));
                Files.writeString(pub, b64(pair.getPublic().getEncoded()));
            }
            privateKey = ec.generatePrivate(new PKCS8EncodedKeySpec(unb64(Files.readString(priv))));
            publicKey = ec.generatePublic(new X509EncodedKeySpec(unb64(Files.readString(pub))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot load ledger key from " + dir, e);
        }
    }

    public String sign(String blockHash) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(blockHash.getBytes(StandardCharsets.UTF_8));
            return b64(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String blockHash, String signature) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(publicKey);
            s.update(blockHash.getBytes(StandardCharsets.UTF_8));
            return s.verify(unb64(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** X.509 SubjectPublicKeyInfo, base64; WebCrypto imports it with format "spki". */
    public String publicKeyBase64() {
        return b64(publicKey.getEncoded());
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unb64(String text) {
        return Base64.getDecoder().decode(text.strip());
    }
}
