package org.corpus_tools.cffmaven;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class KnownLicenses {

    private static final Map<String, String> toSpdx;

    static {
        Map<String, String> licenseMap = new LinkedHashMap<>();

        licenseMap.put("Apache-2.0", "Apache-2.0".toLowerCase());
        licenseMap.put("Apache License 2.0".toLowerCase(), "Apache-2.0");
        licenseMap.put("Apache License, Version 2.0".toLowerCase(), "Apache-2.0");
        licenseMap.put("Apache 2.0".toLowerCase(), "Apache-2.0");
        licenseMap.put("The Apache Software License, Version 2.0".toLowerCase(), "Apache-2.0");

        licenseMap.put("GPL-2.0-with-classpath-exception".toLowerCase(), "GPL-2.0-with-classpath-exception");
        licenseMap.put("GNU General Public License v2.0 w/Classpath exception".toLowerCase(),
                "GPL-2.0-with-classpath-exception");
        licenseMap.put("GNU General Public License, version 2 (GPL2), with the classpath exception".toLowerCase(),
                "GPL-2.0-with-classpath-exception");

        licenseMap.put("MIT".toLowerCase(), "MIT");
        licenseMap.put("MIT license".toLowerCase(), "MIT");

        licenseMap.put("EPL-1.0".toLowerCase(), "EPL-1.0");
        licenseMap.put("Eclipse Public License 1.0".toLowerCase(), "EPL-1.0");
        licenseMap.put("Eclipse Public License, Version 1.0".toLowerCase(), "EPL-1.0");

        toSpdx = Collections.unmodifiableMap(licenseMap);
    }

    /**
     * Parse a license string and return an SPDX short identifier if this is a known
     * license
     * 
     * @param orig The original license String
     * @return If found, the SPDX short identifier
     */
    public static Optional<String> parse(String orig) {
        String license = orig.trim().toLowerCase();

        return Optional.ofNullable(toSpdx.get(license));
    }
}