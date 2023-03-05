package org.corpus_tools.cffmaven;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

public class FileDiff {

  public static void compare(File expected, File actual) throws Exception {
    if (!actual.isFile()) {
      throw new FileNotFoundException("Could not find generated file: " + actual);
    }
    List<String> expectedLines = Files.readAllLines(expected.toPath());
    List<String> actualLines = Files.readAllLines(actual.toPath());
    Patch<String> diff = DiffUtils.diff(expectedLines, actualLines);

    // Ignore all deltas which are only changing the date
    List<AbstractDelta<String>> deltas =
        diff.getDeltas().stream().filter(d -> !canIgnoreDelta(d)).collect(Collectors.toList());


    if (!deltas.isEmpty()) {

      List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(expected.getPath(),
          actual.getPath(), expectedLines, diff, 0);

      StringBuilder sb = new StringBuilder(actual + " has wrong content:\n");
      for (String row : unifiedDiff) {
        sb.append(row);
        sb.append("\n");
      }

      throw new Exception(sb.toString());
    }
  }

  private static boolean canIgnoreDelta(AbstractDelta<String> delta) {
    if (delta.getType() == DeltaType.CHANGE) {
      if (delta.getSource().size() == 1 && delta.getTarget().size() == 1) {
        String sourceLine = delta.getSource().getLines().get(0);
        String targetLine = delta.getTarget().getLines().get(0);
        if (sourceLine.startsWith("date-released:") && targetLine.startsWith("date-released:")) {
          return true;
        } else if (sourceLine.startsWith("version:") && targetLine.startsWith("version:")) {
          return true;
        }
      }
    }
    return false;
  }

}
