package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;

/**
 * Extracts third-party license files like "LICENSE.txt", "NOTICE" or "about.html" into a folder.
 * 
 * @author Thomas Krause
 *
 */
@Mojo(name = "third-party-folder",
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ThirdPartyFolderMojo extends AbstractCffMojo {

  private static final Pattern INCLUDE_THIRD_PARTY_FILE_PATTERN =
      Pattern.compile("(META-INF/)?((NOTICE|DEPENDENCIES|about|license|LICENSE)"
          + "(\\.md|\\.txt|\\.html|\\.rst)?)|(about_files/.+)");

  @Parameter(defaultValue = "${basedir}/THIRD-PARTY")
  private File thirdPartyFolder;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    ProjectBuildingRequest projectBuildingRequest = createProjectBuildingRequest();


    for (Artifact artifact : project.getArtifacts()) {
      try {
        Map<String, Object> newRef = createReference(artifact, projectBuildingRequest);
        String newRefTitle = (String) newRef.getOrDefault("title", "");
        getLog().info("Downloading license files for dependency " + artifact.toString());
        String titleForThirdParty = (String) newRefTitle;
        // remove additional information like stuff in (...) at the end
        titleForThirdParty = titleForThirdParty.replaceFirst("\\s*\\([^)]*\\)$", "");
        createThirdPartyFolder(titleForThirdParty, artifact, projectBuildingRequest);

      } catch (ProjectBuildingException ex) {
        getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
      }
    }
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
