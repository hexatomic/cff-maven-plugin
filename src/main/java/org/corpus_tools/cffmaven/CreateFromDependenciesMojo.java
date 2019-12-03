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
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Developer;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * Create Citation File Format with references from the dependencies defined via Maven.
 *
 */
@Mojo(name = "create", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CreateFromDependenciesMojo extends AbstractCffMojo {

  private static final Pattern INCLUDE_THIRD_PARTY_FILE_PATTERN = Pattern.compile(
      "(META-INF/)?((NOTICE|DEPENDENCIES|about|license|LICENSE)"
      + "(\\.md|\\.txt|\\.html|\\.rst)?)|(about_files/.+)");

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

  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
  protected List<ArtifactRepository> remoteRepositories;

  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession mavenSession;

  /**
   * {@inheritDoc}
   */
  public void execute() throws MojoExecutionException {

    LoadSettings yamlLoadSettings = LoadSettings.builder().build();
    Load yamlLoad = new Load(yamlLoadSettings);
    Map<String, Object> cff = new LinkedHashMap<>();
    cff.putIfAbsent("cff-version", "1.1.0");

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

    ProjectBuildingRequest projectBuildingRequest =
        new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest())
            .setRemoteRepositories(remoteRepositories)
            .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
            .setResolveDependencies(false).setProcessPlugins(false);

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
    if (!authors.isEmpty()) {
      cff.putIfAbsent("authors", authors);
    }

    // get existing references and add new ones to the list
    List<Map<String, Object>> references = mapExistingReferences(cff.get("references"));
    Set<String> existingTitles = references.stream().map(ref -> ref.get("title"))
        .filter(title -> title != null).map(title -> title.toString()).collect(Collectors.toSet());

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
    for (Map<String, Object> ref : newReferences.values()) {
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
            String entryPath =
                currentEntry.getName().replace('\\', '/').replaceFirst("^META-INF/", "");
            getLog().debug("Checking zip file entry \"" + entryPath
                + "\" for inclusion in third party folder.");
            if (INCLUDE_THIRD_PARTY_FILE_PATTERN.matcher(entryPath).matches()) {
              // copy this file to the output folder
              File outputFile = new File(artifactFolder, entryPath);
              if (outputFile.exists()) {
                getLog().warn("Not overwriting existing file " + outputFile.getPath());
              } else {
                getLog().info("Copying " + entryPath + " from artifact to " + outputFile.getPath());
                if (outputFile.getParentFile().isDirectory()
                    || outputFile.getParentFile().mkdirs()) {
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
