## ADDED Requirements

### Requirement: Playlist page supports manual drag ordering
The playlist maintenance page SHALL let the user reorder playlists by dragging a dedicated drag handle on each row. The long-press row gesture SHALL continue to open the playlist menu (刷新 / 重命名 / 删除), and row tap SHALL continue to open the song list.

#### Scenario: User drags a row to a new position
- **WHEN** the user presses the drag handle of a row and moves it above or below other rows
- **THEN** the rows SHALL move visually in real time while the handle is held
- **AND** only the view order SHALL change during the drag; no database write SHALL happen until the row is dropped

#### Scenario: User drops the row
- **WHEN** the user releases the handle after a reorder
- **THEN** the new order SHALL be persisted to the database in one transaction
- **AND** the persisted order SHALL be restored on the next page load and after app restart

#### Scenario: Persistence fails
- **WHEN** writing the new order to the database fails
- **THEN** the app SHALL show an error and SHALL reload the list order from the database so the UI matches stored data

### Requirement: Reordered order is authoritative and stable
The displayed playlist order SHALL be `sortOrder` ascending, with `createdTime` descending as a tie-breaker. Dragging SHALL rewrite `sortOrder` as dense indices, so values do not grow unbounded.

#### Scenario: List is read after a manual reorder
- **WHEN** playlists are loaded after the user dragged rows
- **THEN** they SHALL appear in the exact order the user left them

### Requirement: New and imported playlists insert at the top
Creating a new playlist SHALL place it at the top of the list, preserving the pre-drag-ordering behavior. Importing playlists SHALL place them above all existing playlists, in file order.

#### Scenario: User creates a playlist after having dragged rows
- **WHEN** a new playlist is created
- **THEN** it SHALL appear at the top and the previously dragged order SHALL be unchanged below it

#### Scenario: User imports a backup after having dragged rows
- **WHEN** a backup with multiple playlists is imported
- **THEN** the imported playlists SHALL appear at the top in file order and the existing list order SHALL be unchanged below them
