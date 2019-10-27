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

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Create Citation File Format with references from the dependencies defined via
 * Maven.
 *
 */
@Mojo(name = "dependencies")
public class CreateFromDependenciesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${basedir}/CITATION.cff")
    private File citationFile;

    public void execute() throws MojoExecutionException {

        HashMap<String, Object> scopes = new HashMap<>();

        // set basic properties like title
        scopes.put("title", project.getName());
        scopes.put("version", project.getVersion());

        // collect references to add
        List<HashMap<String, Object>> references = new LinkedList<>();
        for (Dependency dep : project.getModel().getDependencies()) {
            getLog().info("Adding dependency: " + dep.getGroupId() + ":" + dep.getArtifactId());
            HashMap<String, Object> refInfo = new HashMap<>();

            refInfo.put("title", dep.getArtifactId());

            references.add(refInfo);
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