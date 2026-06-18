# MMMVaultSync

## Version

当前版本：`3.3.0`

## 更新内容

### 3.3.0

- 新增余额变动历史自动清理配置。
- 支持按保留天数清理旧历史记录。
- 支持按每个玩家每种货币保留最近 N 条记录，避免历史表无限增长。
- 历史清理任务在异步线程执行，不阻塞服务器主线程。

### 3.2.0

- 新增余额变动历史表 `mmm_vault_sync_balance_changes`。
- 在线余额变动提示改为 MySQL 原子抢占，同一次余额 revision 全服只会提醒一次。
- 玩家离线期间产生的余额变动会记录下来，上线后发送可点击的未读摘要。
- 新增 `/mmmvaultsync changes` 查看最近余额变动；玩家只能查看自己，管理员可以查看指定玩家。
- Redis 继续只负责跨服实时刷新，提示去重和历史记录由 MySQL 负责。

### 3.1.3

- 普通玩家余额变动提示不再显示 `[MMMVaultSync]` 前缀。
- 新增基于余额 revision 的跨服重复提示抑制，减少玩家切服后收到同一次余额变动提示的情况。

### 3.1.2

- 修复首次进入待配置模式后，通过 `/mmmvaultsync reload` 完成初始化时没有注册 PlaceholderAPI 变量的问题。

### 3.1.1

- 补充 PlaceholderAPI 变量说明，逐项说明每个变量的含义和示例。

### 3.1.0

- 新增 PlaceholderAPI 支持，用于菜单、聊天、计分板等插件展示余额和同步状态。
- 新增 `%mmmvaultsync_balance%`、`%mmmvaultsync_balance_<货币ID>%` 等显示变量。
- 明确 PAPI 变量只用于显示；其他插件操作货币应继续使用 `VaultSyncCurrencyService` API。

### 3.0.1

- 优化 `/mmmvaultsync help` 显示，不再输出一整串难读的子命令总览。
- 补充消息变量说明，明确 `{balance}` 是当前余额变量。
- 明确余额查询和余额修改命令都需要 `mmmvaultsync.admin` 权限。
- 补充其他插件接入建议：插件联动应使用 API，不应调用管理员命令。
- 优化余额命令的 TAB 补全提示。

### 3.0.0

- 优化周期远端刷新：常规 `periodic` 刷新只拉取默认货币，避免在线玩家定时刷新时全量查询所有自管货币。
- 修复数据库无余额记录时单货币加载可能因为 `Map.of(..., null)` 抛出异常的问题。
- 简化余额查询命令解析，`balance` 和 `bal` 共用查询参数规则。
- 优化余额变动提示抑制逻辑，减少字符串分配，并清理过期提示状态。
- 余额比较改为 BigDecimal 原生比较，避免转 double 带来的精度偏差。

Redis 同步说明：
- MySQL 继续作为权威存储。
- Redis 只负责跨服事件广播。
- 子服收到事件后会回拉数据库刷新本地状态。

适用于 `Velocity + Paper/Purpur` 联机架构的跨服经济同步插件。


## 插件定位

MMMVaultSync 现在分成两部分能力：

1. 默认货币同步
   默认货币仍然接管你现有的 `Vault / CMI` 主经济。
   插件使用 `MySQL` 作为跨服权威数据源，在多个子服之间同步默认货币余额。

2. 自管多货币
   除默认货币外，插件还可以自己管理额外货币。
   这些货币不依赖 Vault，适合你后续的交易、菜单、任务、活动等插件直接调用 API。

## 主要特性

- 默认货币支持跨服同步
- 使用 MySQL 作为权威余额存储
- 玩家进服时拉取权威余额
- 在线玩家默认货币变化异步回写数据库
- 周期性拉取远端余额，处理跨服改动
- 支持维护模式、排空、校验、安全重载
- 支持对外暴露同步阶段信号
- 支持插件内部自管多货币
- 中文配置与中文提示
- 不在主线程执行数据库 IO

## 设计边界

- 它不会替代你现有的 Vault 经济插件，只会接管同步层
- 默认货币仍然受底层经济插件行为影响
- 如果其他插件绕过 Vault 或绕过同步层直接改余额，维护期间仍可能造成风险
- 跨服同步不是“绝对瞬时”，而是基于事件捕获、异步写入和周期刷新

## 安装步骤

1. 将 [target/mmm-vault-sync-3.3.0.jar](target/mmm-vault-sync-3.3.0.jar) 放入每个子服的 `plugins` 目录。
2. 每个子服先启动一次，让插件自动生成配置文件。
3. 编辑每个子服的 `plugins/MMMVaultSync/config.yml`。
4. 为每个子服填写不同的 `server-id`。
   例如：
   - 生存服：`survival`
   - 冒险服：`adventure`
5. 两个子服使用同一套 MySQL 连接信息。
6. 首次配置完成后，不需要重启整个服务器，直接执行：
   - `/mmmvaultsync reload`

## 首次加载说明

如果插件第一次启动，或配置文件里仍保留默认占位值：

- `server-id: server`
- `database.password: password`

插件会进入“待配置模式”：

- 不连接数据库
- 不启动同步逻辑
- 控制台输出中文安装向导
- 管理员玩家登录后也会收到提示

你只需要改完配置后执行：

```text
/mmmvaultsync reload
```

## 配置说明

主配置文件：[`src/main/resources/config.yml`](src/main/resources/config.yml)

### 必填项

- `server-id`
- `database.host`
- `database.port`
- `database.database`
- `database.username`
- `database.password`

### 默认货币

`default-currency` 表示主货币显示配置。

注意：

- 默认货币实际余额仍来自 Vault 后端
- `display-name` 和 `symbol` 主要用于插件消息提示
- 默认货币 ID 固定为 `default`

### 自管货币

额外货币配置在 `currencies` 下，例如：

```yml
currencies:
  gems:
    display-name: 宝石
    symbol: "◇"
    starting-balance: 0
    notify-on-change: true
```

说明：

- `gems` 是货币 ID
- 货币 ID 建议只使用小写英文、数字、下划线
- 不要与 `default` 冲突
- 这些货币完全由 MMMVaultSync 自己管理

## 数据库说明

默认数据表名：

```text
mmm_vault_sync_balances
```

余额变动历史表名：

```text
mmm_vault_sync_balance_changes
```

`2.0.0` 起，表结构按下面的逻辑工作：

- 主键：`(uuid, currency_id)`
- 默认货币和自管货币统一存表
- 旧的单货币表会自动迁移，补上 `currency_id`

`3.2.0` 起，插件会额外创建余额变动历史表：

- 每次真实余额变化写入一条历史记录
- 唯一键：`(uuid, currency_id, revision)`
- 用于保证同一次余额变化全服只提示一次
- 用于玩家上线后查看离线期间的余额变动
- 关服刷盘、维护 drain、启动补种等快照写入不会写入历史记录

`3.3.0` 起，插件会自动清理历史表，避免长期运行后历史记录无限增长。默认策略：

- 保留最近 30 天历史
- 每个玩家每种货币最多保留 500 条历史
- 每 6 小时异步清理一次

对应配置：

```yml
history:
  retention-days: 30
  max-records-per-player-currency: 500
  cleanup-interval-ticks: 432000
```

如果要关闭某个清理条件，可以设为 `0`。

### Redis

Redis 是可选的跨服实时刷新通道。开启后，某个子服写入新余额 revision 时，会广播事件给其他子服，让其他子服更快回拉 MySQL。

提示去重不依赖 Redis，而是依赖 MySQL 历史表。因此 Redis 未开启时，余额仍能通过周期刷新同步，并且同一条历史记录仍不会重复提示；只是跨服刷新速度取决于 `sync.remote-refresh-interval-seconds`。

## 命令

权限节点：

```text
mmmvaultsync.admin
```

### 基础命令

```text
/mmmvaultsync status
/mmmvaultsync currencies
/mmmvaultsync sync <玩家> [货币ID]
```

### 余额管理命令

```text
/mmmvaultsync balance <玩家> [货币ID]
/mmmvaultsync bal <玩家> [货币ID]
/mmmvaultsync balance <玩家> set <金额> [货币ID]
/mmmvaultsync balance <玩家> add <金额> [货币ID]
/mmmvaultsync balance <玩家> take <金额> [货币ID]
```

说明：

- 不填写货币 ID 时，默认操作 `default`
- `default` 表示主货币
- 其他货币 ID 表示插件自管货币
- 余额查询和余额修改都需要 `mmmvaultsync.admin`

### 余额变动记录

```text
/mmmvaultsync changes [数量]
/mmmvaultsync changes <玩家> [数量]
```

说明：

- 普通玩家可以查看自己的最近余额变动
- 管理员可以查看指定玩家的最近余额变动
- 玩家上线时，如果离线期间有未读余额变动，会收到一条可点击摘要
- 玩家点击摘要或执行 `/mmmvaultsync changes` 后，会把显示到的未读记录标记为已读

### 消息变量

这些变量用于 `lang/ch_ZN.yml` 的提示文本：

```text
{player}  玩家名
{currency}  货币显示名
{reason}  操作原因
{server}  服务器标识
{phase}  当前阶段
{count}  数量统计
{ticks}  Tick 间隔
{millis}  毫秒间隔
{value}  布尔/枚举值
{detail}  失败详情
{amount}  金额变化值
{balance}  当前余额
{id}  货币 ID
{name}  货币名称
{symbol}  货币符号
{type}  货币类型
```

### PlaceholderAPI 变量

如果服务器安装了 PlaceholderAPI，插件会自动注册 `mmmvaultsync` 变量。  
这些变量适合菜单、聊天、计分板、称号等插件显示信息，不适合做发钱、扣钱或交易逻辑。

```text
%mmmvaultsync_balance%
%mmmvaultsync_balance_default%
%mmmvaultsync_balance_<货币ID>%
%mmmvaultsync_currency_name_<货币ID>%
%mmmvaultsync_currency_symbol_<货币ID>%
%mmmvaultsync_default_currency%
%mmmvaultsync_currency_count%
%mmmvaultsync_phase%
%mmmvaultsync_maintenance%
%mmmvaultsync_drain%
%mmmvaultsync_verify%
```

变量说明：

| 变量 | 含义 | 示例 |
| --- | --- | --- |
| `%mmmvaultsync_balance%` | 当前玩家的默认货币余额，等同于 `default` 货币 | `1234.5` |
| `%mmmvaultsync_balance_default%` | 当前玩家的默认货币余额 | `1234.5` |
| `%mmmvaultsync_balance_<货币ID>%` | 当前玩家指定货币的余额，把 `<货币ID>` 换成实际 ID | `%mmmvaultsync_balance_gems%` |
| `%mmmvaultsync_currency_name_<货币ID>%` | 指定货币的显示名称 | `%mmmvaultsync_currency_name_gems%` -> `宝石` |
| `%mmmvaultsync_currency_symbol_<货币ID>%` | 指定货币的符号 | `%mmmvaultsync_currency_symbol_gems%` -> `◆` |
| `%mmmvaultsync_default_currency%` | 默认货币 ID | `default` |
| `%mmmvaultsync_currency_count%` | 当前已加载的货币数量，包含默认货币 | `2` |
| `%mmmvaultsync_phase%` | 插件当前同步阶段 | `NORMAL` |
| `%mmmvaultsync_maintenance%` | 是否处于维护模式 | `true` / `false` |
| `%mmmvaultsync_drain%` | 当前 drain 是否完成 | `true` / `false` |
| `%mmmvaultsync_verify%` | 当前 verify 是否完成 | `true` / `false` |

说明：

- `%mmmvaultsync_balance%` 等同于 `%mmmvaultsync_balance_default%`
- `%mmmvaultsync_balance_<货币ID>%` 显示指定货币余额，例如 `%mmmvaultsync_balance_gems%`
- PAPI 读取的是插件当前缓存/在线快照，不会为了显示变量阻塞等待数据库查询
- 其他插件要修改余额时，必须使用 `VaultSyncCurrencyService` API

### 安全维护流程

```text
/mmmvaultsync maintenance on
/mmmvaultsync drain
/mmmvaultsync verify
/mmmvaultsync reload confirm
/mmmvaultsync maintenance off
```

推荐重载流程：

1. 开启维护模式
2. 执行 `drain`
3. 执行 `verify`
4. 执行 `reload confirm`
5. 关闭维护模式

## 维护与安全模型

### maintenance on

作用：

- 拒绝新的同步写入
- 锁定默认货币的 Vault 代理操作
- 给其他插件发出“当前不适合经济操作”的信号

### drain

作用：

- 等待异步写入完成
- 把在线玩家当前余额尽量刷入数据库

### verify

作用：

- 校验在线玩家本地余额与数据库余额是否一致

### reload confirm

限制：

- 必须先进入维护模式
- 必须先完成 `drain`
- 必须先完成 `verify`
- 需要二次确认

## 对外 API

### 只读状态服务

```text
local.mmm.vaultsync.api.VaultSyncStateService
```

用途：

- 判断插件是否处于维护模式
- 判断是否正在排空、校验、重载
- 供其他插件在敏感阶段暂停交易或发钱逻辑

### 多货币服务

```text
local.mmm.vaultsync.api.VaultSyncCurrencyService
```

用途：

- 获取默认货币 ID
- 获取全部货币定义
- 查询玩家余额
- 修改自管货币或默认货币

其他插件应通过 Bukkit ServicesManager 获取服务：

```java
RegisteredServiceProvider<VaultSyncCurrencyService> provider =
        Bukkit.getServicesManager().getRegistration(VaultSyncCurrencyService.class);
if (provider == null || !provider.getProvider().canAcceptEconomicOperations()) {
    return;
}

VaultSyncCurrencyService service = provider.getProvider();
service.addBalanceAsync(playerId, service.getDefaultCurrencyId(), BigDecimal.valueOf(100), "reward-plugin");
```

不要在插件联动里执行 `/mmmvaultsync balance ...` 管理员命令。命令只用于人工管理；插件应直接调用 API，避免权限、文本解析和跨线程问题。

### 事件

```text
local.mmm.vaultsync.api.VaultSyncPhaseChangeEvent
local.mmm.vaultsync.api.VaultSyncCurrencyBalanceChangeEvent
```

用途：

- 监听同步阶段变化
- 监听玩家某个货币的余额变化

## 给后续插件的建议

如果你以后要写玩家交易插件、任务插件、菜单插件，建议遵守这套规则：

1. 在执行经济操作前先读取 `VaultSyncStateService` 或 `VaultSyncCurrencyService`
2. 如果插件不在 `NORMAL` 阶段，就暂缓交易或资金结算
3. 不要用 `/mmmvaultsync balance ...` 这种管理员命令来做插件联动
4. 默认货币继续走 Vault 生态
5. 新增业务货币优先直接走 MMMVaultSync 的自管多货币 API

这样做的好处是：

- 性能开销很小
- 不需要高频轮询
- 不把数据库或 PAPI 当联动主通道
- 能明显降低交易插件和同步插件之间的冲突风险

## 编译

在工程目录执行：

```powershell
mvn package
```

编译产物：

```text
target/mmm-vault-sync-3.3.0.jar
```

## 当前建议

正式上线前，至少做这几项测试：

1. 两个子服默认货币跨服加减是否同步
2. 玩家在 A 服发钱，B 服在线时是否会在刷新周期内看到变化
3. 维护模式下默认货币是否会拒绝新的 Vault 改动
4. `drain + verify + reload confirm` 整套流程是否符合预期
5. 自管货币的 `query / add / take / set` 是否正常
