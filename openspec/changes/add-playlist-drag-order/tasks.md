## 1. 数据层

- [x] 1.1 `Playlist` 新增 `sortOrder`（含 getter/setter 与带排序值的 `@Ignore` 构造器）
- [x] 1.2 `PlaylistDao`：两个查询改为 `ORDER BY sortOrder ASC, createdTime DESC`，新增 `minSortOrder()` / `updateSortOrder(id, sortOrder)`
- [x] 1.3 `AppDatabase` 版本 3 → 4；`DatabaseManager` 新增 `MIGRATION_3_4`（增列 + 按 `createdTime` 换算）并注入 `addMigrations`
- [x] 1.4 `PlaylistManager.createPlaylist` 写 `minSortOrder - 1`；`importPlaylistsNow` 按文件顺序写 `minSortOrder - n + i`
- [x] 1.5 `PlaylistManager.reorderPlaylists`：单事务按下标重写 `sortOrder`，空列表直接成功

## 2. 列表页拖动

- [x] 2.1 `item_playlist.xml` 新增行尾拖动手柄（排序图标、48dp 热区、contentDescription）
- [x] 2.2 `PlaylistAdapter`：`OnPlaylistDragStartListener`（手柄 `ACTION_DOWN` 触发）、`moveItem`、`notifyOrderChanged`、`getPlaylists` 快照
- [x] 2.3 `MainActivity.initDragSort`：`ItemTouchHelper.SimpleCallback`（仅上下拖动、关闭长按拖动、拖动中半透明），落位后持久化，失败提示并回读
- [x] 2.4 长按整行 → 列表菜单，点击 → 进入歌单，行为与之前一致

## 3. 验证

- [x] 3.1 `PlaylistManagerRoomTest`：新建列表排最前、拖动后顺序落库并影响后续新建、重排不影响歌曲与数量
- [x] 3.2 `PlaylistSortOrderMigrationTest`：v3 旧库升级到 v4 后歌单/歌曲保留，顺序与升级前一致
- [x] 3.3 `PlaylistImportTest`（既有）：导入仍按文件顺序排在已有列表之前
- [x] 3.4 `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全量通过
