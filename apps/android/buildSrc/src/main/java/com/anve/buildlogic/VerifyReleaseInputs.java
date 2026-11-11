package com.anve.buildlogic;

import java.util.ArrayList;
import java.util.HashMap;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Signing validation must execute against the current key and environment")
public abstract class VerifyReleaseInputs extends DefaultTask {
    @Input public abstract MapProperty<String,String> getSettings();
    @Input public abstract Property<Boolean> getDistribution();
    @Internal public abstract RegularFileProperty getLegacyConfig();
    @Internal public abstract DirectoryProperty getSourceRoot();
    @Inject protected abstract ProviderFactory getEnvironment();

    @TaskAction public void verify() {
        var problems = new ArrayList<>(ReleaseInputs.validate(getSettings().get(), getDistribution().get()));
        if (getLegacyConfig().get().getAsFile().exists()) {
            problems.add("move legacy main/res/values/config_local.xml to debug/res/values/config_local.xml before release builds");
        }
        var signing = new HashMap<String,String>();
        for (String name : ReleaseInputs.SIGNING_NAMES) signing.put(name,getEnvironment().environmentVariable(name).getOrElse(""));
        signing.put("ANVE_KEYSTORE_TYPE",getEnvironment().environmentVariable("ANVE_KEYSTORE_TYPE").getOrElse("PKCS12"));
        SigningIdentity.validate(signing,problems,getSourceRoot().get().getAsFile().toPath());
        if (!problems.isEmpty()) throw new GradleException("Release input validation failed:\n - " + String.join("\n - ", problems));
    }
}
