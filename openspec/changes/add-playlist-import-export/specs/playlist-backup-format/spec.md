## ADDED Requirements

### Requirement: Backup files use the web backup format
The system SHALL read and write playlist backups in the web client's JSON format: a root object with `version` (string), `export_time` (ISO-8601 UTC string) and `playlists` (array). Each playlist SHALL have `name`, `description`, `items`, `create_time` and `update_time` (Unix seconds). Each item SHALL carry `fs_id`, `server_filename`, `path`, `size`, `category`, `isdir`, `local_mtime`, `server_mtime`, `md5`, `add_time` and `duration` (seconds, decimal).

#### Scenario: A web backup is parsed
- **WHEN** the file `playlists-backup-2026-11-09.json` produced by the web client is parsed
- **THEN** every playlist SHALL be returned with its name and its items in file order, each item mapped to a song with `fsId = fs_id`, `title = server_filename`, `path`, `size` and `duration` converted from seconds to milliseconds

#### Scenario: A backup is written
- **WHEN** local playlists are exported
- **THEN** the output SHALL contain `version = "1.0"`, the current UTC `export_time`, and one entry per playlist with all songs in playlist order using the field names above

#### Scenario: Round trip preserves content
- **WHEN** an exported file is parsed again
- **THEN** playlist names, song order, `fs_id`, `server_filename`, `path`, `size` and `duration` SHALL be identical to the source

### Requirement: Unsupported sections are ignored
The system SHALL ignore `recentSongs` and any unknown fields when reading and SHALL NOT write `recentSongs` when exporting.

#### Scenario: A backup contains recent songs
- **WHEN** a backup file containing `recentSongs` is imported
- **THEN** the recent songs SHALL NOT create or modify any playlist

### Requirement: Invalid backups are rejected without side effects
The system SHALL validate the whole file before any database write. A file that is not JSON, lacks a `playlists` array, or contains no playlist SHALL be rejected with a readable error. Items without a positive `fs_id` or with `isdir = 1` SHALL be skipped and counted; a playlist without a name SHALL be named "未命名列表".

#### Scenario: The file is not a backup
- **WHEN** the selected file is not valid JSON or has no `playlists` array
- **THEN** the import SHALL fail with "不是有效的播放列表备份文件" and no data SHALL change

#### Scenario: Some items are invalid
- **WHEN** a playlist contains an item with a missing `fs_id` or a directory item
- **THEN** that item SHALL be skipped, the remaining items imported, and the preview SHALL report the skipped count

#### Scenario: Duplicate songs inside one playlist
- **WHEN** one playlist in the file lists the same `fs_id` more than once
- **THEN** only the first occurrence SHALL be kept
