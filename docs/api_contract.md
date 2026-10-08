# Hunt 1896 API 契约规范

## 1. 认证模块 (Auth)
- GET /api/v1/auth/steam/login-url?platform={web|mobile}
- GET /api/v1/auth/steam/callback (Steam OpenID 2.0 回调重定向)
- GET /api/v1/auth/session (Header: Authorization: Bearer <token>)

### 认证返回约定

- login-url 的 platform 支持 web（默认）和 mobile；返回 `{"code":0,"message":"success","data":{"url":"https://steamcommunity.com/openid/login?..."}}`。
- login-url 同时设置短期 HttpOnly/Secure Cookie，客户端需保留到 Steam 回调；Web 跨域请求需使用 credentials: include，移动端需共享登录浏览器的 Cookie 容器。
- callback 的 return_to 包含 platform 和随机 state；成功校验后 302 到配置的回跳地址，附带 token 与 steam_id。state 和 Steam nonce 一次性使用，有效期 10 分钟。
- session 返回 `{"code":0,"message":"success","data":{"authenticated":true,"steam_id":"76561198000000000","expires_at":"2026-10-15T10:00:00Z"}}`（示例时间）。
- 无效或过期认证返回 HTTP 401；非法 platform 返回 400；Steam 服务异常返回 502；认证状态存储异常返回 503。错误体为 `{"code":401,"message":"...","data":null}`（code 随错误状态变化）。

## 2. 玩家模块 (Player)
- GET /api/v1/players/{steam_id}/summary (Header: Authorization: Bearer <token>)
- GET /api/v1/players/{steam_id}/stats (总生涯统计: MMR, 总击杀, 场次, KDA)

### 玩家资料返回约定

两个接口均需 Bearer Token，steam_id 必须为 17 位数字。

summary 返回示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "steam_id": "76561198000000000",
    "persona_name": "Hunter",
    "avatar_full": "https://avatars.example/full.jpg",
    "loc_country_code": "CN"
  }
}
```

Steam 未公开国家代码或头像时对应字段为 null。Redis 缓存 30 分钟；未命中时调用 Steam，异步更新 user_profile，因此响应不保证资料已完成入库。

stats 的 data 示例：

```json
{
  "steam_id": "76561198000000000",
  "total_matches": 10,
  "extracted_matches": 6,
  "win_rate": 0.6000,
  "extraction_rate": 0.6000,
  "total_kills": 20,
  "total_deaths": 8,
  "total_assists": 4,
  "kda": 3.00,
  "total_bounty_extracted": 1200,
  "current_mmr": 2500,
  "mmr_stars": 4
}
```

- 总量从该玩家全部本地 match_record 聚合，不受分页限制；MMR/星级从 user_profile 读取。
- extraction_rate 为撤离场次 / 总场次，取值 0～1，四舍五入保留 4 位。当前 win_rate 按撤离率计算，没有独立获胜判定。
- KDA 为 (总击杀 + 总助攻) / max(总死亡, 1)，四舍五入保留 2 位。无战绩时总量、比率和 KDA 返回零；未知 MMR/星级返回 null。
- 错误使用统一 Result：非法 ID 为 400，缺失/无效 Token 为 401，Steam 玩家不存在为 404，Steam 上游异常为 502，缺少 API Key/Steam 限流/统计数据库不可用为 503。

## 3. 战绩同步与查询模块 (Match)
- POST /api/v1/matches/sync (Header: Authorization: Bearer <token>, 接收 attributes.xml 解析出来的单局 JSON)
- GET /api/v1/players/{steam_id}/matches?page=1&size=20&filter={all|extracted|died} (分页查询历史战绩)

### 同步请求与幂等性

请求体与当前 hunt_sync_client.py 对齐；客户端还包含 team_kills，因此一并接收与保存：

```json
{
  "match_id": "m_300eb470fde2",
  "match_time": "2026-10-08T10:00:00.123456Z",
  "map_name": "Mammon's Gulch",
  "game_mode": "Bounty Hunt",
  "extracted": false,
  "kills": 3,
  "team_kills": 4,
  "deaths": 1,
  "assists": 2,
  "team_wipes": 1,
  "bounty_extracted": 100,
  "mmr": 2500,
  "mmr_change": -10,
  "encountered_players": [{"name": "Enemy", "mmr": 2600, "downed_by_me": 1}]
}
```

- steam_id 从 JWT 获取，请求体不接受此字段。match_id/map_name/game_mode 为非空字符串，长度上限分别为 128/100/50。
- 时间、撤离标志、计数和遭遇列表必填；计数非负，列表可以为 []。team_kills 为兼容早期载荷可省略，默认 0。mmr/mmr_change 可为 null；mmr 非负，mmr_change 可为负数。
- 新记录和相同用户的重复记录均返回 HTTP 200：`{"code":0,"message":"success","data":null}`。重复载荷不会修改已保存的对局或 MMR。
- 全局唯一 match_id 若已属于其他用户，返回 409。当前数据模型不支持同一业务 ID 保存多个玩家视角。
- 首次同步会补齐占位用户资料；事务内插入战绩并更新该用户最新非空对局 MMR，历史补传不覆盖较新的 MMR。
- 幂等性以相同 match_id 为前提：当前客户端每次解析会重新生成 ID，应重传已保存 JSON，不能通过重新解析同一 XML 保持 ID 稳定。

### 历史分页响应

page 默认 1，size 默认 20、范围 1～100；filter 默认 all。extracted 使用 extracted=true，died 使用 deaths>0。按 match_time DESC、id DESC 排序。

```json
{"code":0,"message":"success","data":{"total":0,"pages":0,"list":[]}}
```

list 中每条记录包含同步字段及 id、steam_id。total 为筛选后的总记录数，pages 为总页数；请求超过末页时保留总量元数据并返回空 list。

参数或 JSON 校验失败统一返回 400；缺失或无效 Token 为 401；未预期异常为 500。错误体固定包含 code、message、data，不返回堆栈或内部异常信息。
