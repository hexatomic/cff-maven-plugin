package org.corpus_tools.cffmaven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.ehcache.Cache;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;

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
      Pattern.compile("(meta-info/)?((notice|dependencies|about|license)"
          + "(\\.md|\\.txt|\\.html|\\.rst)?)|(about_files/.+)", Pattern.CASE_INSENSITIVE);

  @Parameter(defaultValue = "true")
  private boolean deleteFolder;

  private Cache<String, String> downloadedLicensesCache;

  /** Constructor. */
  public ThirdPartyFolderMojo() {
    super();

    downloadedLicensesCache = getCacheManager().createCache("downloadedLicenses",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, String.class,
            ResourcePoolsBuilder.newResourcePoolsBuilder().heap(100, EntryUnit.ENTRIES).disk(100,
                MemoryUnit.MB, true)));
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    ProjectBuildingRequest projectBuildingRequest = createProjectBuildingRequest();

    if (deleteFolder && thirdPartyFolder != null && !thirdPartyFolder.getPath().isEmpty()
        && thirdPartyFolder.isDirectory()) {
      try {
        getLog().info("Deleting third party folder " + thirdPartyFolder.getPath());
        FileUtils.deleteDirectory(thirdPartyFolder);
      } catch (IOException e) {
        getLog().error("Could not delete third party folder", e);
      }
    }

    for (Artifact artifact : project.getArtifacts()) {
      if (!isIgnored(artifact)) {
        try {
          Map<String, Object> newRef = createReference(artifact, projectBuildingRequest);
          String newRefTitle = (String) newRef.getOrDefault(TITLE, "");
          String titleForThirdParty = (String) newRefTitle;
          // remove additional information like stuff in (...) at the end
          titleForThirdParty = titleForThirdParty.replaceFirst("\\s*\\([^)]*\\)$", "");
          createThirdPartyFolder(titleForThirdParty, artifact, projectBuildingRequest);

        } catch (ProjectBuildingException ex) {
          getLog().error("Can not resolve dependency artifact " + artifact.toString(), ex);
        }
      }
    }

    closeCache();
    downloadedLicensesCache = null;
  }

  private void createThirdPartyFolder(String title, Artifact artifact,
      ProjectBuildingRequest projectBuildingRequest) {
    // Create a sub-directory for this artifact
    File artifactFolder = getArtifactFolder(title);
    if (artifactFolder != null) {
      // Inspect the JAR file to copy all available license texts and notices
      File file = artifact.getFile();
      if (file != null && file.isFile()
          && "jar".equals(com.google.common.io.Files.getFileExtension(file.getName()))) {

        try (ZipFile artifactFile = new ZipFile(file)) {
          Enumeration<? extends ZipEntry> entries = artifactFile.entries();
          while (entries.hasMoreElements()) {
            ZipEntry currentEntry = entries.nextElement();
            String entryPath =
                currentEntry.getName().replace('\\', '/').replaceFirst("^META-INF/", "");

            if (!entryPath.contains("..")) {

              getLog().debug("Checking zip file entry \"" + entryPath
                  + "\" for inclusion in third party folder.");
              if (INCLUDE_THIRD_PARTY_FILE_PATTERN.matcher(entryPath).matches()) {
                // copy this file to the output folder
                File outputFile = new File(artifactFolder, entryPath);
                if (outputFile.exists()) {
                  getLog().warn("Not overwriting existing file " + outputFile.getPath());
                } else {
                  getLog().info("Copying " + entryPath + " from " + artifact.getGroupId() + ":"
                      + artifact.getArtifactId() + " to " + outputFile.getPath());
                  if (outputFile.getParentFile().isDirectory()
                      || outputFile.getParentFile().mkdirs()) {
                    try (InputStream is = artifactFile.getInputStream(currentEntry)) {
                      Files.copy(is, outputFile.toPath());
                    }
                  }
                }
              }
            }
          }


        } catch (ZipException ex) {
          getLog().warn("Could not open file for artifact " + artifact.getId() + ". Error message: "
              + ex.getMessage());
        } catch (IOException ex) {
          getLog().warn("Could not open file for artifact " + artifact.getId()
              + ". No 3rd party files will be extracted from it.", ex);

        }

      }

      // check if any files have been added
      String[] children = artifactFolder.list();
      if (children == null || children.length == 0) {
        // Folder is empty, attempt to download the license
        try {
          Map<String, Object> reference = createReference(artifact, projectBuildingRequest);
          Object licenseUrl = reference.get("license-url");
          Object licenseId = reference.get("license");

          String licenseFileName = "LICENSE.txt";
          Optional<String> licenseText = Optional.empty();

          if (licenseUrl instanceof String) {
            HttpUrl url = HttpUrl.parse((String) licenseUrl);

            if (downloadedLicensesCache.containsKey(url.toString())) {
              licenseText = Optional.ofNullable(downloadedLicensesCache.get(url.toString()));
            } else {
              Request downloadRequest = new Request.Builder().url(url).build();

              getLog().info(
                  "Downloading license for " + artifact.toString() + " from URL " + licenseUrl);
              try (Response response = http.newCall(downloadRequest).execute()) {
                if (response.code() == 200) {
                  licenseText = Optional.ofNullable(response.body().string());
                  if (licenseText.isPresent()) {
                    downloadedLicensesCache.put(url.toString(), licenseText.get());
                  }
                }
              } catch (IOException e) {
                getLog().error("License download for URL " + licenseUrl + " failed.", e);
              }
            }


          }

          if (!licenseText.isPresent() && licenseId instanceof String) {
            // try to use the SPDX identifier to download the license
            licenseFileName = licenseId + ".txt";

            HttpUrl url = HttpUrl
                .parse("https://raw.githubusercontent.com/spdx/license-list-data/master/text/"
                    + (String) licenseId + ".txt");

            if (downloadedLicensesCache.containsKey(url.toString())) {
              licenseText = Optional.ofNullable(downloadedLicensesCache.get(url.toString()));
            } else {
              Request downloadRequest = new Request.Builder().url(url).build();

              getLog()
                  .info("Downloading license for " + artifact.toString() + " from SPDX repository");

              try (Response response = http.newCall(downloadRequest).execute()) {
                if (response.code() == 200) {
                  licenseText = Optional.ofNullable(response.body().string());
                  if (licenseText.isPresent()) {
                    downloadedLicensesCache.put(url.toString(), licenseText.get());
                  }
                }
              } catch (IOException e) {
                getLog().error("License download for URL " + licenseUrl + " failed.", e);
              }
            }
          }

          if (licenseText.isPresent()) {
            if (artifactFolder.mkdirs()) {
              // Write out as LICENSE.txt
              File outputFile = new File(artifactFolder, licenseFileName);
              try {
                FileUtils.writeStringToFile(outputFile, licenseText.get());
              } catch (IOException e) {
                getLog().error("Writing license file to " + outputFile.getPath() + " failed.", e);
              }
            }
          }

        } catch (ProjectBuildingException e) {
          getLog().error("Could not construct Maven project descriptor", e);
        }

      }
    }
  }

}
