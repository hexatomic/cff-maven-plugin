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
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * Create Citation File Format with references from the dependencies defined via
 * Maven.
 *
 */
@Mojo(name = "create", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CreateFromDependenciesMojo extends AbstractMojo {

    private final static String P2_PLUGIN_GROUP_ID = "p2.eclipse-plugin";

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${basedir}/CITATION.cff")
    private File output;

    @Parameter(defaultValue = "")
    private File input;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Component
    private ProjectBuilder mavenProjectBuilder;

    public void execute() throws MojoExecutionException {

        LoadSettings yamlLoadSettings = LoadSettings.builder().build();
        Load yamlLoad = new Load(yamlLoadSettings);
        Map<String, Object> cff = new LinkedHashMap<>();
        if(input != null && input.isFile()) {
            try(FileInputStream inputFile = new FileInputStream(input)) {
                yamlLoad.loadFromInputStream(inputFile);
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
        cff.putIfAbsent("authors", authors);

        // collect references to add
        List<HashMap<String, Object>> references = new LinkedList<>();
        for (Artifact artifact : project.getArtifacts()) {
            try {
                getLog().info("Adding dependency: " + artifact.toString());
                HashMap<String, Object> refInfo = new HashMap<>();

                refInfo.put("title", artifact.getArtifactId());

                String license = "UNKNOWN";

                references.add(refInfo);

                if(!P2_PLUGIN_GROUP_ID.equals(artifact.getGroupId())) {
                    ProjectBuildingResult result = mavenProjectBuilder.build(artifact, projectBuildingRequest);

                    for (License depLicense : result.getProject().getLicenses()) {
                        getLog().info("" + artifact.toString() + " has license " + depLicense.getName());
                    }
                }

                refInfo.put("license", license);

            } catch (ProjectBuildingException ex) {
                getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
            }

        }
        cff.put("reference", references);

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
}