## Why

播放列表只存在本机 Room 数据库中，换车机、清数据或在 Web 端/命令行端整理好的列表都无法迁移到车载播放器。Web 端已有 `playlists-backup-YYYY-MM-DD.json` 备份格式，车载端需要能读写同一格式，并且备份既能放在本机存储，也能放在百度网盘（车机上更方便取用）。

## What Changes

- 列表维护页（`MainActivity`）标题栏新增“更多”菜单：**导入列表**、**导出列表**。
- 新增备份文件编解码：读取/写出 Web 端 `version 1.0` 格式（`playlists[].items[]`，字段 `fs_id`/`server_filename`/`path`/`size`/`md5`/`duration` 等）；`recentSongs` 不处理（导入忽略，导出不写）。
- **导出**：选择要导出的列表（默认全选）→ 选择目标：
  - 本机文件系统：内置本地目录浏览器选目录 + 文件名（默认 `playlists-backup-YYYY-MM-DD.json`）。
  - 百度网盘：内置网盘目录浏览器选目录 + 文件名；按 precreate → superfile2 → create 三步上传，**同名文件直接覆盖**（`rtype=3`）。
- **导入**：选择来源：
  - 本机文件系统：内置本地文件浏览器选择 `.json` 文件。
  - 百度网盘：内置网盘浏览器选择 `.json` 文件，经 filemetas 取 dlink 下载。
  - 解析后显示预览（列表名、歌曲数，可勾选），确认后在一个事务内写入。
  - **重名处理**：与现有列表同名时另建新列表“名称 (导入)”，若仍重名依次为“名称 (导入2)”…；不修改任何已有列表。
- 本机文件访问：申请 **所有文件访问**（`MANAGE_EXTERNAL_STORAGE`）以浏览整个外部存储；未授权时引导到系统设置，若车机无设置入口则提示 adb 授权命令，并退回仅可访问应用专属目录。
- 新增网盘上传接口与文件下载能力（复用现有 Retrofit/OkHttp、令牌来源、日志脱敏）。

## Capabilities

### New Capabilities
- `playlist-backup-format`: 备份 JSON 的解析与生成规则、字段映射、容错与校验。
- `playlist-import`: 从本机或网盘选择备份文件、预览、重名另建、事务写入。
- `playlist-export`: 选择列表、写入本机文件或上传网盘（同名覆盖）。
- `local-storage-access`: 所有文件访问权限申请、降级策略、本地文件/目录浏览。

### Modified Capabilities
<!-- openspec/specs/ 为空，无既有能力需修改 -->

## Impact

- UI：`MainActivity` / `activity_main.xml`（菜单）；新增本地文件浏览页、导入预览对话框、导出选择对话框；`FileBrowserActivity` 增加“选择 JSON 文件 / 选择目录”模式（不影响原有添加歌曲模式）。
- 数据：`PlaylistManager` 新增批量导入（单事务）；无数据库结构变更、无迁移。
- 网络：`BaiduPanService` 新增 precreate / create 接口；新增 PCS superfile2 上传客户端；下载 dlink 内容。
- Manifest：新增 `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`（maxSdk 29）权限。
- 依赖：无新增第三方库（Gson、OkHttp、Retrofit 已有）。
