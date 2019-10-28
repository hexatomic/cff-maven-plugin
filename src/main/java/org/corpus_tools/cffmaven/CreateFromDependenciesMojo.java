package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
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

    private static final Pattern P2_VERSION_SUFFIX = Pattern.compile("(.*)(\\.v[0-9]+-[0-9]+)");

    private final static HttpUrl DEFINITIONS_ENDPOINT = HttpUrl.parse("https://api.clearlydefined.io/definitions");

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${basedir}/CITATION.cff")
    private File output;

    @Parameter(defaultValue = "")
    private File input;

    @Parameter(defaultValue = "true")
    private boolean skipExistingDependencies;

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
        cff.putIfAbsent("title", project.getName());
        cff.putIfAbsent("version", project.getVersion());

        List<HashMap<String, Object>> authors = new LinkedList<>();
        for (Developer dev : project.getModel().getDevelopers()) {
            HashMap<String, Object> author = new HashMap<>();
            author.put("name", dev.getName());
            authors.add(author);
        }
        if(!authors.isEmpty()) {
            cff.putIfAbsent("authors", authors);
        }

        // get existing references and add new ones to the list
        List<Map<String, Object>> references = mapExistingReferences(cff.get("references"));
        Set<String> existingTitles = references.stream().map(ref -> ref.get("title")).filter(title -> title != null)
                .map(title -> title.toString()).collect(Collectors.toSet());
        for (Artifact artifact : project.getArtifacts()) {
            try {
                Map<String, Object> newRef = createReference(artifact, projectBuildingRequest);
                if (skipExistingDependencies && existingTitles.contains(newRef.getOrDefault("title", ""))) {
                    getLog().info("Ignoring existing dependency " + artifact.toString());
                } else {
                    getLog().info("Adding dependency " + artifact.toString());
                    references.add(createReference(artifact, projectBuildingRequest));
                }
            } catch (ProjectBuildingException ex) {
                getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
            }

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

        Optional<RemoteLicenseInformation> remoteLicense = Optional.empty();
        if (P2_PLUGIN_GROUP_ID.equals(artifact.getGroupId())) {
            remoteLicense = queryLicenseFromClearlyDefined(artifact);
            if (remoteLicense.isPresent()) {
                reference.put("license", remoteLicense.get().spdx);
                List<Map<String, Object>> authorList = new LinkedList<>();
                for(String name : remoteLicense.get().authors) {
                    Map<String, Object> author = new LinkedHashMap<>();
                    author.put("name", name);
                    authorList.add(author);
                }
                if(!authorList.isEmpty()) {
                    reference.put("authors", authorList);
                }
            }
        } else {
            ProjectBuildingResult result = mavenProjectBuilder.build(artifact, projectBuildingRequest);
            MavenProject project = result.getProject();

            // Add license information
            List<License> licenses = project.getLicenses();
            if (!licenses.isEmpty()) {
                License l = licenses.get(0);

                Optional<String> spdx = KnownLicenses.parse(l.getName());

                if (!spdx.isPresent()) {
                    // try to query the license from remote
                    remoteLicense = queryLicenseFromClearlyDefined(artifact);
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
                    reference.put("license", "UNKNOWN");
                }
            }
            // Add author information
            List<Map<String, Object>> authorList = new LinkedList<>();
            for(Developer dev : project.getDevelopers()) {
                Map<String, Object> author = new LinkedHashMap<>();
                author.put("name", dev.getName());
                if(dev.getEmail() != null) {
                    author.put("email", dev.getEmail());
                }
                authorList.add(author);
            }
            if(!authorList.isEmpty()) {
                reference.put("authors", authorList);
            }
        }

        return reference;
    }

    private Optional<RemoteLicenseInformation> queryLicenseFromClearlyDefined(Artifact artifact) {
        // query the REST API of ClearlyDefined
        // https://api.clearlydefined.io/api-docs/
        String pattern;
        if (P2_PLUGIN_GROUP_ID.equals(artifact.getGroupId())) {
            String version = P2_VERSION_SUFFIX.matcher(artifact.getVersion()).replaceFirst("\\1");
            pattern = artifact.getArtifactId() + "/" + version;
        } else {
            pattern = artifact.getGroupId() + "/" + artifact.getArtifactId() + "/" + artifact.getVersion();
        }
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
                            return result;
                        }
                    }
                }
            }

        } catch (IOException ex) {
            getLog().error("Could not interact with clearlydefined.io", ex);
        }
        return Optional.empty();
    }

    class RemoteLicenseInformation {
        String spdx;
        List<String> authors = new LinkedList<>();
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
                    // also get the authors by using the attribution data
                    if(root.has("facets")) {
                        JSONObject facets = root.getJSONObject("facets");
                        if (facets != null) {
                            JSONObject core = facets.getJSONObject("core");
                            if (core != null) {
                                JSONObject attribution = core.getJSONObject("attribution");
                                if (attribution != null) {
                                    JSONArray parties = attribution.getJSONArray("parties");
                                    if (parties != null) {
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
}