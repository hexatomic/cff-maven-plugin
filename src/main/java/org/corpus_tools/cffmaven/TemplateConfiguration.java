package org.corpus_tools.cffmaven;

import java.io.File;
import org.apache.maven.plugins.annotations.Parameter;

public class TemplateConfiguration {
  @Parameter
  private String pattern;
  
  @Parameter
  private File template;

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public File getTemplate() {
    return template;
  }

  public void setTemplate(File template) {
    this.template = template;
  }
}
