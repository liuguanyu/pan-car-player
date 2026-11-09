## ADDED Requirements

### Requirement: Export entry and playlist selection
The playlist maintenance page SHALL offer an "导出列表" action. The user SHALL choose which playlists to export (all selected by default) and then the target: 本机文件 or 百度网盘.

#### Scenario: No playlists
- **WHEN** the user taps 导出列表 and there is no local playlist
- **THEN** the system SHALL show "没有可导出的播放列表"

#### Scenario: Nothing selected
- **WHEN** the user deselects every playlist and confirms
- **THEN** the system SHALL show "请至少选择一个列表" and SHALL NOT continue

### Requirement: Default file name
The default backup file name SHALL be `playlists-backup-YYYY-MM-DD.json` using the local date; the user SHALL be able to edit it, and `.json` SHALL be appended when missing. File names containing `/` or empty names SHALL be rejected.

#### Scenario: Default name
- **WHEN** the user exports on 2026-11-09
- **THEN** the suggested name SHALL be `playlists-backup-2026-11-09.json`

#### Scenario: Missing extension
- **WHEN** the user enters `car-lists`
- **THEN** the file SHALL be written as `car-lists.json`

### Requirement: Export to local storage
The system SHALL let the user choose a local directory (see local-storage-access) and write the backup there as UTF-8 JSON. An existing file with the same name SHALL be overwritten after confirmation.

#### Scenario: Successful local export
- **WHEN** the user picks a directory and confirms
- **THEN** the file SHALL be written and the system SHALL show "已导出到 <完整路径>"

#### Scenario: Local file exists
- **WHEN** a file with the same name exists in the chosen directory
- **THEN** the system SHALL ask "文件已存在，是否覆盖？" and overwrite only if confirmed

#### Scenario: Write fails
- **WHEN** writing the file fails
- **THEN** the system SHALL show the error and SHALL NOT leave a partially written file

### Requirement: Export to Baidu Pan
The system SHALL let the user choose a Baidu Pan directory and upload the backup with the three-step upload (precreate → superfile2 upload → create). A file with the same path SHALL be overwritten (`rtype=3`).

#### Scenario: Successful cloud export
- **WHEN** the user picks a cloud directory and confirms
- **THEN** the backup SHALL be uploaded to `<目录>/<文件名>` and the system SHALL show "已上传到网盘 <路径>"

#### Scenario: Same file exists in the cloud
- **WHEN** a file with the same path already exists in Baidu Pan
- **THEN** it SHALL be overwritten without creating a renamed copy

#### Scenario: Instant upload
- **WHEN** precreate returns `return_type = 2`
- **THEN** the upload SHALL be treated as successful without uploading content

#### Scenario: Any upload step fails
- **WHEN** precreate, upload or create returns a non-zero errno or a network error
- **THEN** the system SHALL show "上传失败: <原因>" with secrets redacted

#### Scenario: Not logged in
- **WHEN** the user chooses cloud export without a valid token
- **THEN** the system SHALL show "未登录，请先登录"
