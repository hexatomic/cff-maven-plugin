package org.corpus_tools.cffmaven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.json.JSONArray;
import org.json.JSONObject;

public abstract class AbstractCffMojo extends AbstractMojo {

  protected static final String P2_PLUGIN_GROUP_ID = "p2.eclipse-plugin";
  protected static final Pattern MINOR_VERSION_HEURISTIC = Pattern.compile("^([0-9]\\.[0-9])\\..*");
  protected static final Pattern ARTIFACTID_HEURISTIC_SUFFIX = Pattern.compile("(.*)(\\.)([^.]+)$");
  protected static final HttpUrl DEFINITIONS_ENDPOINT =
      HttpUrl.parse("https://api.clearlydefined.io/definitions");
  
  @Parameter(defaultValue = "true")
  private boolean includeEMail;
  @Parameter(defaultValue = "true")
  private boolean p2IgnorePatchLevel;
  @Parameter(defaultValue = "false")
  private boolean p2ReconstructGroupId;
  @Component
  private ProjectBuilder mavenProjectBuilder;
  private final OkHttpClient http =
      new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();

  protected Map<String, Object> createReference(Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {
    LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
    reference.put("type", "software");
    reference.put("title", artifact.getArtifactId());
    reference.put("version", artifact.getVersion());

    if (P2_PLUGIN_GROUP_ID.equals(artifact.getGroupId())) {
      createReferenceFromP2(reference, artifact, projectBuildingRequest);
    } else {
      createReferenceFromMavenArtifact(reference, artifact, projectBuildingRequest);
    }

    return reference;
  }

  private void createReferenceFromP2(Map<String, Object> reference, Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {

    if (!createReferenceFromIncludedPom(reference, artifact, projectBuildingRequest)) {
      Optional<RemoteLicenseInformation> remoteLicense = queryLicenseFromClearlyDefined(artifact);
      if (remoteLicense.isPresent()) {
        reference.put("license", remoteLicense.get().getSpdx());
        List<Map<String, Object>> authorList = new LinkedList<>();
        for (String name : remoteLicense.get().getAuthors()) {
          Map<String, Object> author = new LinkedHashMap<>();
          author.put("name", name);
          authorList.add(author);
        }
        if (!authorList.isEmpty()) {
          reference.put("authors", authorList);
        }
      }
    }
  }

  private boolean createReferenceFromIncludedPom(Map<String, Object> reference, Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {
    // try to get the real artifact information from the JAR-file
    if (artifact.getFile() != null && artifact.getFile().isFile()) {
      try (ZipFile artifactFile = new ZipFile(artifact.getFile())) {
        Enumeration<? extends ZipEntry> entries = artifactFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry currentEntry = entries.nextElement();
          if (currentEntry.getName().endsWith("/pom.properties")) {
            try (InputStream propertyInputStream = artifactFile.getInputStream(currentEntry)) {
              Properties props = new Properties();
              props.load(propertyInputStream);
              String groupId = props.getProperty("groupId");
              String artifactId = props.getProperty("artifactId");
              String version = props.getProperty("version");
              if (groupId != null && artifactId != null && version != null) {
                // use the original maven artifact information
                Artifact newArtifact = new DefaultArtifact(groupId, artifactId, version,
                    artifact.getScope(), artifact.getType(), artifact.getClassifier(),
                    artifact.getArtifactHandler());
                // Don't try to fetch snapshot dependencies
                if (!newArtifact.isSnapshot()) {
                  try {
                    createReferenceFromMavenArtifact(reference, newArtifact,
                        projectBuildingRequest);
                    return true;
                  } catch (ProjectBuildingException ex) {
                    if (ex.getCause() instanceof ArtifactResolutionException) {
                      getLog().warn("Replacing artifact " + artifact.toString() + " with "
                          + newArtifact.toString() + " failed because the new one was not found.");
                    } else {
                      getLog().warn("Replacing artifact " + artifact.toString() + " with "
                          + newArtifact.toString() + " failed", ex);
                    }
                  }
                }
              } else {
                getLog().error("Invalid pom.properties detected: " + groupId + "/" + artifactId
                    + "/" + version);
              }
            }
          }
        }
      } catch (IOException ex) {
        getLog().error(
            "Could not open JAR file " + artifact.getFile().getPath() + " for inspection", ex);
      }
    }

    return false;

  }

  private void createReferenceFromMavenArtifact(Map<String, Object> reference, Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {
    ProjectBuildingResult result = mavenProjectBuilder.build(artifact, projectBuildingRequest);
    MavenProject project = result.getProject();

    if (project.getName() != null && !project.getName().isEmpty()) {
      reference.put("title", project.getName() + " (" + artifact.getArtifactId() + ")");
    }
    if (project.getVersion() != null && !project.getVersion().isEmpty()) {
      reference.put("version", project.getVersion());
    }

    // Add license information
    List<License> licenses = project.getLicenses();
    if (!licenses.isEmpty()) {
      License l = licenses.get(0);

      Optional<String> spdx = KnownLicenses.parse(l.getName());

      if (!spdx.isPresent()) {
        // try to query the license from remote repository
        Optional<RemoteLicenseInformation> remoteLicense = queryLicenseFromClearlyDefined(artifact);
        if (remoteLicense.isPresent()) {
          spdx = Optional.of(remoteLicense.get().getSpdx());
        }
      }

      if (spdx.isPresent()) {
        reference.put("license", spdx.get());
      } else if (l.getUrl() != null && !l.getUrl().isEmpty()) {
        // We could not parse the license, but there is an URL we can use
        getLog().warn("Falling back to license URL for unknown license \"" + l.getName() + "\"");
        reference.put("license-url", l.getUrl());
      } else {
        getLog().error("Unknown license for " + artifact.toString());
      }
    }
    // Add author information
    List<Map<String, Object>> authorList = new LinkedList<>();
    for (Developer dev : project.getDevelopers()) {
      Map<String, Object> author = new LinkedHashMap<>();
      author.put("name", dev.getName());
      if (includeEMail && dev.getEmail() != null && !dev.getEmail().isEmpty()) {
        author.put("email", dev.getEmail());
      }
      authorList.add(author);
    }
    reference.put("authors", authorList);
  }

  private Optional<RemoteLicenseInformation> queryLicenseFromClearlyDefined(Artifact artifact) {
    // query the REST API of ClearlyDefined
    // https://api.clearlydefined.io/api-docs/
    List<String> patterns = new LinkedList<>();
    if (P2_PLUGIN_GROUP_ID.equals(artifact.getGroupId())) {
      Optional<String> minorVersion = Optional.empty();
      Matcher minorVersionMatcher = MINOR_VERSION_HEURISTIC.matcher(artifact.getVersion());
      if (minorVersionMatcher.matches()) {
        minorVersion = Optional.of(minorVersionMatcher.group(1));
        getLog().debug(
            "Minor version is " + minorVersion.get() + " for artifact " + artifact.toString());
      }
      patterns.add(artifact.getArtifactId() + "/" + artifact.getVersion());
      if (p2IgnorePatchLevel && minorVersion.isPresent()) {

        patterns.add(artifact.getArtifactId() + "/" + minorVersion.get());
      }
      if (p2ReconstructGroupId) {
        Matcher m = ARTIFACTID_HEURISTIC_SUFFIX.matcher(artifact.getArtifactId());
        if (m.find()) {
          String groupId = m.group(1);
          String artifactId = m.group(3);
          patterns.add(groupId + "/" + artifactId + "/" + artifact.getVersion());
          if (p2IgnorePatchLevel && minorVersion.isPresent()) {
            patterns.add(groupId + "/" + artifactId + "/" + minorVersion.get());
          }
        }
      }
    } else {
      patterns.add(
          artifact.getGroupId() + "/" + artifact.getArtifactId() + "/" + artifact.getVersion());
    }

    TreeMap<Long, RemoteLicenseInformation> remoteLicensesByScore = new TreeMap<>();

    for (String pattern : patterns) {
      getLog().debug("Trying pattern \"" + pattern + "\" for artifact " + artifact.toString());
      HttpUrl findUrl =
          DEFINITIONS_ENDPOINT.newBuilder().addQueryParameter("pattern", pattern).build();
      Request findRequest = new Request.Builder().url(findUrl).build();

      try (Response response = http.newCall(findRequest).execute()) {
        if (response.code() == 200) {
          // Parse JSON
          JSONArray searchResult = new JSONArray(response.body().string());
          for (Object foundArtifactId : searchResult) {
            if (foundArtifactId instanceof String) {
              Optional<RemoteLicenseInformation> result =
                  queryLicenseForId((String) foundArtifactId);
              if (result.isPresent()) {
                getLog().debug("Found license information with score " + result.get().getScore()
                    + " for artifact " + artifact.toString());
                remoteLicensesByScore.put(result.get().getScore(), result.get());
              }
            }
          }
        }

      } catch (IOException ex) {
        getLog().error("Could not interact with clearlydefined.io", ex);
      }
    }

    if (remoteLicensesByScore.isEmpty()) {
      return Optional.empty();
    } else {
      // return the entry with the highest score
      return Optional.of(remoteLicensesByScore.lastEntry().getValue());
    }

  }

  private Optional<RemoteLicenseInformation> queryLicenseForId(String id) throws IOException {
    HttpUrl artifactUrl = DEFINITIONS_ENDPOINT.newBuilder().addEncodedPathSegment(id).build();

    Response response = http.newCall(new Request.Builder().url(artifactUrl).build()).execute();
    if (response.code() == 200) {
      JSONObject root = new JSONObject(response.body().string());
      // only use explicitly declared licenses
      if (root.has("licensed")) {
        JSONObject licensedObject = root.getJSONObject("licensed");
        if (licensedObject.has("declared")) {

          RemoteLicenseInformation result = new RemoteLicenseInformation();
          result.setSpdx(licensedObject.getString("declared"));

          if (root.has("scores")) {
            JSONObject scores = root.getJSONObject("scores");
            if (scores.has("effective")) {
              result.setScore(scores.getLong("effective"));
            }
          }

          // also get the authors by using the attribution data
          if (licensedObject.has("facets")) {
            JSONObject facets = licensedObject.getJSONObject("facets");
            if (facets != null) {
              if (facets.has("core")) {
                JSONObject core = facets.getJSONObject("core");
                if (core.has("attribution")) {
                  JSONObject attribution = core.getJSONObject("attribution");
                  if (attribution.has("parties")) {
                    JSONArray parties = attribution.getJSONArray("parties");
                    for (Object p : parties) {
                      if (p instanceof String) {
                        result.getAuthors().add((String) p);
                      }
                    }
                  }
                }
              }
            }
          }

          return Optional.of(result);
        }
      }
    }

    return Optional.empty();
  }

}
