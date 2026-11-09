## ADDED Requirements

### Requirement: All-files access is requested for local browsing
The app SHALL declare `MANAGE_EXTERNAL_STORAGE`. Before local import/export, if the permission is not granted, the system SHALL explain why and offer to open the system "所有文件访问" settings page for the app.

#### Scenario: Permission already granted
- **WHEN** all-files access is granted
- **THEN** the local browser SHALL open at the shared storage root (`/storage/emulated/<user>`)

#### Scenario: Permission not granted
- **WHEN** all-files access is not granted
- **THEN** the system SHALL show a dialog with "去授权" and "仅使用应用目录"

#### Scenario: Settings page unavailable on the head unit
- **WHEN** the settings intent cannot be resolved
- **THEN** the dialog SHALL show the adb command `adb shell appops set --user <user> com.baidu.carplayer MANAGE_EXTERNAL_STORAGE allow` and offer "仅使用应用目录"

#### Scenario: Returning from settings
- **WHEN** the user returns from the settings page
- **THEN** the permission SHALL be re-checked and the pending import/export SHALL continue automatically if granted

### Requirement: Fallback to the app-specific directory
Without all-files access the local browser SHALL be limited to the app-specific external directory, which needs no permission.

#### Scenario: Fallback browsing
- **WHEN** the user chooses "仅使用应用目录"
- **THEN** the browser SHALL open at `Android/data/com.baidu.carplayer/files` and SHALL NOT navigate above it

### Requirement: Local file browser
The local browser SHALL list folders first then files, sorted by name, show the current path, support going up, and operate in two modes: pick a `.json` file (only folders and `.json` files listed) or pick a directory (only folders listed, with a "选择此目录" action). Unreadable folders SHALL show an error instead of crashing.

#### Scenario: Pick file mode
- **WHEN** the browser is opened for import
- **THEN** only folders and `.json` files SHALL be listed and tapping a file SHALL return it

#### Scenario: Pick directory mode
- **WHEN** the browser is opened for export
- **THEN** only folders SHALL be listed and "选择此目录" SHALL return the current directory

#### Scenario: Going up at the root
- **WHEN** the user presses back at the browsing root
- **THEN** the browser SHALL close without returning a result

#### Scenario: Unreadable folder
- **WHEN** a folder cannot be listed
- **THEN** the browser SHALL show "无法读取该目录" and stay on the previous folder
