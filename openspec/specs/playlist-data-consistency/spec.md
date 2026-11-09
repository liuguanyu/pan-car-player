# playlist-data-consistency Specification

## Purpose
TBD - created by archiving change refactor-playback-and-data-boundaries. Update Purpose after archive.
## Requirements
### Requirement: Playlist mutations are transactional
Adding, deleting, replacing songs, and updating derived playlist metadata SHALL be committed atomically.

#### Scenario: A song insertion fails
- **WHEN** song insertion fails during an add operation
- **THEN** no partial song or count update SHALL remain

#### Scenario: Concurrent additions target one playlist
- **WHEN** concurrent add operations target the same playlist
- **THEN** the final song set and count SHALL reflect all successful unique additions without lost updates

### Requirement: Cloud refresh uses a validated snapshot
The system SHALL preserve the existing local playlist until a complete cloud refresh snapshot has been validated and can be atomically applied.

#### Scenario: Cloud scanning partially fails
- **WHEN** scanning a cloud playlist returns an incomplete or failed result
- **THEN** the existing local playlist SHALL remain unchanged and the operation SHALL report failure

### Requirement: Database schema upgrades rebuild local data
The local database SHALL be treated as rebuildable data. A schema version increase SHALL recreate the database instead of maintaining explicit migrations.

#### Scenario: An existing database is upgraded
- **WHEN** an installed database is opened after a schema version increase
- **THEN** the database SHALL be recreated with the new schema and the app SHALL start with empty local playlists that the user can rebuild

### Requirement: Derived song counts have one authoritative source
The system SHALL calculate or update playlist song counts through one transactionally consistent mechanism.

#### Scenario: A playlist is read after mutations
- **WHEN** songs have been added or removed
- **THEN** the reported count SHALL equal the number of associated songs

