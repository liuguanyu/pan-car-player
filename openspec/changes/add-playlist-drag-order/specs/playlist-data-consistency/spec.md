## MODIFIED Requirements

### Requirement: Database schema upgrades preserve local data
A schema version increase SHALL apply an explicit migration whenever the change is additive (a new column with a default, a new table), so existing playlists and songs survive the upgrade. The database SHALL be recreated only when no migration path exists from the installed version.

#### Scenario: An installed database is upgraded with an explicit migration
- **WHEN** an installed database is opened after a schema version increase that has a registered migration
- **THEN** the database SHALL be upgraded in place and existing playlists, songs, and their counts SHALL remain

#### Scenario: A 3 to 4 upgrade adds the drag-order column
- **WHEN** a v3 database (no `sortOrder` column) is opened by the v4 app
- **THEN** the column SHALL be added with existing rows preserved
- **AND** the displayed order SHALL equal the previous `createdTime` descending order
- **AND** no playlist or song SHALL be deleted

#### Scenario: No migration path exists
- **WHEN** an installed database is opened after a schema increase that has no migration from the installed version
- **THEN** the database SHALL be recreated and the app SHALL start with empty local playlists that the user can rebuild

### Requirement: Migration lifecycle is an explicit decision
Registered migrations form Room's continuous version chain and SHALL be kept until the app deliberately stops supporting upgrades directly from that old version. Removing a migration SHALL be a recorded decision (e.g., raising a minimum supported version), because users still on the migration's source version would otherwise silently hit destructive recreation.

#### Scenario: A future change drops an old migration
- **WHEN** a migration is removed in a later release
- **THEN** users still on the source version of that migration SHALL have their database recreated
- **AND** this SHALL be an intentional decision documented in that change
