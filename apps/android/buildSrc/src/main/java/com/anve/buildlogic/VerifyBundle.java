package com.anve.buildlogic;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.JarFile;

/** Verifies every content entry, not only the presence of signature metadata. */
public final class VerifyBundle {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[1].matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
            int content = 0;
            long bytes = 0;
            byte[] buffer = new byte[16384];
            try (JarFile jar = new JarFile(args[0], true)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().toUpperCase(Locale.ROOT);
                    if (name.equals("META-INF/MANIFEST.MF") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                    try (var input = jar.getInputStream(entry)) {
                        int count;
                        while ((count=input.read(buffer)) != -1) {
                            bytes += count;
                            if (bytes > 1024L*1024*1024) throw new IllegalArgumentException();
                        }
                    }
                    var signers = entry.getCodeSigners();
                    if (signers == null || signers.length != 1) throw new IllegalArgumentException();
                    var certificate = signers[0].getSignerCertPath().getCertificates().get(0);
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                    if (!actual.equalsIgnoreCase(args[1])) throw new IllegalArgumentException();
                    content++;
                }
            }
            if (content == 0) throw new IllegalArgumentException();
            System.out.println("Bundle content signatures and certificate pin verified.");
        } catch (Exception error) {
            System.err.println("Bundle signature verification failed.");
            System.exit(1);
        }
    }
}
