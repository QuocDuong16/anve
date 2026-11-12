package com.anve.buildlogic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.interfaces.RSAKey;
import java.security.interfaces.ECKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class SigningIdentity {
    private SigningIdentity() {}
    public static void validate(Map<String,String> values, List<String> problems, Path sourceRoot) {
        boolean missing = false;
        for (String name : ReleaseInputs.SIGNING_NAMES) {
            if (values.getOrDefault(name, "").isBlank()) {
                problems.add(name + " is required through the environment"); missing = true;
            }
        }
        if (missing) return;
        String expected = values.get("ANVE_SIGNING_CERT_SHA256").replace(":", "").toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) { problems.add("ANVE_SIGNING_CERT_SHA256 must pin the upload certificate"); return; }
        char[] storePass = values.get("ANVE_KEYSTORE_PASSWORD").toCharArray();
        char[] keyPass = values.get("ANVE_KEY_PASSWORD").toCharArray();
        try {
            String type = values.getOrDefault("ANVE_KEYSTORE_TYPE", "PKCS12");
            if (!List.of("PKCS12", "JKS").contains(type)) throw new IllegalArgumentException();
            Path path = Path.of(values.get("ANVE_KEYSTORE_FILE"));
            if (!path.isAbsolute()) {
                problems.add("ANVE_KEYSTORE_FILE must be an absolute path outside the source checkout"); return;
            }
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException();
            if (path.toRealPath().startsWith(sourceRoot.toRealPath())) {
                problems.add("ANVE_KEYSTORE_FILE must be outside the source checkout"); return;
            }
            KeyStore store = KeyStore.getInstance(type);
            try (var input = Files.newInputStream(path)) { store.load(input, storePass); }
            var entry = store.getEntry(values.get("ANVE_KEY_ALIAS"), new KeyStore.PasswordProtection(keyPass));
            if (!(entry instanceof KeyStore.PrivateKeyEntry key)) throw new IllegalArgumentException();
            X509Certificate certificate = (X509Certificate) key.getCertificate();
            certificate.checkValidity();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!actual.equals(expected)) { problems.add("ANVE_SIGNING_CERT_SHA256 does not match the keystore certificate"); return; }
            var publicKey = certificate.getPublicKey();
            if (!(publicKey instanceof RSAKey rsa && rsa.getModulus().bitLength() >= 2048)
                && !(publicKey instanceof ECKey ec && ec.getParams().getOrder().bitLength() >= 256)) {
                problems.add("upload signing key must be RSA >= 2048 bits or EC >= 256 bits");
            }
        } catch (Exception error) {
            // Provider exceptions can include paths/aliases; never send their payload to build logs.
            problems.add("upload keystore, alias and key credentials could not be verified");
        } finally { Arrays.fill(storePass, '\0'); Arrays.fill(keyPass, '\0'); }
    }
}
