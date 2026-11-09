## Context

- 播放列表存于 Room（`playlists` / `songs`，`songs` 主键 `(fsId, playlistId)`，按 `addedTime` 升序排列）。`Song` 只保存 `fsId/title/path/size/addedTime/duration(ms)` 等，不保存 md5、mtime。
- Web 端备份格式见 `playlists-backup-2026-11-09.json`（`version 1.0`，`playlists[].items[]`，`duration` 为秒）。
- 网盘访问：`CloudFileRepository`（令牌每次实时读取）、`BaiduPanService`（Retrofit，基址 `https://pan.baidu.com/rest/2.0/`）、`BaiduLyricsRepository` 已实现 filemetas→dlink→下载文本。
- 上传逻辑参考 `~/devspace/pan-player-cmd/internal/api/baidu_client.go#UploadFile`：precreate（`autoinit=1`、`block_list=["md5"]`、`rtype=3`）→ `https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&partseq=0` multipart 字段 `file` → create（同参数 + `uploadid`）。`return_type=2` 为秒传。
- 车机（Android Automotive, API 33）上 `ACTION_OPEN_DOCUMENT/CREATE_DOCUMENT` 解析到 `DocumentsUIStubWithResult` 空壳，SAF 不可用，因此本机文件只能用应用内浏览器 + 文件 API。
- 用户决策：重名另建“名称 (导入)”；申请所有文件访问；`recentSongs` 不处理；网盘同名覆盖。

## Goals / Non-Goals

**Goals:**
- 与 Web 端备份格式双向兼容；导入/导出均支持本机与网盘。
- 导入全有或全无；导出本地写入原子（临时文件 + rename）。
- 纯逻辑（编解码、重命名、上传协议）可 JVM 单测。

**Non-Goals:**
- 不做最近播放、不做列表合并/覆盖导入、不做增量同步。
- 不使用 SAF（车机无 DocumentsUI）；不做分片上传（备份文件远小于 4MB 单片上限）。
- 不改数据库结构。

## Decisions

1. **编解码：`PlaylistBackupCodec`（纯 Java + Gson 树模型）**
   - 解析用 `JsonParser` 手动取字段，宽容数字/字符串类型差异（`fs_id` 可能是大整数，必须按 `long` 读取，避免 Gson 默认 Double 精度丢失：用 `JsonElement.getAsLong()`）。
   - 输出 `BackupFile { List<BackupPlaylist> playlists; int skippedItems; }`；`BackupPlaylist { name, description, List<Song-like Entry> }`。
   - 导出字段：`category=2`、`isdir=0`、`md5=""`、`local_mtime/server_mtime=add_time`（本地无原值，取歌曲加入时间秒数），`duration = ms/1000.0`，`create_time/update_time = createdTime/1000`；保持 2 空格缩进便于人读，不 HTML 转义中文。
   - 备选：Gson 绑定 POJO。放弃原因：`fs_id` 精度与容错控制不如树模型直接。

2. **导入写库：`PlaylistManager.importPlaylists(List<BackupPlaylist>)` 单事务**
   - 事务内读取全部现有列表名 → `ImportNaming.uniqueName(name, taken)` 生成“名称 (导入)” / “(导入N)” → 插入 Playlist（UUID、`createdTime = now + i` 保持导入顺序）→ 批量插入 Song（`addedTime = base + index` 保证文件顺序）→ `recountSongs`。
   - 预览时用同一 `ImportNaming` 预先计算最终名称展示；写入时在事务内重新计算，以事务内结果为准。

3. **网盘上传：`CloudBackupRepository.upload`（OkHttp 直连）**
   - precreate / create 走 `https://pan.baidu.com/rest/2.0/xpan/file?method=...&access_token=...`，表单编码；superfile2 走 multipart。使用独立的 OkHttpClient（手动设置 UA `pan.baidu.com`、相同超时），**不挂 `RetryInterceptor`**——上传 POST 非幂等，不自动重试；现有拦截器保持不变。
   - 解析 `errno`，非零即失败；所有错误信息经 `LogRedactor`。
   - 内容先序列化为 `byte[]`，计算 MD5；`rtype=3` 覆盖。
   - 可注入 OkHttpClient 与基址，用 MockWebServer 单测三步流程与错误分支。

4. **网盘下载：`CloudBackupRepository.downloadText(fsId)`**（与上传同类，避免扩大 `CloudFileRepository` 职责）
   - filemetas(dlink=1) → `appendAccessToken` → GET（UA `pan.baidu.com`），限制 10MB，UTF-8 解码（去 BOM）。

5. **选择网盘文件/目录：扩展 `FileBrowserActivity` 模式**
   - 新增 `EXTRA_MODE`：`add_songs`（默认，原行为不变）/ `pick_json` / `pick_dir`。`pick_json` 过滤为目录 + `.json`，点击文件 `setResult` 返回 `fs_id/path`；`pick_dir` 只列目录，底部按钮改为“选择此目录”返回当前路径。复用目录加载、排序、返回逻辑。
   - 备选：新建独立网盘选择页。放弃原因：重复目录加载/排序/代次逻辑。

6. **本机文件：新增 `LocalFileBrowserActivity` + `StorageAccess`**
   - `StorageAccess.hasAllFilesAccess()` = `Environment.isExternalStorageManager()`（API 30+；API 29 用 `READ/WRITE_EXTERNAL_STORAGE` 运行时权限）。
   - 授权跳转 `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（package uri），解析不到则 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`，再不行显示 adb 命令。
   - 根目录：授权时 `Environment.getExternalStorageDirectory()`；否则 `getExternalFilesDir(null)`，并锁定不能越过根。
   - 模式与网盘浏览器一致（`pick_json` / `pick_dir`）；列表逻辑 `LocalDirectoryLister`（纯 `java.io.File`）可单测。
   - 写文件：`LocalBackupWriter` 先写同目录 `.tmp` 再 `renameTo`，失败删除临时文件。

7. **流程编排：`PlaylistTransferController`（MainActivity 内的协作者）**
   - 负责对话框序列（来源/目标选择、列表多选、文件名输入、覆盖确认、预览、进度）与 `ActivityResultLauncher` 回调；后台任务走 `AppExecutors`，回调检查 `isFinishing()`。
   - MainActivity 只新增“更多”按钮与少量委托，避免再长成 God Activity。

## Risks / Trade-offs

- [车机设置中没有“所有文件访问”入口] → 显示 adb 授权命令，并提供“仅使用应用目录”降级。
- [Google Play 对 MANAGE_EXTERNAL_STORAGE 有审核限制] → 本应用为车机侧载，不上架；记录在 README。
- [导出丢失 md5/mtime 原值] → 本地未存这些字段；导出 `md5=""`，Web 端导入不依赖 md5（以 fs_id 定位）。如需保真后续可加列（需重建库）。
- [superfile2 域名/参数变更] → 与 pan-player-cmd 保持一致，错误信息完整暴露 errno 便于定位。
- [上传重试导致重复提交] → 上传请求禁用重试拦截器；失败由用户重新发起，`rtype=3` 覆盖保证幂等结果。
- [大文件导入阻塞 UI] → 解析与写库均在后台线程，显示进度对话框；下载限制 10MB。

## Migration Plan

无数据迁移。新增权限需要重新安装后授权；回滚只需移除菜单入口。

## Open Questions

- 无（重名、权限、recentSongs、网盘覆盖策略已由用户确认）。
