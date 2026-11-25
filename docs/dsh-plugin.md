# DSH 插件：让背屏知道 DSH 在跑

Claude Code 和 Codex 会把会话写进 `~/.claude`、`~/.codex`，daemon 扫描一下就知道它们在不在干活。**DSH 不是这样**：它没有可扫描的会话文件，也没有 shell hook。少了这一环，背屏永远看不到 DSH。

这个插件补的就是这一环——把 DSH 的生命周期事件翻译成 daemon 的一份状态推送。

- 源码：[tools/dsh/dsh-plugin.mjs](../tools/dsh/dsh-plugin.mjs)
- 示例配置：[tools/dsh/config.example.json](../tools/dsh/config.example.json)

## 安装

### 方式一：从 npm 装（一条命令）

```bash
dsh plugin --profile web add dsh-plugin-rear-status
```

把 `web` 换成你实际使用的 profile。装完重启 DSH 或新开会话。若提示找不到包（镜像尚未同步），改用方式二。

### 方式二：从源码装

1）把插件放到 DSH 的私有目录：

```bash
mkdir -p ~/.dsh/rear-status
cp tools/dsh/dsh-plugin.mjs ~/.dsh/rear-status/
```

2）可选：改推送地址（默认 `http://127.0.0.1:8770`）：

```bash
cp tools/dsh/config.example.json ~/.dsh/rear-status/config.json
```

也可以不改文件，用环境变量 `REAR_STATUS_URL` 覆盖，它优先于配置文件。

3）在 `~/.dsh/cordis.patch.yml` 里追加一段，把路径换成你机器上的**绝对路径**：

```yaml
- insert:
    - id: rear-status
      name: "file:///C:/Users/Administrator/.dsh/rear-status/dsh-plugin.mjs"
```

- Windows 用正斜杠，保留盘符：`file:///D:/tools/dsh-plugin.mjs`
- macOS / Linux：`file:///Users/you/.dsh/rear-status/dsh-plugin.mjs`

4）重启 DSH，或新开一个会话——插件在进程启动时加载，已开着的会话不会热加载。

## 验证

1. `~/.rear-status/dsh-plugin.log` 里应出现 `plugin loaded`。
2. 发一条消息让 Agent 跑起来，然后：

```bash
curl -s http://127.0.0.1:8770/status.json
```

读数里应出现 `"status": "developing"` 和 `"agents": ["dsh"]`。

3. 背屏右侧出现鲸鱼图标；一轮结束后回到「空闲」。

## 它做了什么

| DSH 事件 | 推送 |
|---|---|
| `agent/session-start` | `developing` |
| `agent/pre-step` | `developing`（一轮正在跑，同时是心跳） |
| `agent/status` = `idle` / `stopped` | `idle` |
| `agent/turn-stopping` | `idle` |
| `agent/disposed` | 移除该会话并重新发布 |

几个刻意的设计：

- **只有 developing 和 idle。** 「停下来问你」和「这一轮跑完」在生命周期事件里长得一样，猜错的代价比不报更大，所以插件不提供这个状态。
- **`developing` 是租约，不是事实。** 5 分钟内没有新的 `pre-step` 就自动算作空闲——事件丢了也不会把面板钉在「开发中」。
- **节流与续命。** 每 20 秒最多发一次心跳；持续在跑时每 5 分钟补一次 keepalive，避免 daemon 的推送有效期过期。
- **会话 TTL 6 小时。** 关窗口或杀进程不一定触发 `disposed`，僵尸会话会自己过期。
- **推送失败不抛错。** 1.2 秒超时；连不上就把同样的 payload 写到 `~/.rear-status/dsh-state.json`，daemon 在 120 秒内看到这个文件的新写入，会把没有署名的推送认作 DSH。桥断了，面板降级，而不是变黑。
- **不拖累会话。** 所有回调里都吞掉异常；状态桥不该弄坏它正在报告的东西。

## 日志与清理

日志在 `~/.rear-status/dsh-plugin.log`，只记录事件与推送结果，不含对话内容。可以直接删，插件会重新创建。插件同时维护 `~/.rear-status/dsh-state.json`，那是给 daemon 的降级备份。

## 卸载

1. 删除 `~/.dsh/cordis.patch.yml` 里的整段 `- insert:`（含 `id: rear-status`）。
2. 删除 `~/.dsh/rear-status/`。
3. 重启 DSH。

之后 DSH 会重新变成「背屏看不见的 Agent」；想临时补一次，可以手工 `POST /push`，协议见 [README](../README.md#状态协议)。
