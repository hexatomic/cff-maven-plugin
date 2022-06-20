
package org.corpus_tools.cffmaven;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.FileTemplateLoader;
import com.github.jknack.handlebars.io.TemplateLoader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.Scm;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.snakeyaml.engine.v2.api.Load;

/**
 * Common functionality of the different CFF Mojos.
 * 
 * @author Thomas Krause
 *
 */
public abstract class AbstractCffMojo extends AbstractMojo {

  protected static final Pattern P2_PLUGIN_GROUP_ID = Pattern.compile("p2.eclipse(\\.|\\-)plugin");
  protected static final Pattern MINOR_VERSION_HEURISTIC = Pattern.compile("^([0-9]\\.[0-9])\\..*");
  protected static final Pattern ARTIFACTID_HEURISTIC_SUFFIX = Pattern.compile("(.*)(\\.)([^.]+)$");
  protected static final HttpUrl DEFINITIONS_ENDPOINT =
      HttpUrl.parse("https://api.clearlydefined.io/definitions");

  protected static final String TITLE = "title";
  protected static final String VERSION = "version";



  @Parameter(defaultValue = "true")
  private boolean includeEmail;
  @Parameter(defaultValue = "true")
  private boolean p2IgnorePatchLevel;
  @Parameter(defaultValue = "false")
  private boolean p2ReconstructGroupId;


  @Parameter(defaultValue = "${project}", readonly = true)
  protected MavenProject project;


  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession mavenSession;

  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
  protected List<ArtifactRepository> remoteRepositories;

  @Component
  private ProjectBuilder mavenProjectBuilder;
  protected final OkHttpClient http =
      new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
  private final TemplateLoader handlebarsTemplateLoader = new FileTemplateLoader("", "");
  private final Handlebars handlebars = new Handlebars(handlebarsTemplateLoader);
  @Parameter(defaultValue = "${basedir}/THIRD-PARTY")
  protected File thirdPartyFolder;
  @Parameter(defaultValue = "${basedir}/CITATION.cff")
  protected File output;

  @Parameter
  protected List<String> ignoredArtifacts;

  private List<Pattern> ignoredPatterns;


  protected Map<String, Object> createReference(Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {
    LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
    reference.put("type", "software");
    reference.put(TITLE, artifact.getArtifactId());
    reference.put(VERSION, artifact.getVersion());

    if (P2_PLUGIN_GROUP_ID.matcher(artifact.getGroupId()).matches()) {
      createReferenceFromP2(reference, artifact, projectBuildingRequest);
    } else {
      createReferenceFromMavenArtifact(reference, artifact, projectBuildingRequest);
    }

    return reference;
  }


  protected Map<String, Object> createReferenceFromTemplate(Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest, File templateFile, Load yamlLoad)
      throws ProjectBuildingException, IOException {

    Map<String, Object> reference = new LinkedHashMap<>();

    // Apply the template to the artifact Pojo
    Template template = handlebars.compile(templateFile.getAbsolutePath());
    String referenceRaw = template.apply(artifact);
    // Parse the generated YAML and create a map out of it
    Object loaded = yamlLoad.loadFromString(referenceRaw);
    if (loaded instanceof Map<?, ?>) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) loaded).entrySet()) {
        if (e.getKey() instanceof String) {
          reference.put((String) e.getKey(), e.getValue());
        } else {
          getLog().warn("Template " + templateFile.getPath() + " as a non-string key '" + e.getKey()
              + "' which will be ignored");
        }
      }
    } else {
      getLog().warn("Template " + templateFile.getPath() + " is not a map and will be ignored");
    }
    return reference;
  }

  private void createReferenceFromP2(Map<String, Object> reference, Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) throws ProjectBuildingException {

    if (!createReferenceFromIncludedPom(reference, artifact, projectBuildingRequest)) {
      LinkedHashSet<Map<String, Object>> authorSet = new LinkedHashSet<>();

      Optional<RemoteLicenseInformation> remoteLicense = queryLicenseFromClearlyDefined(artifact);
      if (remoteLicense.isPresent()) {
        reference.put("license", remoteLicense.get().getSpdx());
        for (String name : remoteLicense.get().getAuthors()) {
          Map<String, Object> author = new LinkedHashMap<>();
          author.put("name", name);
          authorSet.add(author);
        }
      }

      // If no authors are specified, use generic fallback author info
      if (authorSet.isEmpty()) {
        getLog().info("No author info found for P2 artifact " + artifact.getId()
            + ". Creating fallback information.");
        LinkedHashMap<String, Object> author = new LinkedHashMap<>();
        author.put("name",
            "The " + reference.get(TITLE) + " " + reference.get(VERSION) + " Team");
        authorSet.add(author);
      }

      reference.put("authors", new LinkedList<>(authorSet));
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
              String version = props.getProperty(VERSION);
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
      reference.put(TITLE, project.getName());
      reference.put("abbreviation", project.getGroupId() + ":" + project.getArtifactId());
    }
    if (project.getVersion() != null && !project.getVersion().isEmpty()) {
      reference.put(VERSION, project.getVersion());
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
      Object title = reference.get(TITLE);
      if (title instanceof String) {
        File artifactFolder = getArtifactFolder((String) reference.get(TITLE));
        Path relativePath = output.getParentFile().toPath().relativize(artifactFolder.toPath());
        reference.put("notes", "More license information can be found in the "
            + relativePath.toString() + " directory.");
      }

    }
    // Add author information
    LinkedHashSet<Map<String, Object>> authorSet = new LinkedHashSet<>();
    for (Developer dev : project.getDevelopers()) {
      Map<String, Object> author = new LinkedHashMap<>();

      if (dev.getName() != null) {
        author.put("name", dev.getName());
        if (includeEmail && dev.getEmail() != null && !dev.getEmail().isEmpty()) {
          author.put("email", dev.getEmail());
        }
        authorSet.add(author);
      }
    }
    // If no authors are specified, use generic fallback author info
    if (authorSet.isEmpty()) {
      getLog().info("No author info found for Maven artifact " + artifact.getArtifactId()
          + ". Creating fallback information.");
      Map<String, Object> author = new LinkedHashMap<>();
      author.put("name",
          "The " + reference.get(TITLE) + " " + reference.get(VERSION) + " Team");
      authorSet.add(author);
    }
    reference.put("authors", new LinkedList<>(authorSet));

    // Add SCM URL if available
    String scmUrl = getRepositoryCodeUrl(project.getScm());
    if (scmUrl != null) {
      reference.put("repository-code", scmUrl);
    }
  }

  protected String getRepositoryCodeUrl(Scm scm) {

    if (scm != null) {
      String scmUrl = scm.getUrl();
      if (scmUrl != null && !scmUrl.isEmpty()) {
        if (scmUrl.startsWith("scm:") || scmUrl.startsWith("git@")) {
          getLog().warn("Invalid SCM URL " + scmUrl + " detected .It will be ignored.");
        } else {
          return scmUrl;
        }
      }
    }
    return null;
  }

  protected File getArtifactFolder(String artifactTitle) {
    if (thirdPartyFolder != null && !thirdPartyFolder.getPath().isEmpty()) {
      return new File(thirdPartyFolder, artifactTitle.replaceAll("[^a-zA-Z_0-9.]+", "_"));
    } else {
      return null;
    }
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

  protected ProjectBuildingRequest createProjectBuildingRequest() {
    return new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest())
        .setRemoteRepositories(remoteRepositories)
        .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
        .setResolveDependencies(false).setProcessPlugins(false);
  }

  protected boolean isIgnored(Artifact artifact) {
    if (ignoredPatterns == null) {
      ignoredPatterns = new LinkedList<>();
      if (ignoredArtifacts != null) {
        // Add all patterns defined in the "ignored" parameter to the list
        for (String patternRaw : ignoredArtifacts) {
          Pattern pattern = Pattern.compile(patternRaw);
          getLog().info("Adding ignored pattern " + patternRaw);
          ignoredPatterns.add(pattern);
        }
      }
    }
    for (Pattern p : ignoredPatterns) {
      if (p.matcher(artifact.toString()).matches()) {
        return true;
      }
    }
    return false;
  }


}
