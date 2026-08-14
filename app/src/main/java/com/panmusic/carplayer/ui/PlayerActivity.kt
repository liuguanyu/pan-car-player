package com.panmusic.carplayer.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.panmusic.carplayer.R
import com.panmusic.carplayer.cache.PlaylistCache
import com.panmusic.carplayer.model.PlayMode
import com.panmusic.carplayer.model.PlaybackState
import com.panmusic.carplayer.service.AudioPlayerService
import com.panmusic.carplayer.service.PlaybackController
import com.panmusic.carplayer.ui.adapter.CurrentPlaylistAdapter
import com.panmusic.carplayer.viewmodel.PlayerViewModel
import com.panmusic.carplayer.viewmodel.PlayerUiState
import com.panmusic.carplayer.widget.LrcView
import com.panmusic.carplayer.widget.ParticleBackgroundView
import com.panmusic.carplayer.widget.RotatingDiscView
import com.panmusic.carplayer.widget.RotatingTextView
import com.panmusic.carplayer.widget.VisualizerView
import com.panmusic.carplayer.visualizer.VisualizerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Random
import javax.inject.Inject

/**
 * 播放器页面 — 对齐 Java 版：渐变标题栏 + 粒子 + 唱片 + 旋转文字 + 歌词 + 播单面板
 */
@AndroidEntryPoint
class PlayerActivity : BaseCarActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    @Inject lateinit var playlistCache: PlaylistCache
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var visualizerManager: VisualizerManager

    // 顶部
    private lateinit var headerLayout: View
    private lateinit var songTitleView: TextView
    private lateinit var songAlbumView: TextView
    private lateinit var formatInfoView: TextView

    // 进度
    private lateinit var currentTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var seekBar: SeekBar

    // 控制按钮
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnPlayMode: ImageButton
    private lateinit var btnVisualizer: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var btnPlaylist: ImageButton

    // 可视化 / 歌词
    private lateinit var visualizerView: VisualizerView
    private lateinit var particleBackgroundView: ParticleBackgroundView
    private lateinit var rotatingDiscView: RotatingDiscView
    private lateinit var rotatingTextView: RotatingTextView
    private lateinit var lrcView: LrcView
    private lateinit var visualizerNameView: TextView
    private lateinit var volumeBar: SeekBar

    // 播放列表面板
    private lateinit var playlistContainer: View
    private lateinit var playlistSearchInput: EditText
    private lateinit var playlistRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var playlistAdapter: CurrentPlaylistAdapter

    private var isSeeking = false
    private var isMuted = false
    private var lastVolume = 0.5f
    private var volumeManager: AudioManager? = null
    private var attachedAudioSessionId = 0
    private var pendingSessionId = 0

    private val requestRecordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingSessionId > 0) {
            visualizerManager.attachToSession(pendingSessionId)
            attachedAudioSessionId = pendingSessionId
        }
    }
    private var playlistVisible = false
    private val random = Random()
    private var lastSongFsId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        volumeManager = getSystemService(AUDIO_SERVICE) as AudioManager

        initViews()
        setupListeners()
        observeState()
        observePlaylist()

        handleIntent(intent)
        startForegroundService(Intent(this, AudioPlayerService::class.java))
    }

    private fun initViews() {
        headerLayout = findViewById(R.id.header_layout)
        songTitleView = findViewById(R.id.tv_song_title)
        songAlbumView = findViewById(R.id.tv_song_album)
        formatInfoView = findViewById(R.id.tv_format_info)

        currentTimeView = findViewById(R.id.tv_current_time)
        totalTimeView = findViewById(R.id.tv_total_time)
        seekBar = findViewById(R.id.seek_bar)

        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnNext = findViewById(R.id.btn_next)
        btnPrevious = findViewById(R.id.btn_previous)
        btnPlayMode = findViewById(R.id.btn_play_mode)
        btnVisualizer = findViewById(R.id.btn_visualizer)
        btnVolume = findViewById(R.id.btn_volume)
        btnPlaylist = findViewById(R.id.btn_playlist)

        visualizerView = findViewById(R.id.visualizer_view)
        particleBackgroundView = findViewById(R.id.particle_background)
        rotatingDiscView = findViewById(R.id.rotating_disc_view)
        rotatingTextView = findViewById(R.id.rotating_text_view)
        lrcView = findViewById(R.id.lrc_view)
        visualizerNameView = findViewById(R.id.tv_visualizer_name)
        volumeBar = findViewById(R.id.volume_bar)

        // 播放列表面板
        playlistContainer = findViewById(R.id.playlist_panel)
        playlistSearchInput = findViewById(R.id.et_playlist_search)
        playlistRecyclerView = findViewById(R.id.rv_current_playlist)
        playlistAdapter = CurrentPlaylistAdapter { song, originalPosition ->
            playerViewModel.playAt(originalPosition)
            hidePlaylist()
        }
        playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        playlistRecyclerView.adapter = playlistAdapter

        playlistContainer.visibility = View.GONE

        visualizerView.attach(visualizerManager)

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener {
            navigateBack()
        }

        val maxVolume = volumeManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 100
        val currentVolume = volumeManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 50
        volumeBar.max = maxVolume
        volumeBar.progress = currentVolume

        // 初始渐变
        applyRandomHeaderGradient()
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener { playerViewModel.togglePlayPause() }
        btnNext.setOnClickListener { playerViewModel.playNext() }
        btnPrevious.setOnClickListener { playerViewModel.playPrevious() }
        btnPlayMode.setOnClickListener { playerViewModel.cyclePlayMode() }

        btnVisualizer.setOnClickListener {
            playerViewModel.cycleVisualizer(visualizerManager.getVisualizerNames())
        }

        btnPlaylist.setOnClickListener {
            if (playlistVisible) hidePlaylist() else showPlaylist()
        }

        findViewById<ImageButton>(R.id.btn_close_playlist).setOnClickListener {
            hidePlaylist()
        }
        playlistContainer.setOnClickListener {
            hidePlaylist()
        }

        btnVolume.setOnClickListener {
            // 对齐 Java 版：使用 ExoPlayer 音量控制，不使用 AudioManager
            if (isMuted) {
                // 取消静音
                isMuted = false
                playerViewModel.setVolume(lastVolume)
                volumeBar.progress = (lastVolume * volumeBar.max).toInt()
                btnVolume.setImageResource(R.drawable.ic_volume_up)
            } else {
                // 静音
                val currentVol = playerViewModel.getVolume()
                if (currentVol > 0) {
                    lastVolume = currentVol
                }
                isMuted = true
                playerViewModel.setVolume(0f)
                volumeBar.progress = 0
                btnVolume.setImageResource(R.drawable.ic_volume_mute)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val position = (progress / 1000f * playerViewModel.uiState.value.duration).toLong()
                    playerViewModel.seekTo(position)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isSeeking = false }
        })

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val vol = if (volumeBar.max > 0) progress.toFloat() / volumeBar.max else 0f
                    playerViewModel.setVolume(vol)
                    isMuted = progress == 0
                    btnVolume.setImageResource(if (isMuted) R.drawable.ic_volume_mute else R.drawable.ic_volume_up)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        playlistSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                playlistAdapter.filter.filter(s ?: "")
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            playerViewModel.uiState.collect { state ->
                updatePlayerUi(state)
            }
        }
    }

    private fun updatePlayerUi(state: PlayerUiState) {
                // 歌曲信息
                val displayTitle = state.songTitle.ifEmpty { getString(R.string.player_unknown_song) }
                songTitleView.text = displayTitle
                rotatingTextView.setText(displayTitle)
                formatInfoView.text = state.formatInfo.displayText.ifEmpty { "" }

                // 切歌时刷新渐变标题栏 + 清空歌词（避免旧歌词残留）
                val currentFsId = state.currentSong?.fsId ?: -1
                if (currentFsId != lastSongFsId && currentFsId > 0) {
                    lastSongFsId = currentFsId
                    applyRandomHeaderGradient()
                    lrcView.reset()
                    lrcView.tag = null
                }

                // LRC 元数据显示在副标题
                val album = state.lrcInfo?.album
                val artist = state.lrcInfo?.artist
                songAlbumView.text = listOfNotNull(album, artist)
                    .joinToString(" · ").ifEmpty { "" }
                songAlbumView.visibility = if (songAlbumView.text.isNotEmpty()) View.VISIBLE else View.GONE

                // 进度
                if (!isSeeking) {
                    currentTimeView.text = state.formattedProgress
                    totalTimeView.text = state.formattedDuration
                    seekBar.max = 1000
                    seekBar.progress = if (state.duration > 0) {
                        (state.progress * 1000 / state.duration).toInt()
                    } else 0
                }

                // 歌词加载 — LrcView 始终 VISIBLE，无歌词时内部不绘制（与 Java 版一致）
                val lrcInfo = state.lrcInfo
                if (lrcInfo != null && lrcInfo.lines.isNotEmpty()) {
                    if (lrcView.tag != lrcInfo) {
                        lrcView.setLrc(lrcInfo)
                        lrcView.tag = lrcInfo
                    }
                    lrcView.updateTime(state.progress)
                } else {
                    lrcView.reset()
                    lrcView.tag = null
                }

                // 播放/暂停
                btnPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

                // 播放模式
                btnPlayMode.setImageResource(
                    when (state.playMode) {
                        PlayMode.ORDER -> R.drawable.ic_repeat
                        PlayMode.RANDOM -> R.drawable.ic_shuffle
                        PlayMode.SINGLE -> R.drawable.ic_repeat_one
                    }
                )

                // 可视化名称
                visualizerNameView.text = state.activeVisualizer

                // 可视化切换 / 音频会话绑定（default 为唱片模式，无需切换可视化器）
                if (state.activeVisualizer != VisualizerManager.MODE_DEFAULT &&
                    state.activeVisualizer != visualizerManager.getActiveName()
                ) {
                    visualizerManager.switchTo(state.activeVisualizer)
                }
                if (state.audioSessionId > 0 && state.audioSessionId != attachedAudioSessionId) {
                    // Android 6.0+ Visualizer 构造需要 RECORD_AUDIO 运行时权限
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                        visualizerManager.attachToSession(state.audioSessionId)
                        attachedAudioSessionId = state.audioSessionId
                    } else {
                        pendingSessionId = state.audioSessionId
                        requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                // 可视化模式控制：default 为唱片模式（唱片+粒子+旋转文字，无可视化覆盖层），
                // 其他模式（bars/wave/particles/sheep）只显示可视化内容
                val isDiscMode = state.activeVisualizer == VisualizerManager.MODE_DEFAULT

                if (isDiscMode) {
                    // 唱片模式：只显示装饰元素，隐藏可视化
                    visualizerView.setActive(false)
                    visualizerView.visibility = View.GONE

                    particleBackgroundView.visibility = View.VISIBLE
                    rotatingDiscView.visibility = View.VISIBLE
                    rotatingTextView.visibility = View.VISIBLE

                    // 根据播放状态控制装饰动画
                    when (state.playbackState) {
                        is PlaybackState.Playing -> {
                            rotatingDiscView.resumeRotation()
                            rotatingTextView.resumeRotation()
                            particleBackgroundView.setPlaying(true)
                        }
                        is PlaybackState.Paused -> {
                            rotatingDiscView.pauseRotation()
                            rotatingTextView.pauseRotation()
                            particleBackgroundView.setPlaying(false)
                        }
                        else -> {
                            rotatingDiscView.stopRotation()
                            rotatingTextView.stopRotation()
                            particleBackgroundView.stopAnimation()
                        }
                    }
                } else {
                    // 可视化模式：隐藏装饰元素，只显示可视化内容
                    rotatingDiscView.stopRotation()
                    rotatingTextView.stopRotation()
                    particleBackgroundView.stopAnimation()

                    particleBackgroundView.visibility = View.GONE
                    rotatingDiscView.visibility = View.GONE
                    rotatingTextView.visibility = View.GONE
                    visualizerView.visibility = View.VISIBLE

                    // 可视化 View 始终活跃
                    visualizerView.setActive(true)
                    visualizerView.setFrameRate(if (state.playbackState is PlaybackState.Playing) 60 else 15)
                }
    }

    private fun observePlaylist() {
        lifecycleScope.launch {
            playerViewModel.playlistSongs.collectLatest { songs ->
                playlistAdapter.setSongs(songs)
            }
        }
        lifecycleScope.launch {
            playerViewModel.currentIndexFlow.collectLatest { index ->
                val songs = playerViewModel.playlistSongs.value
                if (index in songs.indices) {
                    playlistAdapter.setCurrentPlayingSongId(songs[index].id)
                    if (playlistVisible) {
                        playlistRecyclerView.scrollToPosition(index)
                    }
                }
            }
        }
    }

    // ========== 播放列表面板 ==========

    private fun showPlaylist() {
        val songs = playerViewModel.playlistSongs.value
        if (songs.isEmpty()) {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        playlistAdapter.setSongs(songs)
        val currentIdx = playerViewModel.currentIndexFlow.value
        if (currentIdx in songs.indices) {
            playlistAdapter.setCurrentPlayingSongId(songs[currentIdx].id)
            playlistRecyclerView.scrollToPosition(currentIdx)
        }
        playlistSearchInput.setText("")
        playlistContainer.visibility = View.VISIBLE
        playlistVisible = true
    }

    private fun hidePlaylist() {
        playlistContainer.visibility = View.GONE
        playlistVisible = false
    }

    // ========== 渐变标题栏 ==========

    private fun applyRandomHeaderGradient() {
        val r1 = random.nextInt(80)
        val g1 = random.nextInt(80)
        val b1 = random.nextInt(120)

        val r2 = random.nextInt(100)
        val g2 = random.nextInt(60)
        val b2 = random.nextInt(140)

        val color1 = Color.rgb(r1, g1, b1)
        val color2 = Color.rgb(r2, g2, b2)

        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color1, color2)
        )
        headerLayout.background = gradient
    }

    // ========== 导航 ==========

    private fun navigateBack() {
        if (playlistVisible) {
            hidePlaylist()
            return
        }
        if (!isTaskRoot) {
            onBackPressedDispatcher.onBackPressed()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    // ========== 生命周期 ==========

    private fun handleIntent(intent: Intent) {
        val cacheKey = intent.getStringExtra("PLAYLIST_CACHE_KEY")
        val startIndex = intent.getIntExtra("START_INDEX", 0)
        val resume = intent.getBooleanExtra("EXTRA_RESUME_PLAYBACK", false)

        if (cacheKey != null) {
            val rawSongs = playlistCache.getAndRemove(cacheKey) ?: return
            // 对齐 Java 版：排序后传入播放
            val sortedSongs = rawSongs.toMutableList()
            com.panmusic.carplayer.model.Song.sortSongs(sortedSongs)
            playerViewModel.setPlaylist(sortedSongs, startIndex)
        } else if (resume) {
            Toast.makeText(this, "恢复播放中...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::visualizerView.isInitialized) {
            visualizerView.setActive(false)
            particleBackgroundView.stopAnimation()
            rotatingDiscView.stopRotation()
            rotatingTextView.stopRotation()
            try {
                visualizerManager.release()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
