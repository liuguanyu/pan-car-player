## Context

播放器当前由 `AudioPlayerService`、`PlayerActivity` 和多个 Manager 共同维护可变状态。播放 URL、歌词、认证和数据库操作均存在异步回调未绑定身份、缺少取消或缺少事务的问题。设计必须支持渐进式迁移，保持现有 Java/Android UI 功能和百度网盘协议兼容。

## Goals / Non-Goals

**Goals:**

- 建立单一、可观察且可测试的播放状态源。
- 使播放、歌词、认证和数据刷新请求具备身份、取消和生命周期边界。
- 保证播放列表更新的事务性和数据库升级安全。
- 降低 Activity、Service、Adapter 和 Manager 的职责耦合。

**Non-Goals:**

- 本变更不重做 UI 视觉设计。
- 本变更不替换 ExoPlayer、Room、Retrofit 或百度网盘 API。
- 本变更不一次性完成所有历史类的重命名；允许通过适配层渐进迁移。

## Decisions

### 1. 以 PlaybackState 和 PlaybackCommand 作为播放边界

所有 Activity、MediaSession、通知和硬件按键均转换为命令，命令由单一播放协调器串行处理。状态包含 `songId`、队列、索引、播放状态、位置、时长、播放模式、audioSessionId 和 requestId。Service 只作为 Android 生命周期/媒体入口适配层。

选择该方案而不是继续增加 Service getter/listener，是因为裸字段和单 listener 无法表达请求代次，也无法支持多订阅。

### 2. 异步资源请求使用 requestId + songId 双重校验

播放地址和歌词请求在发起时创建 requestId，并关联歌曲稳定 ID。回调只有在 requestId 仍为当前请求、歌曲仍为当前歌曲且组件未销毁时才能提交结果。新请求取消或失效旧请求。

### 3. 使用多订阅状态接口

状态协调器提供 subscribe/unsubscribe 或等价的 LiveData/Flow 适配，不向 Activity 暴露具体 Service 实例。迁移期间保留兼容 API，但兼容 API 内部转发到新状态源。

### 4. Repository 与 Coordinator 分离

下载地址、认证、歌词、播放列表数据库分别通过可注入 Repository 访问；Coordinator 负责顺序、取消和状态转换。网络线程和回调线程契约统一，测试可注入 Executor。

### 5. Room 事务和快照替换

歌曲增删及计数更新通过事务完成。云端刷新先生成并验证完整快照，成功后事务性替换；本地数据库视为可重建数据，schema 升级时使用 destructive fallback 重建，不维护显式 Migration。

### 6. 分阶段迁移

先增加状态/Repository/事务测试和适配层，再迁移 Service 的播放路径，最后迁移 Activity/Adapter 和删除旧接口。每阶段保持可编译、可回滚。

### Song identity

- `Song.fsId` (Baidu Pan file id) is the only song identity used by playback, lyrics, selection, row models, intents and persisted resume state. `Song.getId()` is an alias for `fsId`; there is no separate surrogate id.
- A database row is identified by `(fsId, playlistId)`: the same cloud file may appear in several playlists but at most once per playlist. Re-adding updates the row (`@Update`), never `REPLACE`, so foreign-key children are not cascaded.
- Playlists are identified by their string `id`; `songCount` is derived and recomputed after every bulk change.
- List positions are never identities. They are only a fallback when the `fsId` cannot be found after reload or re-sort.

## Risks / Trade-offs

- [迁移期间新旧播放路径并存] → 用单一协调器作为唯一写入口，旧 API 只做转发，并增加重复播放监控。
- [网络请求取消能力不一致] → requestId 失效作为逻辑取消兜底，Retrofit/Handler/Executor 同时实现物理取消。
- [状态模型改造影响 UI 时序] → 先保留现有 UI 回调适配器，增加竞态和生命周期回归测试。
- [升级后本地播放列表被清空] → 已接受：播放列表可从网盘重新添加；需要保留数据时再引入显式 Migration。
- [状态更新频率增加] → 进度更新采用节流/固定采样间隔，状态快照只发布必要字段。

## Migration Plan

1. 建立 capability 中定义的接口、状态模型和测试基础设施。
2. 迁移播放请求校验、歌词请求校验和 Service 销毁失效逻辑。
3. 将数据库操作改为事务；schema 升级时重建本地数据库，验证事务失败回滚。
4. 迁移 Activity、Adapter、Visualizer 到状态订阅，移除轮询和单 listener。
5. 禁用旧入口，运行完整测试和真实设备回归；发现问题时通过兼容适配层回滚到旧 UI 接入。

## Open Questions（实施后结论）

- LiveData 与自定义 listener：采用纯 Java 的 `PlaybackCoordinator`（多订阅、主线程投递、关闭后丢弃），不依赖 LiveData；页面在 onStart/onStop 订阅与退订。
- ViewModel：本次未引入，使用 Java Coordinator 与不可变状态控制改动范围；需要时可在页面层包一层 ViewModel 而不影响 Service。
- 云端部分失败：一律保留旧快照。只有完整且非空的扫描结果才替换本地数据；扫描为空时提示“网盘中未找到歌曲，已保留原列表”。

## Implementation Notes

### 组件边界

- `service/AudioPlayerService`：只做组装与命令分发。公开接口仅剩 `dispatch`、`dispatchCommand`、`resumeSavedSession`、`startPlaylist`、`getPlaybackSnapshot`、`subscribePlaybackState`/`unsubscribePlaybackState`。
- `player/PlaybackQueue`（队列与顺序/随机/单曲模式）、`player/PlaybackPersistence`（恢复状态）、`player/FocusPolicy`（焦点决策）、`player/MediaSessionBridge`（系统媒体会话）、`player/PlaybackNotifier`（前台通知）、`player/PlayerEngine`（ExoPlayer 构建与诊断）、`repository/DownloadUrlRepository`（可取消的下载地址解析）。
- 请求代次：`playback/PlaybackRequestTracker` 为每次选歌分配 generation，seek 与 autoplay 意图绑定在同一请求上；过期网络回调被丢弃，旧请求被物理取消。
- 页面：只通过 `PlaybackCommand` 控制，只从 `PlaybackState` 渲染；`LyricsSession`、`SeekGesture`、`VolumeState`、`CaptureGate`、`LoadGate` 等纯逻辑类可单测。
- 数据：fsId 是唯一歌曲身份；批量写入走单事务；禁止对有外键子表的行使用 `REPLACE`；schema 变化时重建本地库。
- 鉴权：`AuthStore` 是唯一写入者；轮询结果由 `TokenPollResult` 判定；页面不缓存 token，Repository 每次请求经 `TokenProvider` 读取。

### 设备回归中发现并修复的问题

- `RetryInterceptor` 会对已取消的请求继续重试并在退避中 `sleep`，快速切歌时占满 OkHttp 每 host 的并发名额，导致最终歌曲约 12 秒后才开始加载。现在取消的请求不再重试，退避按 100ms 分片检查取消。修复后最终请求约 0.4 秒返回。
- 切歌时旧歌会一直播到新下载地址返回为止。弱网或断网时这段时间很长，界面已显示新歌、扬声器仍在播旧歌，看起来像“切歌无效、顺序错乱”。现在选歌后立即停止并清空旧媒体，进度归零，播放意图保留在新请求上。
- 歌曲列表把列表位置当作歌曲 ID 传给播放器，点击几乎总是播放第一首；现在传 fsId，位置仅作兜底。

### 验证记录

- 单元测试 150 个全部通过（JVM + Robolectric，含内存 Room 与假网盘服务）。
- 模拟器（Android Automotive，user 10）：冷启动恢复到上次歌曲；点击指定歌曲播放正确；媒体键播放/暂停/上一曲/下一曲；连续 10 次下一曲 + 3 次上一曲停在预期歌曲且保留播放意图；通知标题与按钮正确；飞行模式下切歌报错不崩溃，恢复网络后可继续播放。
- 未编写仪器测试（`androidTest` 目录不存在），设备场景以手动 adb 回归代替。

## Known Follow-up Work

- 增加 `androidTest` 仪器测试，覆盖 Service 绑定、媒体会话按键与 Activity 重建。
- 离线时下载地址请求最长要等 45s 超时加 3 次重试；可考虑在无网络时快速失败，并在 UI 中显示明确的离线提示。
- `AudioPlayerService` 中仍保留大量排障日志（音频设备、格式检测等），可迁移到 `PlayerEngine` 或按 BuildConfig 关闭。
- `buildPlaybackState` 错误分支的空指针修复尚无专门测试。
- 已决定不实施：打开已删除播放列表时自动退出、按数据库校验恢复队列（低频场景）。
