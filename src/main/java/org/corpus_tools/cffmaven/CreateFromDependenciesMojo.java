package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Create Citation File Format with references from the dependencies defined via
 * Maven.
 *
 */
@Mojo(name = "create", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CreateFromDependenciesMojo extends AbstractMojo {

    private final static String P2_PLUGIN_GROUP_ID = "p2.eclipse-plugin";

    private static final Pattern MINOR_VERSION_HEURISTIC = Pattern.compile("^([0-9]\\.[0-9])\\..*");
    private static final Pattern ARTIFACTID_HEURISTIC_SUFFIX = Pattern.compile("(.*)(\\.)([^.]+)$");
    private static final Pattern INCLUDE_THIRD_PARTY_FILE_PATTERN = Pattern.compile(
            "(META-INF/)?((NOTICE|DEPENDENCIES|about|license|LICENSE)(\\.md|\\.txt|\\.html|\\.rst)?)|(about_files/.+)");

    private final static HttpUrl DEFINITIONS_ENDPOINT = HttpUrl.parse("https://api.clearlydefined.io/definitions");

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${basedir}/CITATION.cff")
    private File output;

    @Parameter(defaultValue = "${basedir}/THIRD-PARTY")
    private File thirdPartyFolder;

    @Parameter(defaultValue = "")
    private File input;

    @Parameter(defaultValue = "true")
    private boolean skipExistingDependencies;

    @Parameter(defaultValue = "true")
    private boolean includeEMail;

    @Parameter(defaultValue = "true")
    private boolean p2IgnorePatchLevel;

    @Parameter(defaultValue = "false")
    private boolean p2ReconstructGroupId;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Component
    private ProjectBuilder mavenProjectBuilder;

    private final OkHttpClient http = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();

    public void execute() throws MojoExecutionException {

        LoadSettings yamlLoadSettings = LoadSettings.builder().build();
        Load yamlLoad = new Load(yamlLoadSettings);
        Map<String, Object> cff = new LinkedHashMap<>();
        cff.putIfAbsent("cff-version", "1.0.3");

        if (input != null && input.isFile()) {
            try (FileInputStream inputFile = new FileInputStream(input)) {
                Object loaded = yamlLoad.loadFromInputStream(inputFile);
                if (loaded instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) loaded).entrySet()) {
                        if (e.getKey() instanceof String) {
                            cff.put((String) e.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Throwable ex) {
                getLog().error("Error loading input YAML file " + input.getPath(), ex);
            }
        }

        ProjectBuildingRequest projectBuildingRequest = new DefaultProjectBuildingRequest(
                mavenSession.getProjectBuildingRequest()).setRemoteRepositories(remoteRepositories)
                        .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL).setResolveDependencies(false)
                        .setProcessPlugins(false);

        // set basic properties like title
        cff.putIfAbsent("message", "If you use this software, please cite it as below.");
        cff.putIfAbsent("title", project.getName());
        cff.putIfAbsent("version", project.getVersion());

        List<HashMap<String, Object>> authors = new LinkedList<>();
        for (Developer dev : project.getModel().getDevelopers()) {
            HashMap<String, Object> author = new HashMap<>();
            author.put("name", dev.getName());
            authors.add(author);
        }
        // If no authors are specified, use generic fallback author info
        if (authors.isEmpty()) {
        	getLog().info("No author info found for this project. Creating fallback information.");
            HashMap<String, Object> author = new HashMap<>();
            author.put("name", "The " + cff.get("title") + " " + cff.get("version") + " Team");
            authors.add(author);
        }
        cff.putIfAbsent("authors", authors);

        // get existing references and add new ones to the list
        List<Map<String, Object>> references = mapExistingReferences(cff.get("references"));
        Set<String> existingTitles = references.stream().map(ref -> ref.get("title")).filter(title -> title != null)
                .map(title -> title.toString()).collect(Collectors.toSet());
      
        TreeMap<String, Map<String, Object>> newReferences = new TreeMap<>();

        for (Artifact artifact : project.getArtifacts()) {
            try {
                Map<String, Object> newRef = createReference(artifact, projectBuildingRequest);
                String newRefTitle = (String) newRef.getOrDefault("title", "");
                if (skipExistingDependencies && existingTitles.contains(newRefTitle)) {
                    getLog().info("Ignoring existing dependency " + artifact.toString());
                } else if (!newReferences.containsKey(newRefTitle)) {
                    getLog().info("Adding dependency " + artifact.toString());
                    newReferences.put(newRefTitle, newRef);
                    String titleForThirdParty = (String) newRefTitle;
                    // remove additional information like stuff in (...) at the end
                    titleForThirdParty = titleForThirdParty.replaceFirst("\\s*\\([^)]*\\)$", "");
                    createThirdPartyFolder(titleForThirdParty, artifact, projectBuildingRequest);
                }
            } catch (ProjectBuildingException ex) {
                getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
            }
        }

        // add all new references to the list
        for(Map<String, Object> ref : newReferences.values()) {
            references.add(ref);
        }

        cff.put("references", references);

        // Write out the YAML file again
        DumpSettings dumpSettings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build();
        Dump dumpYaml = new Dump(dumpSettings);
        try (FileWriter outWriter = new FileWriter(output)) {
            String yamlAsString = dumpYaml.dumpToString(cff);
            outWriter.write(yamlAsString);
        } catch (IOException ex) {
            getLog().error("Could not write Citation file", ex);
        }
    }

    private List<Map<String, Object>> mapExistingReferences(Object existingReferences) {
        List<Map<String, Object>> references = new LinkedList<>();
        if (existingReferences instanceof List) {
            for (Object ref : (List<?>) existingReferences) {
                if (ref instanceof Map) {
                    Map<String, Object> existingRefEntry = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) ref).entrySet()) {
                        if (e.getKey() instanceof String) {
                            existingRefEntry.put((String) e.getKey(), e.getValue());
                        }
                    }
                    references.add(existingRefEntry);
                }
            }
        }

        return references;
    }

    private Map<String, Object> createReference(Artifact artifact, ProjectBuildingRequest projectBuildingRequest)
            throws ProjectBuildingException {
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
                reference.put("license", remoteLicense.get().spdx);
                List<Map<String, Object>> authorList = new LinkedList<>();
                for (String name : remoteLicense.get().authors) {
                    Map<String, Object> author = new LinkedHashMap<>();
                    author.put("name", name);
                    authorList.add(author);
                }
                // If no authors are specified, use generic fallback author info
                if (authorList.isEmpty()) {
                	getLog().info("No author info found for P2 artifact " + artifact.getId() + ". Creating fallback information.");
                    HashMap<String, Object> author = new LinkedHashMap<>();
                    author.put("name", "The " + reference.get("title") + " " + reference.get("version") + " Team");
                    authorList.add(author);
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
                                                    + newArtifact.toString()
                                                    + " failed because the new one was not found.");
                                        } else {
                                            getLog().warn("Replacing artifact " + artifact.toString() + " with "
                                                    + newArtifact.toString() + " failed", ex);
                                        }
                                    }
                                }
                            } else {
                                getLog().error("Invalid pom.properties detected: " + groupId + "/" + artifactId + "/"
                                        + version);
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                getLog().error("Could not open JAR file " + artifact.getFile().getPath() + " for inspection", ex);
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
                    spdx = Optional.of(remoteLicense.get().spdx);
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
        // If no authors are specified, use generic fallback author info
        if (authorList.isEmpty()) {
        	getLog().info("No author info found for Maven artifact " + artifact.getArtifactId() + ". Creating fallback information.");
        	Map<String, Object> author = new LinkedHashMap<>();
            author.put("name", "The " + reference.get("title") + " " + reference.get("version") + " Team");
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
                getLog().debug("Minor version is " + minorVersion.get() + " for artifact " + artifact.toString());
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
            patterns.add(artifact.getGroupId() + "/" + artifact.getArtifactId() + "/" + artifact.getVersion());
        }

        TreeMap<Long, RemoteLicenseInformation> remoteLicensesByScore = new TreeMap<>();

        for (String pattern : patterns) {
            getLog().debug("Trying pattern \"" + pattern + "\" for artifact " + artifact.toString());
            HttpUrl findUrl = DEFINITIONS_ENDPOINT.newBuilder().addQueryParameter("pattern", pattern).build();
            Request findRequest = new Request.Builder().url(findUrl).build();

            try (Response response = http.newCall(findRequest).execute()) {
                if (response.code() == 200) {
                    // Parse JSON
                    JSONArray searchResult = new JSONArray(response.body().string());
                    for (Object foundArtifactId : searchResult) {
                        if (foundArtifactId instanceof String) {
                            Optional<RemoteLicenseInformation> result = queryLicenseForId((String) foundArtifactId);
                            if (result.isPresent()) {
                                getLog().debug("Found license information with score " + result.get().score
                                        + " for artifact " + artifact.toString());
                                remoteLicensesByScore.put(result.get().score, result.get());
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

    class RemoteLicenseInformation {
        String spdx;
        List<String> authors = new LinkedList<>();
        long score = 0;
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
                    result.spdx = licensedObject.getString("declared");

                    if (root.has("scores")) {
                        JSONObject scores = root.getJSONObject("scores");
                        if (scores.has("effective")) {
                            result.score = scores.getLong("effective");
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
                                                result.authors.add((String) p);
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

    private void createThirdPartyFolder(String title, Artifact artifact,
            ProjectBuildingRequest projectBuildingRequest) {
        if (thirdPartyFolder != null && !thirdPartyFolder.getPath().isEmpty()) {
            // Create a sub-directory for this artifact
            File artifactFolder = new File(thirdPartyFolder, title.replaceAll("\\W+", "_"));
            // Inspect the JAR file to copy all available license texts and notices
            if (artifact.getFile() != null && artifact.getFile().isFile()) {
                try (ZipFile artifactFile = new ZipFile(artifact.getFile())) {
                    Enumeration<? extends ZipEntry> entries = artifactFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry currentEntry = entries.nextElement();
                        String entryPath = currentEntry.getName().replace('\\', '/').replaceFirst("^META-INF/", "");
                        getLog().debug("Checking zip file entry \"" + entryPath
                                + "\" for inclusion in third party folder.");
                        if (INCLUDE_THIRD_PARTY_FILE_PATTERN.matcher(entryPath).matches()) {
                            // copy this file to the output folder
                            File outputFile = new File(artifactFolder, entryPath);
                            if(outputFile.exists()) {
                                getLog().warn("Not overwriting existing file " + outputFile.getPath());
                            } else {
                                getLog().info("Copying " + entryPath + " from artifact to " + outputFile.getPath());
                                if (outputFile.getParentFile().isDirectory() || outputFile.getParentFile().mkdirs()) {
                                    try (InputStream is = artifactFile.getInputStream(currentEntry)) {
                                        Files.copy(is, outputFile.toPath());
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException ex) {
                    getLog().warn("Could not open artifact file " + artifact.getFile()
                            + ". No 3rd party files will be extracted from it.", ex);
                }
            }

        
        }
    }

}