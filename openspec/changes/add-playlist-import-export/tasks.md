## 1. 备份格式

- [x] 1.1 新增 `backup/PlaylistBackupCodec`：解析 Web 备份（树模型、`fs_id` 按 long 读取、去 BOM、跳过无效/目录项、列表内 fs_id 去重、空名→“未命名列表”、忽略 `recentSongs`）
- [x] 1.2 `PlaylistBackupCodec` 生成备份（`version 1.0`、UTC `export_time`、字段映射、duration 秒↔毫秒、不写 `recentSongs`、不转义中文）
- [x] 1.3 单测：解析用户提供的真实备份样本（复制到 test resources）、无效文件报错、跳过计数、去重、往返一致

## 2. 导入写库与命名

- [x] 2.1 新增 `backup/ImportNaming.uniqueName`：“名称 (导入)”/“(导入N)”，同批次也避让
- [x] 2.2 `PlaylistManager.importPlaylists`：单事务创建列表、按文件顺序插入歌曲、重算数量，返回最终名称与歌曲数
- [x] 2.3 单测：命名规则、导入顺序、重名另建且不改原列表、事务失败全回滚（Robolectric 内存库）

## 3. 网盘读写

- [x] 3.1 `CloudBackupRepository.downloadText(fsId)`：filemetas→dlink→下载（10MB 上限、UTF-8、脱敏错误）
- [x] 3.2 `CloudBackupRepository.upload`：precreate→superfile2→create，`rtype=3` 覆盖、秒传、errno 检查、可注入 OkHttpClient/基址
- [x] 3.3 上传使用不带 `RetryInterceptor` 的独立 OkHttpClient（非幂等 POST 不自动重试）
- [x] 3.4 单测：上传三步参数（path/size/block_list md5/rtype/uploadid）、秒传、各步失败、令牌脱敏；下载解析

## 4. 本机存储

- [x] 4.1 Manifest 增加 `MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`(maxSdk 29)；新增 `storage/StorageAccess`（检查、跳转设置、adb 提示、根目录选择）
- [x] 4.2 新增 `storage/LocalDirectoryLister`（目录优先+名称排序、按模式过滤、不可读报错、根目录锁定）与 `LocalBackupWriter`（临时文件+rename）
- [x] 4.3 新增 `LocalFileBrowserActivity` + 布局（pick_json / pick_dir 模式、当前路径、上一级、“选择此目录”）
- [x] 4.4 单测：目录列举过滤排序、根目录锁定、原子写入与失败清理

## 5. 网盘选择模式

- [x] 5.1 `FileBrowserActivity` 增加 `EXTRA_MODE`（add_songs 默认/pick_json/pick_dir），选择后 `setResult` 返回 fs_id/path；原添加歌曲模式行为不变

## 6. 列表页流程

- [x] 6.1 `activity_main.xml` 增加“更多”按钮；新增 `PlaylistTransferController` 处理导入/导出对话框序列
- [x] 6.2 导出：列表多选 → 目标（本机/网盘）→ 目录 → 文件名（默认 `playlists-backup-YYYY-MM-DD.json`、补 `.json`、校验）→ 本地覆盖确认 → 写入/上传 → 结果提示
- [x] 6.3 导入：来源（本机/网盘）→ 选择 json → 解析 → 预览（最终名称、歌曲数、跳过数、可勾选）→ 事务写入 → 结果提示并刷新
- [x] 6.4 权限流程：未授权时弹窗（去授权 / 仅使用应用目录 / adb 命令），返回后重新检查并继续
- [x] 6.5 单测：默认文件名与文件名校验

## 7. 验证

- [x] 7.1 全量单测 + assembleDebug + `git diff --check` + `openspec validate --strict`
- [x] 7.2 模拟器：adb 授权后从本机导入样本备份、导出到本机并对比；网盘导出（同名覆盖）再从网盘导入；重名生成“(导入)”
