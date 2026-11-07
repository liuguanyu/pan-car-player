## Why

当前播放器的播放、网络、认证、持久化和 UI 生命周期被集中在多个 God Component 中，异步请求缺少请求身份和取消边界，已经产生旧歌曲覆盖新歌曲、歌词串歌、Service 销毁后回调、监听器互相覆盖及数据库数据不一致的风险。需要先建立可验证的状态与职责边界，再进行渐进式拆分，避免继续在现有耦合结构上叠加功能。

## What Changes

- 建立统一的播放状态与命令边界，保证所有播放请求可识别、可取消且只有最新请求能够提交。
- 将播放引擎、队列、下载地址、音频焦点、媒体控制和状态持久化从 `AudioPlayerService` 中分离。
- 将歌词加载绑定到歌曲身份，并统一播放进度、歌词和可视化状态的订阅方式。
- 将 Activity 降级为生命周期、导航和 UI 渲染层，移除重复的播放控制和轮询逻辑。
- 将播放列表、认证和网络操作改为可取消、可测试、事务化的 Repository/Coordinator 边界。
- 修复数据库迁移、播放列表计数、云端刷新、Token 刷新和日志脱敏风险。
- 补充播放竞态、认证、Room 事务/迁移和网络错误的自动化测试。

## Capabilities

### New Capabilities

- `playback-state-coordination`: 统一播放命令、状态快照、请求代次、生命周期和多订阅行为。
- `lyrics-session-safety`: 歌词请求的歌曲身份绑定、取消和 UI 状态同步。
- `playlist-data-consistency`: 播放列表事务、刷新快照、计数和数据库迁移保证。
- `auth-network-lifecycle`: 认证状态并发控制、请求取消、Token 安全和网络调用边界。
- `ui-component-boundaries`: Activity、Adapter、Visualizer 与业务状态的职责边界。

### Modified Capabilities

无。当前仓库没有已有 OpenSpec capability 需要修改。

## Impact

- 主要影响 `AudioPlayerService`、`PlayerActivity`、`MainActivity`、`SongListActivity`、`FileBrowserActivity`。
- 影响 `PlaylistManager`、`LyricsManager`、`BaiduAuthService`、Room DAO/Database、Retrofit 网络层和 Adapter。
- 需要增加 Repository/Coordinator/状态模型及测试基础设施，可能调整数据库迁移和依赖配置。
- 对现有 UI 和用户功能保持兼容；内部 Service 直接暴露给 Activity 的接口将逐步收敛。
