package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Developer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
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
public class CreateMojo extends AbstractCffMojo {

  protected static final String REFERENCES = "references";

  @Parameter
  private File input;


  @Parameter(defaultValue = "true")
  private boolean skipExistingDependencies;

  @Parameter
  private String dateReleased;


  @Parameter
  private List<TemplateConfiguration> referenceTemplates;

  /**
   * {@inheritDoc}
   */
  public void execute() throws MojoExecutionException {


    LoadSettings yamlLoadSettings = LoadSettings.builder().build();
    Load yamlLoad = new Load(yamlLoadSettings);
    Map<String, Object> cff = new LinkedHashMap<>();
    cff.putIfAbsent("cff-version", "1.2.0");
    cff.putIfAbsent("type", "software");

    if (input != null && input.isFile()) {
      try (FileInputStream inputFile = new FileInputStream(input)) {
        getLog().info("Reading input CFF file " + input.getPath());
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

    ProjectBuildingRequest projectBuildingRequest = createProjectBuildingRequest();

    // set basic properties like title
    cff.putIfAbsent("message", "If you use this software, please cite it as below.");
    cff.putIfAbsent(TITLE, project.getName());
    cff.putIfAbsent(VERSION, project.getVersion());
    if (dateReleased == null) {
      // add current date
      SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
      cff.putIfAbsent("date-released", df.format(new Date()));
    } else {
      cff.put("date-released", dateReleased);
    }

    LinkedHashSet<Map<String, Object>> authors = new LinkedHashSet<>();
    for (Developer dev : project.getModel().getDevelopers()) {
      HashMap<String, Object> author = new HashMap<>();
      author.put("name", dev.getName());
      authors.add(author);
    }

    // If no authors are specified, use generic fallback author info
    if (authors.isEmpty()) {
      getLog().info("No author info found for this project. Creating fallback information.");
      HashMap<String, Object> author = new HashMap<>();
      author.put("name", "The " + cff.get(TITLE) + " " + cff.get(VERSION) + " Team");
      authors.add(author);
    }

    cff.putIfAbsent("authors", new LinkedList<>(authors));


    // Add primary SCM information to CFF
    String scmUrl = getRepositoryCodeUrl(project.getScm());
    if (scmUrl != null) {
      cff.put("repository-code", scmUrl);
    }


    // get existing references and add new ones to the list
    List<Map<String, Object>> references = mapExistingReferences(cff.get(REFERENCES));
    Set<String> existingTitles = references.stream().map(ref -> ref.get(TITLE))
        .filter(title -> title != null).map(title -> title.toString()).collect(Collectors.toSet());

    TreeMap<String, Map<String, Object>> newReferences = new TreeMap<>();

    Map<Pattern, File> templatePatterns = new LinkedHashMap<Pattern, File>();
    if (referenceTemplates != null) {
      for (TemplateConfiguration config : referenceTemplates) {
        Pattern p = Pattern.compile(config.getPattern().toString());
        templatePatterns.put(p, config.getTemplate());
      }
    }

    for (Artifact artifact : project.getArtifacts()) {

      if (!isIgnored(artifact)) {
        try {

          Map<String, Object> newRef = null;

          for (Map.Entry<Pattern, File> entry : templatePatterns.entrySet()) {
            getLog().debug("Testing artifact " + artifact.toString() + " with pattern "
                + entry.getKey().pattern());
            if (entry.getKey().matcher(artifact.toString()).matches()) {
              try {
                getLog().info("Adding reference " + artifact.toString() + " from template "
                    + entry.getValue().getPath());
                newRef = createReferenceFromTemplate(artifact, projectBuildingRequest,
                    entry.getValue(), yamlLoad);
                break;
              } catch (IOException e) {
                getLog().error("Could create reference from template " + entry.getValue().getPath(),
                    e);
              }
            }
          }

          if (newRef == null) {
            // no pattern matched
            newRef = createReference(artifact, projectBuildingRequest);
          }
          String newRefTitle = (String) newRef.getOrDefault(TITLE, "");
          if (skipExistingDependencies && existingTitles.contains(newRefTitle)) {
            getLog().info("Ignoring existing dependency " + artifact.toString());
          } else if (!newReferences.containsKey(newRefTitle)) {
            getLog().info("Adding reference " + artifact.toString());
            newReferences.put(newRefTitle, newRef);
          }
        } catch (ProjectBuildingException ex) {
          getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
        }
      }
    }

    // add all new references to the list
    for (Map<String, Object> ref : newReferences.values()) {
      references.add(ref);
    }

    // Remove references first, then add them again to place them at the end of the file.s
    cff.remove(REFERENCES);
    cff.put(REFERENCES, references);

    // Write out the YAML file again
    DumpSettings dumpSettings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build();
    Dump dumpYaml = new Dump(dumpSettings);
    try (FileWriter outWriter = new FileWriter(output)) {
      String yamlAsString = dumpYaml.dumpToString(cff);
      outWriter.write(yamlAsString);
    } catch (IOException ex) {
      getLog().error("Could not write Citation file", ex);
    }

    closeCache();
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

}
