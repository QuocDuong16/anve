package com.anve.buildlogic;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Build-input validation is syntactic only; it never resolves hosts or contacts providers. */
public final class ReleaseInputs {
    public static final List<String> PUBLIC_NAMES = List.of(
        "ANVE_API_URL", "ANVE_GOOGLE_WEB_CLIENT_ID", "ANVE_MAPS_API_KEY",
        "ANVE_FIREBASE_APP_ID", "ANVE_FIREBASE_PROJECT_ID", "ANVE_FIREBASE_API_KEY",
        "ANVE_FIREBASE_SENDER_ID", "ANVE_VERSION_CODE", "ANVE_VERSION_NAME"
    );
    public static final List<String> SIGNING_NAMES = List.of(
        "ANVE_KEYSTORE_FILE", "ANVE_KEYSTORE_PASSWORD", "ANVE_KEY_ALIAS",
        "ANVE_KEY_PASSWORD", "ANVE_SIGNING_CERT_SHA256"
    );
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9]+-[a-zA-Z0-9-]+\\.apps\\.googleusercontent\\.com");
    private ReleaseInputs() {}

    public static List<String> validate(Map<String,String> values, boolean distribution) {
        List<String> problems = new ArrayList<>();
        for (String name : PUBLIC_NAMES) {
            String value = values.getOrDefault(name, "");
            if (value.isBlank() || value.equals("REPLACE_ME") || value.chars().anyMatch(Character::isISOControl)) {
                problems.add(name + " must be explicitly configured without control characters");
            }
        }
        if (!validApiUrl(values.getOrDefault("ANVE_API_URL", ""), distribution)) {
            problems.add("ANVE_API_URL must be an absolute HTTPS DNS URL with no credentials, query or fragment; local/emulator hosts are refused");
        }
        if (!CLIENT_ID.matcher(values.getOrDefault("ANVE_GOOGLE_WEB_CLIENT_ID", "")).matches()) {
            problems.add("ANVE_GOOGLE_WEB_CLIENT_ID must be a Google web client ID");
        }
        String sender = values.getOrDefault("ANVE_FIREBASE_SENDER_ID", "");
        String appId = values.getOrDefault("ANVE_FIREBASE_APP_ID", "");
        if (!sender.matches("[0-9]+") || !appId.matches("1:" + Pattern.quote(sender) + ":android:[0-9a-fA-F]+")) {
            problems.add("ANVE_FIREBASE_APP_ID must be an Android app ID matching ANVE_FIREBASE_SENDER_ID");
        }
        if (!values.getOrDefault("ANVE_FIREBASE_PROJECT_ID", "").matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            problems.add("ANVE_FIREBASE_PROJECT_ID must be a project ID");
        }
        try {
            String text = values.getOrDefault("ANVE_VERSION_CODE", "");
            long code = Long.parseLong(text);
            if (!text.matches("[1-9][0-9]*") || code > 2_100_000_000L) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            problems.add("ANVE_VERSION_CODE must be an explicit integer from 1 through 2100000000");
        }
        if (!values.getOrDefault("ANVE_VERSION_NAME", "").matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) {
            problems.add("ANVE_VERSION_NAME must be an explicit three-part version");
        }
        return problems;
    }

    public static boolean validApiUrl(String value, boolean distribution) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || value.contains("\\")) return false;
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length()-1);
            if (host.isEmpty() || !host.contains(".") || host.matches("[0-9.]+") || host.contains(":")) return false;
            if (host.equals("localhost") || host.endsWith(".localhost")) return false;
            if (distribution) {
                for (String suffix : List.of("invalid", "test", "example", "example.com", "example.net", "example.org")) {
                    if (host.equals(suffix) || host.endsWith("."+suffix)) return false;
                }
            }
            return true;
        } catch (Exception error) { return false; }
    }

    public static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
