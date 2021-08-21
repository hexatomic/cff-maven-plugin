package org.corpus_tools.cffmaven;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class KnownLicenses {

  private static final Map<String, String> toSpdx;

  protected static final String SPDX_APACHE20 = "Apache-2.0";
  protected static final String SPDX_GPL20CLASSPATHEX = "GPL-2.0-with-classpath-exception";
  protected static final String SPDX_MIT = "MIT";
  protected static final String SPDX_EPL10 = "EPL-1.0";
  

  static {
    Map<String, String> licenseMap = new LinkedHashMap<>();

    licenseMap.put(norm(SPDX_APACHE20), SPDX_APACHE20);
    licenseMap.put(norm("Apache License 2.0"), SPDX_APACHE20);
    licenseMap.put(norm("Apache License, Version 2.0"), SPDX_APACHE20);
    licenseMap.put(norm("Apache 2.0"), SPDX_APACHE20);
    licenseMap.put(norm("The Apache Software License, Version 2.0"), SPDX_APACHE20);
    licenseMap.put(norm("http://www.apache.org/licenses/LICENSE-2.0.txt"), SPDX_APACHE20);

    licenseMap.put(norm(SPDX_GPL20CLASSPATHEX), "");
    licenseMap.put(norm("GNU General Public License v2.0 w/Classpath exception"),
        SPDX_GPL20CLASSPATHEX);
    licenseMap.put(
        norm("GNU General Public License, version 2 (GPL2), with the classpath exception"),
        SPDX_GPL20CLASSPATHEX);

    licenseMap.put(norm(SPDX_MIT), SPDX_MIT);
    licenseMap.put(norm("MIT license"), SPDX_MIT);

    licenseMap.put(norm(SPDX_EPL10), SPDX_EPL10);
    licenseMap.put(norm("Eclipse Public License 1.0"), SPDX_EPL10);
    licenseMap.put(norm("Eclipse Public License, Version 1.0"), SPDX_EPL10);

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
