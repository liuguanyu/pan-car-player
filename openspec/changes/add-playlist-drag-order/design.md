## Context

列表页 `MainActivity` 用 `RecyclerView` + `LinearLayoutManager` + `PlaylistAdapter` 展示歌单，数据来自 `PlaylistManager.getAllPlaylists`（`ORDER BY createdTime DESC`）。行内已有两个手势：点击进入歌单，长按弹出「刷新 / 重命名 / 删除」菜单。

## Decisions

### 拖动入口：行尾手柄，而不是长按整行

长按已被菜单占用。若改成「长按拖动」，就会丢掉长按菜单的可发现性（虽然右侧还有 `⋮`）。

- 采用：行尾新增拖动手柄（`playlist_drag_handle`，48dp 热区），`ACTION_DOWN` 时调用 `ItemTouchHelper.startDrag`。
- `isLongPressDragEnabled()` 返回 `false`，长按整行行为完全不变。
- 拖动中的行 `alpha = 0.7`，松手复位。

备选（未采用）：长按整行拖动 + 去掉长按菜单。车机上「长按选中再拖」也很常见，但会破坏现有交互，收益不明显。

### 持久化时机：松手写一次，而不是每次 `onMove`

`onMove` 在拖动过程中会被高频调用；逐次写库没有意义，还会让拖动期间的回读与视图不一致。

- 拖动中只做 `playlists.add(to, playlists.remove(from))` + `notifyItemMoved`，置 `playlistOrderDirty`。
- `clearView`（落位）时若有变更：`notifyOrderChanged()` 重绘（背景斑马纹/高亮依赖 `position`）+ `persistPlaylistOrder()`。
- `reorderPlaylists` 在一个事务里按新下标逐行 `UPDATE playlists SET sortOrder = ?`（只更新排序列，不用 `@Update` 整行，避免覆盖并发写入的 `songCount`）。
- 写库失败：Toast 提示并 `loadPlaylists()` 以数据库为准回滚界面。

### 排序语义

- 查询：`ORDER BY sortOrder ASC, createdTime DESC`；`sortOrder` 只用于排序，不要求连续。
- 新建歌单：`minSortOrder - 1`，保持原来「新建排最前」的观感（在事务内读取 + 插入，避免并发读到旧极值）。
- 导入歌单：`minSortOrder - n + i`，按文件顺序插在已有列表之前（与原 `createdTime = now + (n - i)` 的效果一致）。
- 手动拖动后 `sortOrder` 被重写为 `0..n-1`，值域不会无限增长。

### Schema 升级：显式迁移，不再重建库

原来 `fallbackToDestructiveMigration()` + 每次版本 +1 意味着升级即清库；`sortOrder` 是纯增列，为此清掉用户歌单没有必要。

- `MIGRATION_3_4`：`ALTER TABLE playlists ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0`，再按 `createdTime` 倒序换算成 `0..n-1`，升级后列表顺序与升级前完全一致。
- 保留 `fallbackToDestructiveMigration()`：v1/v2 直升级仍走重建（无迁移链，符合「本地库可重建」的既有前提）。
- Room 的迁移是连续版本链：`3→4` 必须留到「不再支持 v3 直接升级」为止；届时应作为一次显式决策删除，而不是随手清理。

### 不在本次范围

- 排序值不写进备份文件（`playlist-backup-format` 不变），导出再导入按导入时的插入规则排列。
- 歌单内歌曲的拖动排序不在本次范围。
- `PlaylistGridAdapter` 未被列表页使用，未同步改动。

## Risks

- 车机上拖动手柄热区偏小：48dp 已是 Material 最小触控尺寸，若实测不好点，可加大到 `icon_button_size`（64dp）。
- 迁移没有 schema JSON（`exportSchema = false`），无法用 `MigrationTestHelper` 做校验；改为「用 v3 建表语句造旧库 → 打开新版本库」的 Robolectric 测试覆盖。
