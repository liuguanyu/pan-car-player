# 百度网盘车载音乐播放器

一款基于百度网盘的Android车载音乐播放器应用，专为Android 9+车载系统优化设计。

## 项目概述

本项目是一个轻量级车载音乐播放器，允许用户通过百度网盘账号登录，浏览网盘中的音频文件，创建播放列表，并在车载环境下流畅播放音乐。项目复用了桌面端（Electron）和TV端（Android TV）的成熟技术方案，针对车载场景进行了适配和优化。

## 核心功能

### 1. 用户认证
- **OAuth设备码登录流程**
  - 展示二维码供用户扫描
  - 自动轮询获取访问令牌
  - 支持令牌刷新机制
  - 安全的本地令牌存储

### 2. 界面架构（全新设计）
- **播放列表管理页**（MainActivity）
  - 矩阵网格布局展示所有播放列表
  - 支持创建、重命名、删除播放列表
  - 长按显示更多操作
  
- **歌曲列表页**（SongListActivity）
  - 显示选定播放列表中的所有歌曲
  - 支持播放全部、随机播放
  - 添加歌曲到播放列表
  
- **播放页面**（PlayerActivity）
  - 全屏播放界面
  - 实时歌词显示（LRC格式）
  - 播放控制（上一曲/下一曲/播放/暂停）
  - 播放模式切换（顺序/随机/单曲循环）
  - 音量调节
  - 进度条拖动

### 3. 文件浏览器
- 浏览百度网盘目录结构
- 支持音频文件过滤（MP3、FLAC、WAV、AAC、M4A、OGG、WMA）
- 多选文件添加到播放列表
- 文件大小显示

### 4. 音频播放
- **ExoPlayer核心引擎**
  - 支持多种音频格式
  - 自动音频焦点管理
  - 后台播放支持
  
- **播放控制**
  - 播放/暂停
  - 上一曲/下一曲
  - 进度拖动
  - 音量调节
  
- **播放模式**
  - 顺序播放
  - 随机播放
  - 单曲循环

### 5. 歌词功能
- LRC歌词解析
- 同步歌词显示
- 自动滚动跟随
- 当前歌词高亮

### 6. 数据持久化
- **Room数据库**
  - Playlist实体：播放列表信息
  - Song实体：歌曲信息
  - 自动同步歌曲数量

## 技术栈

### 核心框架
- **语言**: Java 8
- **最低SDK**: Android 9 (API 28)
- **目标SDK**: Android 13 (API 33)
- **架构**: MVVM-like
- **媒体会话**: MediaSessionCompat（支持蓝牙控制、方向盘按键）

### 主要依赖

#### UI框架
- Material Components 1.9.0
- CardView 1.0.0
- RecyclerView 1.3.0

#### 音频播放
- ExoPlayer 2.18.7

#### 网络请求
- Retrofit 2.9.0
- OkHttp 4.11.0
- Gson 2.10.1

#### 数据存储
- Room 2.5.0

#### 二维码生成
- ZXing Core 3.5.1

#### 媒体会话
- androidx.media:media 1.6.0

### 车载优化配置
- **网络超时**: 45秒（考虑车载网络不稳定）
- **重试机制**: 最多3次重试
- **轮询间隔**: 6秒（设备码认证）
- **UI尺寸**: 大字体（20-36sp）、大按钮（60-80dp）
- **配色方案**: 深色主题，高对比度

## 项目结构

```
app/src/main/java/com/baidu/carplayer/
├── adapter/              # RecyclerView适配器
│   ├── FileAdapter.java          # 文件列表适配器
│   ├── PlaylistAdapter.java      # 播放列表适配器（线性）
│   ├── PlaylistGridAdapter.java  # 播放列表适配器（网格）
│   └── SongAdapter.java           # 歌曲列表适配器
├── auth/                 # 认证模块
│   └── BaiduAuthService.java     # OAuth认证服务
├── config/               # 配置
│   └── BaiduConfig.java          # API配置
├── database/             # 数据库
│   ├── AppDatabase.java          # Room数据库
│   ├── DatabaseManager.java      # 数据库管理器
│   ├── PlaylistDao.java          # 播放列表DAO
│   └── SongDao.java              # 歌曲DAO
├── manager/              # 业务逻辑管理器
│   ├── LyricsManager.java        # 歌词管理器（自动加载LRC）
│   └── PlaylistManager.java     # 播放列表管理器
├── model/                # 数据模型
│   ├── AuthInfo.java             # 认证信息
│   ├── DeviceCodeResponse.java   # 设备码响应
│   ├── FileItem.java             # 文件项
│   ├── Playlist.java             # 播放列表
│   ├── Song.java                 # 歌曲
│   └── TokenResponse.java        # 令牌响应
├── network/              # 网络层
│   ├── ApiConstants.java         # API常量
│   ├── BaiduPanService.java      # 百度网盘API接口
│   ├── RetrofitClient.java       # Retrofit客户端
│   └── RetryInterceptor.java     # 重试拦截器
├── service/              # 服务
│   └── AudioPlayerService.java   # 音频播放服务
├── utils/                # 工具类
│   └── LrcParser.java            # LRC歌词解析器
├── widget/               # 自定义控件
│   └── LrcView.java              # 歌词显示视图
├── CarPlayerApplication.java     # Application类
├── FileBrowserActivity.java      # 文件浏览器
├── LoginActivity.java            # 登录页面
├── MainActivity.java             # 播放列表管理页
├── PlayerActivity.java           # 播放页面
├── SongListActivity.java         # 歌曲列表页
└── SplashActivity.java           # 启动页
```

## 关键特性

### 1. 网络层优化
```java
// 车载环境网络配置
- 连接超时: 45秒
- 读取超时: 45秒
- 写入超时: 45秒
- 最大重试次数: 3次
- 轮询间隔: 6秒
```

### 2. 播放模式
```java
public enum PlayMode {
    ORDER,   // 顺序播放
    RANDOM,  // 随机播放
    SINGLE   // 单曲循环
}
```

### 3. 音频焦点管理
- 自动请求音频焦点
- 焦点丢失时暂停播放
- 焦点恢复时继续播放
- 短暂焦点丢失时降低音量

### 4. 后台播放
- Foreground Service确保持续播放
- 通知栏播放控制
- **MediaSession支持** - 系统级媒体会话，支持蓝牙控制、方向盘按键等

### 5. 歌词自动加载
- 自动搜索同名LRC文件
- 从百度网盘下载歌词
- 异步加载不阻塞播放
- 找不到歌词时友好提示

## 界面设计

### 主题色
- 主色调: #2196F3 (蓝色)
- 强调色: #FF9800 (橙色)
- 背景色: #121212 (深灰)
- 表面色: #1E1E1E (浅灰)

### 车载优化尺寸
- 文字大小: 20-36sp
- 按钮高度: 60dp
- 图标按钮: 64dp
- 控制按钮: 80dp
- 侧边栏宽度: 240dp

## 使用说明

### 1. 首次启动
1. 启动应用进入登录页
2. 使用手机百度网盘APP扫描二维码
3. 授权后自动跳转到播放列表管理页

### 2. 创建播放列表
1. 点击右上角"+"按钮
2. 输入播放列表名称
3. 点击"创建"

### 3. 添加歌曲
1. 点击播放列表进入歌曲列表页
2. 点击"添加歌曲"按钮
3. 浏览百度网盘文件
4. 选择音频文件
5. 点击"添加"

### 4. 播放音乐
1. 在歌曲列表页点击歌曲
2. 进入播放页面
3. 使用播放控制按钮控制播放

## 待实现功能

### 媒体会话控制（可选）
- 蓝牙控制支持
- 方向盘按键控制
- Android Auto集成

### 其他优化
- 歌曲缓存机制
- 播放历史记录
- 收藏功能
- 搜索功能
- 歌词文件自动加载

## 开发环境

- Android Studio 2021.1+
- Gradle 7.0+
- JDK 8+

## 构建说明

```bash
# 克隆项目
git clone <repository-url>

# 进入项目目录
cd baidu-car-player

# 构建项目
./gradlew build

# 安装到设备
./gradlew installDebug
```

## 注意事项

1. **百度网盘API密钥**
   - 需要在`BaiduConfig.java`中配置您自己的API密钥
   - 申请地址: https://pan.baidu.com/union/doc/

2. **网络权限**
   - 确保设备有网络连接
   - 需要INTERNET权限

3. **存储权限**
   - 需要READ_EXTERNAL_STORAGE权限（用于缓存）
   - 需要WRITE_EXTERNAL_STORAGE权限（用于缓存）

4. **车载系统兼容性**
   - 建议在Android 9+系统上运行
   - 横屏显示最佳体验
   - 支持1024x600及以上分辨率

## License

本项目仅供学习和研究使用。

## 致谢

- 百度网盘开放平台
- ExoPlayer团队
- Material Design团队