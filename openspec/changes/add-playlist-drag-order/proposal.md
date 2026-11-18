## Why

歌单列表页（`MainActivity`）一直按 `createdTime DESC` 固定排列，用户无法按自己的使用频率整理列表：常用的车机歌单如果创建得早，就永远沉在列表底部。需要支持手动排序，并且排序结果要在重启/升级后保留。

## What Changes

- `playlists` 表新增 `sortOrder` 列（升序），列表页查询改为 `ORDER BY sortOrder ASC, createdTime DESC`。
- 列表行新增拖动手柄（`ic_menu_sort_by_size`）；按下手柄即开始拖动（`ItemTouchHelper`），**长按整行仍然弹出列表菜单**，两个手势不冲突。
- 拖动过程只改视图顺序，松手后一次性把当前顺序写入数据库（`PlaylistManager.reorderPlaylists`，单事务、逐行 `UPDATE sortOrder`）；写库失败则提示并回读数据库顺序。
- 新建歌单排在列表最前（`minSortOrder - 1`）；导入的歌单按文件顺序排在已有列表之前。
- 数据库版本 3 → 4：新增 `MIGRATION_3_4`（`ALTER TABLE ADD COLUMN` + 按 `createdTime` 换算 `sortOrder`），**升级不重建库、已有歌单和歌曲全部保留**。

## Capabilities

### New Capabilities
- `playlist-ordering`: 列表页手动排序的交互方式、持久化语义与新建/导入列表的插入位置。

### Modified Capabilities
- `playlist-data-consistency`: 原来要求“schema 版本升级一律重建数据库”，改为“纯增列升级 MUST 走显式迁移，仅在无迁移路径时才重建”，并明确 3→4 迁移的生命周期。

## Impact

- 数据：`Playlist` 新增 `sortOrder`；`PlaylistDao` 查询排序与 `minSortOrder` / `updateSortOrder`；`AppDatabase` 版本 4；`DatabaseManager` 新增 `MIGRATION_3_4`。
- 逻辑：`PlaylistManager.createPlaylist` / `importPlaylistsNow` 写入排序值，新增 `reorderPlaylists`。
- UI：`item_playlist.xml` 增加拖动手柄，`PlaylistAdapter` 支持拖动起点与 `moveItem`，`MainActivity` 挂载 `ItemTouchHelper`。
- 兼容：升级路径下不丢数据；从 v1/v2 直接升级仍按原策略重建（无迁移链）。
- 依赖：无新增第三方库（`ItemTouchHelper` 来自已有 recyclerview）。
