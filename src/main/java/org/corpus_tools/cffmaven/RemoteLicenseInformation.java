package org.corpus_tools.cffmaven;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class RemoteLicenseInformation implements Serializable {
  private static final long serialVersionUID = -9210187411126111132L;

  private String spdx;
  private List<String> authors = new LinkedList<>();
  private long score = 0;

  public String getSpdx() {
    return spdx;
  }

  public void setSpdx(String spdx) {
    this.spdx = spdx;
  }

  public List<String> getAuthors() {
    return authors;
  }

  public void setAuthors(List<String> authors) {
    this.authors = authors;
  }

  public long getScore() {
    return score;
  }

  public void setScore(long score) {
    this.score = score;
  }
}
