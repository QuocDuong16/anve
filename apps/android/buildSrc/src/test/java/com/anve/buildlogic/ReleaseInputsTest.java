package com.anve.buildlogic;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReleaseInputsTest {
    private Map<String,String> fixture() {
        Map<String,String> values = new HashMap<>();
        values.put("ANVE_API_URL","https://api.production-fixture.tld/");
        values.put("ANVE_GOOGLE_WEB_CLIENT_ID","1234567890-build-fixture.apps.googleusercontent.com");
        values.put("ANVE_MAPS_API_KEY","synthetic-client-value");
        values.put("ANVE_FIREBASE_APP_ID","1:1234567890:android:abcdef0123456789");
        values.put("ANVE_FIREBASE_PROJECT_ID","anve-build-fixture");
        values.put("ANVE_FIREBASE_API_KEY","synthetic-client-value");
        values.put("ANVE_FIREBASE_SENDER_ID","1234567890");
        values.put("ANVE_VERSION_CODE","2");
        values.put("ANVE_VERSION_NAME","0.3.0");
        return values;
    }
    @Test public void productionRequiresEveryExplicitField() {
        assertTrue(ReleaseInputs.validate(fixture(),true).isEmpty());
        for (String name : ReleaseInputs.PUBLIC_NAMES) {
            var values=fixture(); values.remove(name);
            assertTrue(name,ReleaseInputs.validate(values,true).stream().anyMatch(error -> error.contains(name)));
        }
    }
    @Test public void urlsRejectCredentialsLocalDefaultsAndAmbiguousAddresses() {
        for (String url : new String[]{"http://10.0.2.2:8080", "https://127.0.0.1/", "https://[::1]/", "https://localhost/",
            "https://host.localhost/", "https://user@api.production-fixture.tld/", "https://api.production-fixture.tld/?q=value",
            "https://api.production-fixture.tld/#fragment", "https://api.production-fixture.tld:0/", "https://api.production-fixture.tld:70000/",
            "https://2130706433/", "https://0177.0.0.1/", "https://api.production-fixture.tld\\@localhost/", "/relative"}) {
            assertFalse(url,ReleaseInputs.validApiUrl(url,true));
        }
    }
    @Test public void syntheticHostsOnlyBelongToValidationPackage() {
        for (String host : new String[]{"build.invalid", "ci.test", "api.example", "api.example.com", "example.org."}) {
            assertFalse(host,ReleaseInputs.validApiUrl("https://"+host+"/",true));
            assertTrue(host,ReleaseInputs.validApiUrl("https://"+host+"/",false));
        }
        assertTrue(ReleaseInputs.validApiUrl("https://api.production-fixture.tld/base/",true));
    }
    @Test public void versionsAndFirebaseConsistencyAreValidated() {
        for (String code : new String[]{"", "0", "-1", "2100000001", "02", "99999999999999999999"}) {
            var values=fixture(); values.put("ANVE_VERSION_CODE",code);
            assertFalse(code,ReleaseInputs.validate(values,true).isEmpty());
        }
        var values=fixture(); values.put("ANVE_FIREBASE_SENDER_ID","9999");
        assertTrue(ReleaseInputs.validate(values,true).stream().anyMatch(error -> error.contains("ANVE_FIREBASE_APP_ID")));
    }
    @Test public void errorsDoNotRepeatSuppliedValues() {
        var values=fixture(); values.put("ANVE_API_URL","private-invalid-value");
        values.put("ANVE_GOOGLE_WEB_CLIENT_ID","private-invalid-value");
        assertFalse(String.join(" ",ReleaseInputs.validate(values,true)).contains("private-invalid-value"));
    }
    @Test public void generatedStringLiteralsEscapeBuildInputs() {
        assertEquals("\"a\\\"b\\\\c\\nd\"",ReleaseInputs.javaString("a\"b\\c\nd"));
    }
}
