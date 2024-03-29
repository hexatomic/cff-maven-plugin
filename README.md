
# Citation File Format Maven plugin

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.corpus-tools/cff-maven-plugin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.corpus-tools/cff-maven-plugin)

This is a helper plugin to create a [CFF](https://citation-file-format.github.io/) file for
a Maven project.
You can generate the citation file of this project by executing
```bash
mvn install && mvn cff:create
```

## Goals

- [cff:create](#cffcreate)
- [cff:third-party-folder](#cffthird-party-folder)
- [Common parameters](#common-parameters)
  - [Curated reference templates](#curated-reference-templates)

### cff:create

```bash
mvn cff:create
```

Create Citation File Format with references from the dependencies defined via Maven.
The third-party references will be generated by multiple sources, e.g. the Maven metadata
or OSGI manifest entries. 
For artifacts, which are located in a P2 repository and mapped to Maven by Eclipse Tycho, heuristics can
be applied to find a corresponding Maven Central artifact or query the [Clearly Defined REST API](https://clearlydefined.io) if the artifact is known in their curated database.



| Parameter                  | Default Value | Description                                                                                                                                                                                                                                                                                                         |
| -------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `input`                    | \<empty\>     | A CFF input file that will be extended. E.g. if you want to add additional information to the CFF file that is not automatically generated, you can write this information into the input file and and the plugin will extend the input file with new information, but will not override existing existing entries. |
| `skipExistingDependencies` | `true`        | If `true`, don't replace existing reference entries from the input file.                                                                                                                                                                                                                                            |

### cff:third-party-folder

Extracts third-party license files like `LICENSE.txt`, `NOTICE` or `about.html` into a folder.

```bash
mvn cff:third-party-folder
```


| Parameter      | Default Value | Description                                                                                       |
| -------------- | ------------- | ------------------------------------------------------------------------------------------------- |
| `deleteFolder` | `true`        | If `true`, deletes the contents of the given third party folder before copying the license files. |

### Common parameters

The following parameters are accepted by all goals and configure the basic behavior like the artifact resolution heuristics.

| Parameter                | Default Value             | Description                                                                                                                                                                                                    |
| ------------------------ | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `output`                 | `${basedir}/CITATION.cff` | The output file                                                                                                                                                                                                |
| `thirdPartyFolder`       | `${basedir}/THIRD-PARTY`  | If available, third-party license files like `NOTICE` or `about.html` are added to a                                                                                                                           |
| `includeEmail`           | `true`                    | If `true`, include the e-mail information from the Maven metadata into author information.                                                                                                                     |
| `referenceTemplates`     | \<empty\>                 | A list of templates for references that is used as replacement for the automatic generated reference entry. This allows to add curated entries when the automatic heuristics fail or information is incorrect. |
| subfolder of this folder |
| `ignoredArtifacts`       | \<empty\>                 | A list of regular expression patterns of artifact IDs to ignore.                                                                                                                                               |
| `p2IgnorePatchLevel`     | `true`                    | Ignore any patch level information when applying the heuristics to P2 artifacts                                                                                                                                |
| `p2ReconstructGroupId`   | `false`                   | For P2 bundles, try to reconstruct a group ID from the bundle name.                                                                                                                                            |

#### Curated reference templates

Sometimes, it can happen that an information in the meta-data of Maven or the other sources is wrong
or incomplete.
To allow manually curated reference entries, you can create a template file for the reference
using the [Handlebars syntax](https://handlebarsjs.com/guide/#simple-expressions).

To configure reference templates, add the following configuration to the `cff-maven-plugin` configuration in Maven (either in the `pluginsManagement/plugins` or `build/plugins` section).

```xml
<plugin>
    <groupId>org.corpus-tools</groupId>
    <artifactId>cff-maven-plugin</artifactId>
    <version>SET VERSION HERE</version>
    <configuration>
        <referenceTemplates>
            <referenceTemplate>
                <pattern>.*:aopalliance:.*</pattern>
                <template>cff-templates/aop.yml</template>
            </referenceTemplate>
        </referenceTemplates>
    </configuration>
</plugin>
```
A single reference pattern configuration consists of two parts: 
- the `pattern` which is a regular expression the whole Maven Artifact ID (including group ID and version)
- a path to the `template` file.
You can add several `<referenceTemplate>` entries to the list.
The first pattern that matches will be used.

The template file itself is a YAML file where you can use Handlebar expressions using the
[Maven `Artifact` POJO as context](https://maven.apache.org/ref/3.5.4/maven-artifact/apidocs/org/apache/maven/artifact/Artifact.html) as in the following example with `{{artifactId}}` or `{{version}}` expressions that directly map to the corresponding fields of the `Artififact` POJO:

```yaml
type: software
title: Java/J2EE AOP standards
abbreviation: '{{artifactId}}'
version: '{{version}}'
license: CC-PDDC
url: http://aopalliance.sourceforge.net/
notes: Library for interoperability for Java AOP implementations.
copyright: Published in the public domain.
authors:
-  name: The AOP Alliance 
```
