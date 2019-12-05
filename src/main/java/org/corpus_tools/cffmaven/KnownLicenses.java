package org.corpus_tools.cffmaven;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class KnownLicenses {

  private static final Map<String, String> toSpdx;

  static {
    Map<String, String> licenseMap = new LinkedHashMap<>();

    licenseMap.put(norm("Apache-2.0"), "Apache-2.0");
    licenseMap.put(norm("Apache License 2.0"), "Apache-2.0");
    licenseMap.put(norm("Apache License, Version 2.0"), "Apache-2.0");
    licenseMap.put(norm("Apache 2.0"), "Apache-2.0");
    licenseMap.put(norm("The Apache Software License, Version 2.0"), "Apache-2.0");
    licenseMap.put(norm("http://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0");

    licenseMap.put(norm("GPL-2.0-with-classpath-exception"), "GPL-2.0-with-classpath-exception");
    licenseMap.put(norm("GNU General Public License v2.0 w/Classpath exception"),
        "GPL-2.0-with-classpath-exception");
    licenseMap.put(
        norm("GNU General Public License, version 2 (GPL2), with the classpath exception"),
        "GPL-2.0 WITH Classpath-exception-2.0");

    licenseMap.put(norm("MIT"), "MIT");
    licenseMap.put(norm("MIT license"), "MIT");

    licenseMap.put(norm("EPL-1.0"), "EPL-1.0");
    licenseMap.put(norm("Eclipse Public License 1.0"), "EPL-1.0");
    licenseMap.put(norm("Eclipse Public License, Version 1.0"), "EPL-1.0");

    licenseMap.put(norm("COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0"),
        "CDDL-1.0");

    toSpdx = Collections.unmodifiableMap(licenseMap);
  }

  private static String norm(String license) {
    if (license != null) {
      return license.toLowerCase().trim();
    } else {
      return "";
    }
  }

  /**
   * Parse a license string and return an SPDX short identifier if this is a known license.
   * 
   * @param orig The original license String
   * @return If found, the SPDX short identifier
   */
  public static Optional<String> parse(String orig) {
    return Optional.ofNullable(toSpdx.get(norm(orig)));
  }
}
