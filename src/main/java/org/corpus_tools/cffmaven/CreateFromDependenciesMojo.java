package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuilderConfiguration;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.plexus.component.annotations.Requirement;

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
    private File citationFile;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Component
    private ProjectBuilder mavenProjectBuilder;

    public void execute() throws MojoExecutionException {

        getLog().info("maven session: " + mavenSession);
        getLog().info("project builder: " + mavenProjectBuilder);

        ProjectBuildingRequest projectBuildingRequest = new DefaultProjectBuildingRequest(
                mavenSession.getProjectBuildingRequest()).setRemoteRepositories(remoteRepositories)
                        .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL).setResolveDependencies(false)
                        .setProcessPlugins(false);

        HashMap<String, Object> scopes = new HashMap<>();

        // set basic properties like title
        scopes.put("title", project.getName());
        scopes.put("version", project.getVersion());

        List<HashMap<String, Object>> authors = new LinkedList<>();
        for (Developer dev : project.getModel().getDevelopers()) {
            HashMap<String, Object> author = new HashMap<>();
            author.put("name", dev.getName());
            authors.add(author);
        }
        scopes.put("author", authors);

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
        scopes.put("reference", references);

        // Write out the file using the template and the collected information
        MustacheFactory mustacheFactory = new DefaultMustacheFactory();
        try (InputStream cff_template = getClass().getResourceAsStream("citation_template.yaml");
                FileWriter outWriter = new FileWriter(citationFile)) {

            Mustache m = mustacheFactory.compile(new InputStreamReader(cff_template, StandardCharsets.UTF_8), "CFF");
            m.execute(outWriter, scopes);

        } catch (IOException ex) {
            getLog().error("Could create Citation file from template", ex);
        }

    }
}