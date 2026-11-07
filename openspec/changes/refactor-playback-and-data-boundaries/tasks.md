## 1. Baseline and test infrastructure

- [x] 1.1 Record current playback, playlist, authentication, and database behavior with focused regression tests.
- [x] 1.2 Add test support for Room in-memory databases and MockWebServer/network fakes.
- [x] 1.3 Define stable song identity rules and document the mapping between `id`, `fsId`, and playlist identity.

## 2. Playback state and request coordination

- [x] 2.1 Introduce immutable `PlaybackState` and `PlaybackCommand` models containing song identity, queue state, position, mode, audio session, and request ID.
- [x] 2.2 Implement a single-threaded playback coordinator with multi-subscriber state updates and unsubscribe support.
- [x] 2.3 Add request generation and song identity checks to download URL playback; invalidate pending requests on replacement and Service destruction.
- [x] 2.4 Bind seek position and autoplay intent to playback commands and remove global `pendingSeekPosition` behavior.
- [x] 2.5 Adapt MediaSession, notification, Activity, and hardware-key entry points to dispatch coordinator commands.
- [x] 2.6 Add tests for out-of-order URL responses, pause-during-prepare, service destruction, and multiple subscribers.

## 3. Lyrics and visualizer lifecycle

- [x] 3.1 Add a lyrics coordinator that associates every request/result with song ID and supports cancellation or logical invalidation.
- [x] 3.2 Change the player screen to render lyrics from playback state and reject stale results.
- [x] 3.3 Add a lifecycle-aware visualizer coordinator covering visibility, permission, audio session, capture, and release.
- [x] 3.4 Add tests for stale lyrics, screen stop, seek-while-paused, invalid audio sessions, and stale visualizer callbacks.

## 4. Playlist persistence and refresh consistency

- [x] 4.1 Convert add/delete/replace song operations and derived count updates to Room transactions.
- [x] 4.2 Add foreign-key/cleanup behavior for playlist songs and eliminate count lost-update paths.
- [x] 4.3 Implement validated cloud refresh snapshots and atomically replace local data only after successful completion.
- [x] 4.4 Treat the local database as rebuildable: bump the schema version and recreate it with destructive fallback instead of explicit migrations.
- [x] 4.5 Add concurrency, partial-cloud-failure, rollback, and count consistency tests.

## 5. Authentication and network boundaries

- [x] 5.1 Replace exposed mutable `AuthInfo` with immutable snapshots or defensive copies and a single controlled write path.
- [x] 5.2 Serialize or generation-guard device polling, token refresh, and logout; add cancellation for Handler polling.
- [x] 5.3 Preserve an existing refresh token when a refresh response omits one.
- [x] 5.4 Extract repository boundaries for playlist, download URL, lyrics, and authentication network calls with injectable executors.
- [x] 5.5 Disable production BODY logging and redact credentials from token-bearing URLs, logs, and errors.
- [x] 5.6 Add authentication race, refresh-token retention, cancellation, callback-thread, retry, and redaction tests.

## 6. Activity and Adapter boundaries

- [x] 6.1 Introduce a player state coordinator/ViewModel adapter and move recovery, progress, playlist filtering, and playback state out of `PlayerActivity`.
- [x] 6.2 Remove duplicate playback listener and media-key implementations from `MainActivity`, `SongListActivity`, and `PlayerActivity`.
- [x] 6.3 Move selection state and business decisions out of `SongAdapter`, `CurrentPlaylistAdapter`, and `FileAdapter`; adapters shall render immutable row models and return stable IDs.
- [x] 6.4 Fix `SongListActivity` initialization/binding lifecycle and prevent duplicate recovery/load operations on recreation.
- [x] 6.5 Migrate screens incrementally to the shared state source and remove direct Service getters/polling.
- [x] 6.6 Add recreation, stop/start, filtered-click, selection, and duplicate-load regression tests.

## 7. Cleanup and verification

- [x] 7.1 Split the remaining `AudioPlayerService` responsibilities into engine, queue, repository, focus, media-session, and persistence components.
- [x] 7.2 Replace manager-owned raw threads with managed executors or lifecycle-aware cancellable work.
- [x] 7.3 Run unit, instrumentation, and device playback regression tests, including rapid next/previous and offline scenarios.
- [x] 7.4 Remove deprecated direct-Service and single-listener APIs after all callers migrate.
- [x] 7.5 Validate the OpenSpec change and update implementation notes with known follow-up work.
