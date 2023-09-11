# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed 

- Updated `org.json:json`` dependency due to CVE-2022-45688

## [0.8.0] - 2023-03-07

### Fixed 

- The `third-party-folder` goal now also actually respects the
  `referenceTemplates` parameter. In case you have it configured, this also
  speeds the lookup for P2 artifacts and makes sure the information in
  `CITATION.cff` and the `THIRD-PARTY` folder are synchronized. This was already
  described in the documentation, but not actually implemented.

### Added

- Added cache for requests to external servers (like clearlydefined.io or the
  SPDX license server).


## [0.7.0] - 2023-03-03

### Fixed

- Only try to open jar-files when expecting artifact contents. Trying to open
  other file types will create an exception about an empty ZIP file.

## [0.6.0] - 2022-06-20 

### Fixed

- Use a pattern instead of a fixed string to determine if this is a P2 artifact.


## [0.5.0] - 2021-08-21

### Changed

- Support CFF version 1.2.0
- Updated checkstyle to 8.29

## [0.4.0] - 2019-12-06

### Added

- Add `ignoredArtifacts` parameter for ignoring artifacts if their string representation matches a regular expression.

## [0.3.4] - 2019-12-06

### Fixed

- `institution` was not correctly used in the example templates
- Ignore more invalid SCM URL patterns
- Reference to CFF was not formatted correctly

## [0.3.3] - 2019-12-06

### Fixed

- Create an empty author list for P2 artifacts even if the remote query failed

## [0.3.2] - 2019-12-06

### Fixed

- When there was an existing reference in the input file, added fields like "date-released" 
where located after the references.

## [0.3.1] - 2019-12-06

### Fixed

- Allow dot characters in the third party folder name derived from the title

## [0.3.0] - 2019-12-06

### Added

- SCM URL information is added as `repository-code` field
- Add notice which points to the third-party folder of this artifact (#5)
- Download license to third-party folder if its empty otherwise

## [0.2.6] - 2019-12-05

### Fixed

- Only break when pattern was found, not when first pattern was added.

## [0.2.5] - 2019-12-05

### Fixed

- Use empty author list for P2 artifacts instead of omitting the field

## [0.2.4] - 2019-12-05

### Fixed

- Add a URL to the SCM which can be accessed by a browser

## [0.2.3] - 2019-12-05

### Fixed

- Use release profile when executing gitflow release/hotfix finish

## [0.2.2] - 2019-12-05

### Fixed

- Some Sonatype requirements like signing the artifactss or a description were not met
- Close Sonatype staging repository automatically when deploying

## [0.2.1] - 2019-12-05

### Fixed

- Configure Gitflow Plugin to deploy after release

## [0.2.0] - 2019-12-05

### Added

- Deploy releases on Maven Central

## [0.1.0] - 2019-12-05

First public release

[Unreleased]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.3.4...v0.4.0
[0.3.4]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.6...v0.3.0
[0.2.6]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.1.0...v0.2.0
