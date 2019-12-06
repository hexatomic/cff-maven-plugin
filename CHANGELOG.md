# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- SCM URL information is added as `repository-code` field
- Add notice which points to the third-party folder of this artifact (#5)

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

[Unreleased]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.6...HEAD
[0.2.6]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/hexatomic/cff-maven-plugin/compare/v0.1.0...v0.2.0
