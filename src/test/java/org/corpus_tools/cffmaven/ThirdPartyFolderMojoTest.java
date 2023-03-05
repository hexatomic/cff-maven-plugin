package org.corpus_tools.cffmaven;


import static org.junit.Assert.assertNotNull;

import java.io.File;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.junit.Rule;
import org.junit.Test;

public class ThirdPartyFolderMojoTest {


  @Rule
  public MojoRule rule = new MojoRule() {
    @Override
    protected void before() throws Throwable {}

    @Override
    protected void after() {}
  };

  @Test
  public void testDefaultConfig() throws Exception {


    File pomDir = new File("src/test/resources/default-config/");
    MavenProject project = rule.readMavenProject(pomDir);
    MavenSession session = rule.newMavenSession(project);

    MojoExecution execution = rule.newMojoExecution("third-party-folder");

    ThirdPartyFolderMojo myMojo =
        (ThirdPartyFolderMojo) rule.lookupConfiguredMojo(session, execution);
    assertNotNull(myMojo);

    myMojo.execute();

  }
}
