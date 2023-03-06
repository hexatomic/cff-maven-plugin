package org.corpus_tools.cffmaven;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class FileDiff {

  public static void compare(File expected, File actual) throws Exception {
    compare(expected, actual, true);
  }

  public static void compare(File expected, File actual, boolean ignoreDateReleased)
      throws Exception {
    if (!actual.exists()) {
      throw new FileNotFoundException("\"" + actual + "\" is missing");
    }

    if (expected.isFile()) {
      List<String> expectedLines = Files.readAllLines(expected.toPath());
      List<String> actualLines = Files.readAllLines(actual.toPath());
      Patch<String> diff = DiffUtils.diff(expectedLines, actualLines);

      // Ignore all deltas which are only changing the date
      List<AbstractDelta<String>> deltas = ignoreDateReleased
          ? diff.getDeltas().stream().filter(d -> !canIgnoreDelta(d)).collect(Collectors.toList())
          : diff.getDeltas();


      if (!deltas.isEmpty()) {

        String unifiedDiff =
            getUnifiedDiff(expected.getPath(), actual.getPath(), expectedLines, diff);

        throw new Exception(unifiedDiff);
      }
    } else if (expected.isDirectory()) {
      // Collect a sorted list of all file paths for both sided and compare this textual
      // representation of the file tree
      ArrayList<String> expectedFiles =
          Files.walk(expected.toPath()).map(expected.toPath()::relativize).map(Path::toString)
              .sorted().collect(Collectors.toCollection(ArrayList::new));
      ArrayList<String> actualFiles = Files.walk(actual.toPath()).map(actual.toPath()::relativize)
          .map(Path::toString).sorted().collect(Collectors.toCollection(ArrayList::new));

      Patch<String> diff = DiffUtils.diff(expectedFiles, actualFiles);
      if (!diff.getDeltas().isEmpty()) {
        String unifiedDiff = getUnifiedDiff("expected-files", "actual-files", expectedFiles, diff);
        throw new Exception(unifiedDiff);
      }
      // Check that all files (not directories) are the same
      for (int i = 0; i < expectedFiles.size(); i++) {
        File expectedFile = new File(expected, expectedFiles.get(i));
        File actualFile = new File(actual, actualFiles.get(i));
        if (expectedFile.isFile()) {
          if (actualFile.isFile()) {
            compare(expectedFile, actualFile, ignoreDateReleased);
          } else {
            throw new Exception("\"" + actualFile.getPath() + "\" is not a file.");
          }
        }
      }
    }
  }

  private static String getUnifiedDiff(String expectedFileName, String actualFileName,
      List<String> expectedLines, Patch<String> diff) {
    List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(expectedFileName,
        actualFileName, expectedLines, diff, 0);

    StringBuilder sb = new StringBuilder(actualFileName + " has wrong content:\n");
    for (String row : unifiedDiff) {
      sb.append(row);
      sb.append("\n");
    }
    return sb.toString();
  }

  private static boolean canIgnoreDelta(AbstractDelta<String> delta) {
    if (delta.getType() == DeltaType.CHANGE) {
      if (delta.getSource().size() == 1 && delta.getTarget().size() == 1) {
        String sourceLine = delta.getSource().getLines().get(0);
        String targetLine = delta.getTarget().getLines().get(0);
        if (sourceLine.startsWith("date-released:") && targetLine.startsWith("date-released:")) {
          return true;
        }
      }
    }
    return false;
  }

}
