## ADDED Requirements

### Requirement: Import entry on the playlist page
The playlist maintenance page SHALL offer an "导入列表" action that lets the user choose the source: 本机文件 or 百度网盘.

#### Scenario: User opens import
- **WHEN** the user taps 更多 → 导入列表
- **THEN** a dialog SHALL offer "从本机文件导入" and "从百度网盘导入"

### Requirement: Import from Baidu Pan
The system SHALL let the user browse Baidu Pan folders and pick a `.json` file; only folders and `.json` files SHALL be listed. The file SHALL be downloaded through the file's download link using the current access token.

#### Scenario: User picks a cloud backup
- **WHEN** the user selects a `.json` file in the cloud browser
- **THEN** the file content SHALL be downloaded and parsed, and the import preview SHALL be shown

#### Scenario: Not logged in
- **WHEN** the user chooses cloud import without a valid token
- **THEN** the system SHALL show "未登录，请先登录" and SHALL NOT open the browser

#### Scenario: Download fails
- **WHEN** fetching the download link or file content fails
- **THEN** the system SHALL show the redacted error and no data SHALL change

### Requirement: Import from local storage
The system SHALL let the user browse local storage and pick a `.json` file (see local-storage-access).

#### Scenario: User picks a local backup
- **WHEN** the user selects a `.json` file in the local browser
- **THEN** the file SHALL be read as UTF-8, parsed, and the import preview SHALL be shown

### Requirement: Import preview and confirmation
Before writing, the system SHALL show every playlist from the file with its song count and its final local name, all selected by default; the user SHALL be able to deselect playlists. Nothing SHALL be written until the user confirms.

#### Scenario: User cancels the preview
- **WHEN** the user cancels the preview
- **THEN** no playlist SHALL be created

#### Scenario: User deselects a playlist
- **WHEN** the user deselects one playlist and confirms
- **THEN** only the selected playlists SHALL be created

### Requirement: Name conflicts create new playlists
Imported playlists SHALL always be created as new playlists; existing playlists SHALL never be modified. If the name already exists locally (or earlier in the same import), the new playlist SHALL be named "名称 (导入)", then "名称 (导入2)", "名称 (导入3)" … until unique.

#### Scenario: Name already exists
- **WHEN** a playlist named "生肖" exists and a backup containing "生肖" is imported
- **THEN** a new playlist "生肖 (导入)" SHALL be created and the existing "生肖" SHALL be unchanged

#### Scenario: Imported twice
- **WHEN** the same backup is imported a second time
- **THEN** the new playlist SHALL be named "生肖 (导入2)"

#### Scenario: No conflict
- **WHEN** no local playlist has the imported name
- **THEN** the playlist SHALL keep its original name

### Requirement: Import is atomic and order-preserving
All selected playlists and their songs SHALL be written in one database transaction; song order SHALL follow the file order; song counts SHALL be recomputed in the same transaction.

#### Scenario: Write fails midway
- **WHEN** a database error occurs while inserting the third playlist
- **THEN** none of the playlists from this import SHALL remain

#### Scenario: Song order
- **WHEN** an imported playlist is opened
- **THEN** its songs SHALL appear in the same order as in the file

#### Scenario: Import result
- **WHEN** the import succeeds
- **THEN** the system SHALL show "已导入 N 个列表，共 M 首歌曲" and refresh the playlist page
