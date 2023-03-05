File actualFile = new File( basedir, "CITATION.cff" );
File expectedFile = new File("src/it/default-config/CITATION.cff")

org.corpus_tools.cffmaven.FileDiff.compare(expectedFile, actualFile);

File folder = new File( basedir, "THIRD-PARTY" );
if ( !folder.isDirectory() )
{
    throw new FileNotFoundException( "Could not find generated THIRD-PARTY folder: " + folder );
}