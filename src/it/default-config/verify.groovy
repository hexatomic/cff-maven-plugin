File expectedFile = new File("src/it/default-config/CITATION.cff")
File actualFile = new File( basedir, "CITATION.cff" );

org.corpus_tools.cffmaven.FileDiff.compare(expectedFile, actualFile);

File expectedFolder = new File("src/it/default-config/THIRD-PARTY")
File actualFolder = new File( basedir, "THIRD-PARTY" );
org.corpus_tools.cffmaven.FileDiff.compare(expectedFolder, actualFolder, false);
