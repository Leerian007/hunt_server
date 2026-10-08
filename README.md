# Hunt Server

《猎杀：对决 1896》战绩助手后端，基于 JDK 17、Spring Boot 3、JPA、PostgreSQL 和 Redis。

## 本地启动

1. 准备 PostgreSQL 数据库 `hunt_server` 和 Redis。
2. 在空数据库执行 `psql -U postgres -d hunt_server -v ON_ERROR_STOP=1 -f src/main/resources/db/schema.sql`。
3. 配置环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；Redis 可配置 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。默认地址见 application.yml。
4. 设置 `JWT_SECRET`（至少 32 字节随机密钥的 Base64 编码；留空或过短会拒绝启动）。`JWT_EXPIRATION` 默认为 `7d`，支持 `12h`、`30m` 等 Duration 格式。
5. 执行 `mvn spring-boot:run`，或 `mvn clean verify` 后执行 `java -jar target/hunt_server-1.0-SNAPSHOT.jar`。

启动时 Hibernate 校验表结构，不自动创建或修改表。DDL 为首次初始化脚本，后续升级应使用独立迁移脚本。

## 数据约定

- Steam ID 使用字符串，避免 JavaScript 数字精度损失；对局引用用户 Steam ID，写入战绩前需创建用户。
- `match_id` 按当前需求全局唯一。若未来同局保存多个用户视角，应调整为 `(match_id, steam_id)` 唯一键。
- 时间使用 Java Instant 和 PostgreSQL TIMESTAMPTZ。用户创建和更新时间由 JPA 生命周期回调维护；直接 SQL 更新需显式设置 updated_at。
- 未知 MMR、星级及 MMR 变化保存为 NULL，与实际的 0 区分。bounty_extracted 为非负赏金数量，mmr_change 可以为负数。
- encountered_players 为 JSONB 对象数组，默认 []。契约未定义玩家对象字段，实体暂用 List<Map<String, Object>> 保留扩展能力。
- Repository 提供全部战绩、撤离状态、死亡次数过滤，并按对局时间和 ID 倒序分页。API 的 page=1 在后续 Controller 层转换为 PageRequest.of(page - 1, size)。
- 历史查询的 died 按 deaths > 0 筛选。当前 Python 客户端将最终阵亡标志映射为 deaths=1，否则为 0；后续若上报复活前的死亡次数，死亡和撤离筛选可重叠。
- Result<T> 成功业务码为 0，失败业务码为非零；固定包含 code、message、data。HTTP 状态码由后续 Controller 独立设置。

## Steam 认证与 JWT

- `GET /api/v1/auth/steam/login-url?platform=web` 返回 `Result`，登录链接位于 `data.url`；platform 支持 web（默认）和 mobile。
- 此接口写入有效期 10 分钟的 Secure、HttpOnly、SameSite=Lax Cookie，并在 Redis 保存一次性状态。Web 端使用 `fetch(url, { credentials: 'include' })`，再跳转到返回的 Steam 链接。仅允许 `web.return-url` 所在源的凭据式跨域请求。
- 移动端必须让登录发起请求和 Steam 回调使用同一浏览器 Cookie 容器；不能在原生 HTTP 客户端获取链接后直接切换到不共享 Cookie 的系统浏览器。登录期间只保留最新一次登录 Cookie。
- Steam 回调会向官方固定端点 POST `openid.mode=check_authentication`，检查签名字段、return_to、身份 URL 和 nonce 时效，再原子消费 Redis 状态与 nonce；校验成功后向配置的 web/mobile 地址返回 302，携带 token 和 steam_id。
- 登录与回调必须通过 HTTPS 访问。部署时配置 `STEAM_BASE_DOMAIN` 为外部可访问的 API 域名；默认值为 `https://api.hunt1896.app`。`WEB_RETURN_URL` 与 `MOBILE_DEEP_LINK` 可覆盖默认回跳地址。
- `GET /api/v1/auth/session` 携带 `Authorization: Bearer <token>`，返回 `data.authenticated`、`data.steam_id`、`data.expires_at`。JWT 使用 HS256，subject 为 Steam ID；无 Token、无效签名或过期返回 HTTP 401 与统一 Result。
- 仅两个 Steam 登录端点匿名开放，其余接口均要求认证。会话无服务器端续期或撤销列表；更换 JWT_SECRET 会使旧 Token 全部失效。
- Steam 验签服务异常返回 502，Redis 不可用返回 503，校验失败返回 401，非法 platform 返回 400。
- `STEAM_WEB_API_KEY` 留空不影响 OpenID 登录，但玩家资料接口缓存未命中时需要该配置。OpenID 回调本身不创建 user_profile；资料接口会异步创建或更新用户，战绩首次同步也会创建必要的占位用户。
- 按接口要求通过回跳 URL 传递 JWT；前端收到后应立即从地址栏清除 token，部署层避免记录完整回调查询字符串。

## 玩家资料与生涯统计

- 已有数据库升级前执行 `psql -U postgres -d hunt_server -v ON_ERROR_STOP=1 -f src/main/resources/db/migrations/002_add_user_country.sql`，新增可空的 `loc_country_code` 字段。新建数据库直接使用更新后的 schema.sql；本项目不自动执行迁移。
- 设置 `STEAM_WEB_API_KEY` 后，使用 Bearer Token 访问 `GET /api/v1/players/{steam_id}/summary`，返回 Steam ID、昵称、完整头像和国家代码。国家代码属于可选公开资料，Steam 未返回时为 null。
- 基础资料按 Steam ID 缓存在 Redis，键为 `player:steam:summary:v1:{steam_id}`，TTL 固定 30 分钟，读取不会延长 TTL。缓存命中不调用 Steam、不访问数据库；损坏缓存或 Redis 不可用时回源 Steam。此策略不合并多个并发未命中请求。
- 缓存未命中时，调用官方 GetPlayerSummaries API，缓存成功资料后通过专用有界线程池异步 upsert 用户资料。只更新昵称、头像、国家和更新时间，保留 MMR、星级和创建时间。并发写入通过 PostgreSQL ON CONFLICT 避免 steam_id 重复。
- 异步入库属于最终一致性：summary 成功不代表数据库已写入完成。入库失败或队列满时记录日志并清除缓存，后续请求可重新刷新；没有后台自动重试或持久化任务队列。同步战绩时会自动补齐必要的占位用户。
- `GET /api/v1/players/{steam_id}/stats` 直接聚合本地 match_record，不调用 Steam，也不使用资料缓存。返回总场次、撤离场次、总击杀/死亡/助攻、总提取赏金、KDA，以及 user_profile 中当前 MMR/星级（未知为 null）。
- `extraction_rate = extracted_matches / total_matches`，返回 0～1 的小数，保留 4 位；当前没有独立获胜字段，因此 `win_rate` 与撤离率相同。`kda = (total_kills + total_assists) / max(total_deaths, 1)`，保留 2 位，均按四舍五入处理。无本地战绩时总量、比率和 KDA 都为 0，不据此判断 Steam 账号是否存在。
- Steam ID 必须为 17 位数字，否则返回 400；Steam 未返回玩家时为 404；上游网络、格式或其他 HTTP 错误返回 502；未配置 API Key、上游限流或统计数据库不可用返回 503。上游异常响应不会向客户端暴露 API Key。

## 战绩同步与历史查询

- 已有数据库还需执行 `psql -U postgres -d hunt_server -v ON_ERROR_STOP=1 -f src/main/resources/db/migrations/003_add_match_team_kills.sql`。新库使用 schema.sql 即可。
- `POST /api/v1/matches/sync` 接收 `hunt_sync_client.py` 导出的 snake_case JSON；已核对实际脚本的 15 个字段（包括任务清单外的 team_kills）。时间支持带 Z 的 UTC ISO 字符串及微秒精度，encountered_players 保留 name/mmr/downed_by_me 对象列表。未提交 team_kills 时兼容为 0；未知字段（包括 steam_id）返回 400。未知 mmr/mmr_change 可为 null。
- 用户身份仅来自 JWT。用户不存在时，使用 Steam ID 作为占位昵称创建资料，后续 summary 可更新昵称等 Steam 字段。同步不依赖 Steam Web API 或资料缓存。
- 同步在一个数据库事务内完成：检查业务键、创建必要用户、锁定用户行、原子插入对局、更新 MMR。相同用户重复提交同一 match_id 返回成功，忽略后续载荷差异；并发重复由唯一约束与 ON CONFLICT DO NOTHING 处理。已有 match_id 属于其他用户时返回 409，不改写归属。
- 当前 MMR 取该玩家按 match_time、id 倒序的最新非空对局 MMR，防止乱序补传回退。MMR 更新与新对局插入同事务；任一步失败会回滚。星级不从 MMR 推算。
- `GET /api/v1/players/{steam_id}/matches?page=1&size=20&filter=all` 要求 Bearer Token；page 从 1 开始，size 为 1～100。filter 支持 all、extracted（extracted=true）和 died（deaths>0）。结果按 match_time DESC、id DESC 稳定排序，data 包含 total、pages、list；total/pages 按筛选结果计算，越界页的 list 为空。
- GlobalExceptionHandler 统一参数/JSON/校验错误 400、认证错误 401 和未知异常 500；其他业务错误保留对应 HTTP 状态，均使用 Result。Security 过滤链发生的 401/403 继续由认证入口和过滤器输出相同结构。
- 客户端当前使用“每次解析时的当前时间”生成 match_id。直接重传同一 JSON 可幂等；重新解析同一个 XML 会生成不同 ID，后端不能据此识别重复。客户端应在后续改进中使用稳定的对局标识或复用已保存的 JSON。本次未修改客户端脚本。

执行 `mvn verify` 验证认证、资料缓存、异步更新、同步逻辑、DTO/校验、安全过滤链和历史/统计查询。测试夹具 client-export.json 由实际 Python 解析类在固定时间和样例 XML 下生成。Steam/Redis 使用模拟依赖，查询在 H2 的 PostgreSQL 兼容模式执行；PostgreSQL 原生冲突插入、行锁并发与真实基础设施仍需联调。
