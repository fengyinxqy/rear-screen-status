# dsh-plugin-rear-status

DeepSeek Harness 插件：把 DSH 会话状态推给 [背屏亮点](https://github.com/fengyinxqy/rear-screen-status) 的状态 daemon，让小米手机的背屏显示 DSH 是在干活还是空闲。

## 安装

```bash
dsh plugin --profile web add dsh-plugin-rear-status
```

把 `web` 换成你实际使用的 profile。安装后重启 DSH 或新开会话生效。

可选配置：把 `{"endpoint": "http://127.0.0.1:8770"}` 写到 `~/.dsh/rear-status/config.json`，或用环境变量 `REAR_STATUS_URL` 覆盖。

## 前提

这台机器上要跑着背屏亮点的 daemon（`python tools/statusd.py --bind 0.0.0.0`）。完整说明见 [docs/dsh-plugin.md](https://github.com/fengyinxqy/rear-screen-status/blob/main/docs/dsh-plugin.md)。

## 许可

MIT。
