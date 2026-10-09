# Hunt 同步客户端

## 任务 1：定位游戏数据文件

Python 3.10+，无需第三方依赖。在项目根目录执行：

```powershell
python hunt_sync_client/steam_locator.py
```

模块依次检查：

1. Windows 注册表 HKCU `Software\Valve\Steam` 的 SteamPath/SteamExe，以及 HKLM `SOFTWARE\Valve\Steam` 的 InstallPath（包含 32/64 位注册表视图）。
2. Steam 默认库以及 `libraryfolders.vdf` 中的其他游戏库，兼容新旧格式。
3. 通过 App ID `594650` 的 appmanifest 定位实际安装目录，再查找 `user/profiles/default/attributes.xml`；缺少 manifest 时尝试常见游戏目录名。
4. 自动定位失败后，读取本模块旁的 config.json 中的 `attributes_path`。

自定义路径示例（推荐使用正斜杠，反斜杠需要在 JSON 中写成 `\\`）：

```json
{
  "attributes_path": "D:/SteamLibrary/steamapps/common/Hunt Showdown/user/profiles/default/attributes.xml"
}
```

相对路径按 config.json 所在目录解析；也支持 `%USERPROFILE%` 等环境变量。可通过 `--config C:/path/config.json` 使用其他配置文件。只返回实际存在的文件，找不到时输出原因并以退出码 1 结束，不创建游戏文件或修改注册表。

其他客户端模块可以直接调用：

```python
from steam_locator import LocatorError, locate_attributes_file

try:
    attributes_path = locate_attributes_file()
except LocatorError as error:
    print(error)
```

运行测试：`python -m unittest discover -s hunt_sync_client/tests -v`。

## 任务 2：单局解析与上传包

`match_parser.py` 解析单局，输出 schema_version=2、match_hash、metrics、players 和 warnings。metrics 包含当局击杀/击倒、助攻、赏金、MMR 及变动、地图和撤离状态；players 保留昵称、MMR、击杀/击倒与交互计数、赏金和队友标志。玩家记录不包含 SteamID 或原始 XML 属性。缺失数据为 null，不将交互次数当成该玩家的总击杀数。

两级玩家编号 `(slot, member_index)` 分别保留，避免合并 `_0_0` 与 `_0_1`。resource/attributes.xml 样本解析出三名玩家。

```powershell
python hunt_sync_client/match_parser.py hunt_sync_client/resource/attributes.xml --output hunt_sync_client/resource/parsed_match.json
```

match_hash 保持原有算法：对完整 MissionBag 键值排序后生成带版本前缀的 SHA-256；原始属性只在内存中用于哈希，不再输出。XML 顺序、解析时间、文件路径以及外层上传者账号不影响 hash。同局数据仍在变化时 hash 会改变；两局所有数据完全一致且没有局号/时间字段时无法区分，后续监听器应等待快照稳定。

## Steam 上传者账号

steam_user.py 从注册表确定 Steam 安装目录，再读取 config/loginusers.vdf，查找 MostRecent=1 的用户节点。仅接受唯一的 17 位数字 ID；文件缺失、损坏、没有最近账号或存在歧义时明确报错，不猜测账号。可用 --steam-dir 指定安装目录（不是游戏库目录）。不输出 AccountName、登录凭据或其他用户配置。

upload_packet.py 将账号放在对局包外层：

```json
{
  "uploader_steam_id": "76561198000000001",
  "match": {
    "schema_version": 2,
    "match_hash": "SHA-256指纹",
    "metrics": {},
    "players": [],
    "warnings": []
  }
}
```

自动定位账号与游戏文件：

```powershell
python hunt_sync_client/upload_packet.py --output hunt_sync_client/upload.json
```

指定样本和 Steam 安装目录：

```powershell
python hunt_sync_client/upload_packet.py hunt_sync_client/resource/attributes.xml --steam-dir "C:/Program Files (x86)/Steam" --output hunt_sync_client/upload.json
```

MostRecent 表示 Steam 最近使用的账号，退出 Steam 后标记也可能保留，不能证明账号当前在线，也不能证明旧 XML 属于该账号。它不是认证凭据；正式上传时需与 JWT 身份核对。当前模块只生成本地数据包，不联网，现有后端平铺 MatchSyncDTO 尚不直接接受此嵌套包，后续上传适配模块需负责转换及账号一致性检查。

## 上传客户端（sync_client.py）

已扫描当前后端 MatchController、MatchSyncDTO、MatchService 和 application.yml：上传为 `POST /api/v1/matches/sync`，未配置 context-path 或额外 servlet 前缀。上传适配层直接发送平铺 DTO；上面的 upload_packet.py 继续用于查看本地封装数据，不直接发送其嵌套结构。

### 安装与运行

```powershell
python -m pip install -r hunt_sync_client/requirements.txt
$env:HUNT_SYNC_TOKEN = "Steam登录后后端签发的JWT"
python hunt_sync_client/sync_client.py
```

Token 是登录结果，不是 JWT_SECRET。过期后更新环境变量并重新启动客户端。可使用 `--config 路径` 指定配置；`--once` 立即上传当前文件一次并退出。默认监听模式不上传启动前遗留对局；设置 upload_on_start=true 才会处理初始文件。

config.json：attributes_path 为自动定位失败后的路径；steam_directory 可指定 Steam 安装目录；backend_base_url 默认 http://localhost:8080；sync_path/session_path 分别为上传与登录态接口；token_env 指定保存 JWT 的环境变量名；request_timeout_seconds 默认 15；state_file 保存成功 ID。配置中的相对文件路径相对于配置文件目录。若部署增加 context-path，例如 /hunt，则设置 backend_base_url 为 http://host:8080/hunt，接口路径保留 /api/v1/...。

### DTO 转换

| 后端字段 | 来源/规则 |
| --- | --- |
| match_id | m_ + uploader_steam_id + 下划线 + match_hash；稳定且不超过 128 字符 |
| match_time | XML 文件最后修改时间转 UTC ISO-8601；样本没有真实对局时间，不使用本次重试时间 |
| map_name | MissionBagMapName |
| game_mode | MissionBagIsQuickPlay=true 为 Soul Survivor，否则 Bounty Hunt |
| extracted | MissionBagIsExtract |
| deaths | MissionBagIsHunterDead=true 为 1，否则 0 |
| kills/team_kills/assists/team_wipes/bounty_extracted | 对应单局 metrics 整数 |
| mmr/mmr_change | 单局 MMR 与变动；未知为 null |
| encountered_players | players 列表，保留昵称/MMR/击杀击倒交互等字段 |

由于后端没有单独的单局 downs 字段，它保留在本地解析结果中，不添加到严格 DTO 中；玩家击倒数据在 encountered_players 中上传。缺少必需计数/布尔字段时拒绝上报，不伪造 0。

uploader_steam_id 用于先请求 GET /api/v1/auth/session，并与响应 data.steam_id 比对。后端认证有效且账号一致才 POST；uploader_steam_id 不放入请求体，后端继续由 JWT 确定归属。每次处理重新读取最近 Steam 账号；它仍不能证明磁盘上的旧对局属于该账号。

### 监听、容错与日志

- watchdog 监听目标父目录的修改、创建与重命名替换事件；每次事件重置 2 秒防抖计时，单工作线程处理，上传期间的新事件会保留待处理。
- 只读二进制读取文件，读取前后核对大小、mtime 和文件标识；占用、写入变化或 XML 未完成时，每隔 0.5 秒重试，首次读取后最多重试 5 次。
- 请求超时/连接异常、408、429、5xx 最多额外重试 3 次，间隔 1/2/4 秒，总计最多 4 次请求；401/403/400/409 等直接报错，拒绝跟随重定向。重试复用相同载荷和 match_id。
- 日志包含请求地址、尝试次数、JSON、HTTP 状态码与返回体，不输出 Authorization/JWT。日志中的对局昵称等数据仍属于玩家资料。
- HTTP 2xx 且 JSON code=0 后，以原子替换方式持久化成功 ID；防重按后端地址和上传者隔离，跨重启有效。若响应丢失或本地状态写入失败，后续重传由后端幂等保护。
- 重试耗尽后记录错误，不标记成功；当前没有持久化失败队列。可在文件未被新局覆盖前使用 --once 再次上传；游戏覆盖失败对局后无法从当前 XML 恢复它。

验证：`python -m unittest discover -s hunt_sync_client/tests -v`。包含读取实际 Java DTO 的字段集合对齐测试、实际 XML 解析、模拟 HTTP 重试、身份校验和防抖测试；没有使用真实账号进行上报。

## 无窗口系统托盘与自启

```powershell
python -m pip install -r hunt_sync_client/requirements.txt
$env:HUNT_SYNC_TOKEN = "登录返回的JWT"
pythonw hunt_sync_client/tray_client.py
```

使用 pythonw.exe 启动不会出现控制台窗口；调试也可使用 `python hunt_sync_client/tray_client.py`。默认读取脚本旁的 config.json，支持 `--config 完整路径`。

托盘主线程运行 pystray 事件循环，后台工作线程完成初始化、解析与网络请求；watchdog 负责文件事件监测。使用 Pillow 生成 64×64 图标，绿色表示监听正常，橙色表示初始化/处理中或最近处理失败。右键菜单包含：

- 状态：监听中、解析/上传中或等待文件/配置。
- 上次上传：本次运行中最后一次实际上传成功的时间；防重跳过不会刷新时间。
- 后端：初始为尚未验证，上传成功后显示“上次上传连接正常”，不是实时在线探测。
- 手动上传当前对局：加入后台队列，仍保留 2 秒防抖、身份检查与防重，不强制重复写入已成功对局。
- 开机自启：切换 HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run 中的 Hunt1896SyncClient 值，使用带正确引号的 pythonw.exe、脚本和配置绝对路径。无需管理员权限，仅在当前用户登录 Windows 时启动。默认不主动开启，也不修改其他自启项。
- 退出：停止接收事件和后续网络重试，等待当前读取/HTTP 请求结束，关闭 watchdog 与 requests Session，回收工作线程。当前 HTTP 请求不能被强制中断，退出可能需要等待请求超时。

Steam/游戏盘尚未就绪时，托盘保留并每 5 秒重新定位；无需先启动游戏。正常监听后更改配置文件需重启客户端。

无窗口日志写入配置目录下 logs/tray.log，单文件最多约 2 MB，保留 3 份轮转备份。故障详情（含 HTTP 返回体）可在这里查看。

自启进程不会继承上一次 PowerShell 中临时设置的 `$env:HUNT_SYNC_TOKEN`。若使用自启，需要在 Windows 当前用户环境变量中设置同名 JWT，注销再登录后生效；Token 过期时更新并重启客户端。自启注册表命令不包含 JWT。本阶段未实现自动登录或 Token 刷新。

托盘、注册表开关及资源释放使用模拟测试验证，未替用户开启真实自启项。

## 单文件打包与分发

在 Windows 上安装 Python 3.10+（建议使用与已验证环境一致的 Python 3.14 x64），在项目根目录执行：

```powershell
.\hunt_sync_client\build.bat
```

脚本创建独立 `.venv-build`，安装 requirements.txt 和 requirements-build.txt 中锁定的运行/构建依赖，运行测试，生成 ICO/PNG 图标，再执行 build.spec。失败时返回非零退出码并停止，不静默跳过测试。首次构建需要网络下载依赖。

产物为 `hunt_sync_client/dist/hunt_tracker.exe`，单文件、无控制台、无需目标用户安装 Python。build.spec 的 console=False 与单个 EXE 构建配置实现静默托盘运行；编译架构跟随构建用 Python。本项目需在当前 Spring Boot 仓库内构建，因为契约测试会读取 Java DTO。

分发仅需复制 hunt_tracker.exe。默认配置来自 distribution/config.json，静态资源来自 assets/；开发用 config.json、resource 中的游戏样本、JWT、日志和状态均不打入程序。发布到远程服务前修改 distribution/config.json 的 backend_base_url 再构建；默认 localhost 仅用于本机后端。

首次运行将内置配置复制到 `%LOCALAPPDATA%\HuntTracker\config.json`，用户修改后重启即可。日志在同目录 logs/tray.log，防重记录为 sync_state.json。更新 exe 不覆盖已有配置。可使用 `hunt_tracker.exe --config "D:\MyConfig\config.json"` 显式指定配置；该文件需事先存在且目录可写。

通过 `Local\Hunt1896Tracker.Tray.v1` Windows 命名 Mutex 限制同一 Windows 会话只运行一个托盘实例，重复启动静默退出；互斥句柄保持到后台线程结束，正常退出/进程崩溃后 Windows 自动释放。源码托盘与打包版共用此锁。自启菜单在打包模式下注册真实 exe 的绝对路径，而不是临时解压路径；移动 exe 后应重新切换自启以更新路径。

免安装不等于免配置：首次使用仍需设置后端地址、必要时指定 XML/Steam 路径，并提供有效 HUNT_SYNC_TOKEN。当前 exe 未做代码签名，不包含安装器和自动更新功能。
