File file = new File( basedir, "CITATION.cff" );
if ( !file.isFile() )
{
    throw new FileNotFoundException( "Could not find generated CITATION.cff file: " + file );
}

File folder = new File( basedir, "THIRD-PARTY" );
if ( !folder.isDirectory() )
{
    throw new FileNotFoundException( "Could not find generated THIRD-PARTY folder: " + folder );
}