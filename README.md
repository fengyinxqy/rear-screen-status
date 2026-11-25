# 背屏亮点 · Rear Screen Status

把电脑上 Claude Code、Codex 或 DSH 的运行状态显示在支持背屏的小米手机上。本项目不是小米或上述工具的官方应用。

**当前只发布源码，暂无正式签名 APK。** 自行构建见[构建与测试](docs/build.md)。

## 快速开始

1. 从 [Shizuku 官网](https://shizuku.rikka.app/)或 [官方 Releases](https://github.com/RikkaApps/Shizuku/releases)安装 Shizuku，按指引启动。它是独立应用；背屏亮点通过它获取用户授权的 shell 能力，用来把面板移到背屏。
2. 安装自行签名的背屏亮点 APK，进入设置并授权 Shizuku。
3. 在电脑运行 `python tools/statusd.py --bind 0.0.0.0`。仅在可信局域网使用，并用防火墙将 TCP 8770 的入站访问限制为手机。
4. 在 App 中填 `http://电脑局域网IP:8770/status.json`，点「保存并测试」，选中背屏再点「开始背屏显示」。部分 HyperOS 版本会在锁屏时切回系统背屏界面；非 root 设备重启后可能需要重启 Shizuku。

## 隐私与许可

PC 服务读取本机 Agent 会话文件并接收状态推送。HTTP 响应可能包含状态、会话数、Agent 类型与项目目录名；服务没有 TLS 或鉴权，不要将端口映射到公网。仅本机测试时用 `--bind 127.0.0.1`。

项目原创代码采用 [MIT](LICENSE)。见[第三方依赖与致谢](docs/third-party.md)了解 Shizuku SDK、测试依赖和参考项目。
