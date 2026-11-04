# ADB 无线配对：AOSP 事实核对与取舍

状态：**未实现（阻塞在依赖决策）**
读者：维护者、发布负责人。
关联代码：`feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/device/`（通道层）、
`P256.kt` / `Spake2P256.kt` / `AdbPairingSession.kt`（本轮新增的密码学切片）。

## 1. 为什么要写这份文档

目标是「对标 DSHA：无 root、免电脑、扫码配对无线 ADB」。开工前我们（包括我在早期报告里）
把 adb 配对描述成「SPAKE2 + TLS 1.3 PSK」。**这个描述是错的**，本轮核对了 AOSP 源码后纠正：

| 早期说法 | AOSP 实际实现（main） |
|---|---|
| SPAKE2 用 P-256，掩码常量 `adbM` / `adbN` | **BoringSSL Curve25519 SPAKE2**（`SPAKE2_CTX_new(spake2_role_alice/bob, "adb pair client", …, "adb pair server", …)`），AOSP 里**没有** `adbM`/`adbN` |
| 配对走 TLS 1.3 预共享密钥（PSK） | **证书 TLS**（`TlsConnection::Create(role, cert, privKey, fd)`，接受任意 peer 证书），握手后 `ExportKeyingMaterial(64)` 与配对码拼接，再跑 SPAKE2 |
| 之后直接跑 adb 协议 | 之后是 adb 自己的 `PairingPacketHeader` + **AES-128-GCM** 加密的 protobuf `PeerInfo` 交换 |

结论：**真实 adb 配对需要四个部件**——(1) 证书 TLS 连接、(2) `ExportKeyingMaterial`（RFC 5705/8446 exporter）、
(3) Curve25519 上的 SPAKE2、(4) AES-128-GCM + adb protobuf 封装。缺一并列，就没有线格式兼容。

## 2. 本轮已完成、但不接线的那一半

`P256.kt`、`Spake2P256.kt`、`Spake2Primitives.kt`、`AdbPairingSession.kt` 是本轮新增的
**RFC 9382 SPAKE2(P-256)** 实现，38 个单测全绿（含 RFC 9382 附录 B 的四个官方向量、
以 JDK `secp256r1` ECDH 交叉验证标量乘）。

它们**不能用于配对真 adbd**，原因就是上表：曲线不同、掩码不同、没有 TLS/EKM、没有 adb 的
AES-GCM + protobuf 封装。因此：

- 不接入 `DshAdbChannel`；
- `AdbPairingSession.unsupported()` 是当前唯一诚实的出口，UI 必须显示 `UNSUPPORTED`；
- 旧 KDoc 里「pairing (SPAKE2 + TLS-PSK) 尚未实现」的说法要改成引用本文档。

保留它们的理由：P-256 曲线算术、HKDF/PBKDF2/HMAC、常数时间比较、TT 编码与确认流程都是
后续 Curve25519 版本可直接复用的骨架，且它们**已经被测试锁住**（包含一个真实 bug 的回归：
`littleEndianLength` 里 Int 移位按 32 取模导致 TT 出错，被 RFC 向量抓出）。

## 3. 三条可选路线

### A. 自研全套（不建议）
自己实现 TLS 记录层 + EKM + Curve25519 SPAKE2 + AES-GCM + protobuf。
工作量数周，且**无法在本机验证**——没有真机配对会话，写多少测试都只能证明自洽，
不能证明与 adbd 互通。风险是把不可用的密码学代码伪装成功能。

### B. 引入 BouncyCastle 补 TLS 与 EKM，自研 Curve25519 SPAKE2（**推荐**）
- `org.bouncycastle:bctls-jdk18on` 提供 TLS 1.3 客户端/服务端与
  `TlsContext.exportKeyingMaterial(label, context, length)`，正好覆盖 (1)(2)。
- Curve25519 上的 SPAKE2（(3)）仍需自研：BoringSSL 的 SPAKE2 用 edwards25519 群，
  JDK 只有 X25519 `KeyAgreement`，做不了 SPAKE2 需要的点运算。
- 代价：依赖体积约 +8 MB、bctls 的服务端 PSK 被标为 experimental（我们做客户端，影响较小）、
  需要评估 R8 与多 dex。
- 风险：仍然要真机验证；但 TLS/EKM 这两层是成熟实现，把自研面缩到只剩下的 Curve25519 SPAKE2。

### C. 不做配对，保持 root / Shizuku 两条路径（当前状态）
- 有 root：一键配置已经可用（写 `adb_keys` + 起 adbd + 回连校验）。
- 有 Shizuku：既有通道可用。
- 无 root 且无 Shizuku：只能手动登记公钥（控制台已写明）。
- 代价：与 DSHA 的「免 root 免电脑」体验有差距；收益：不引入未验证的密码学与 8 MB 依赖。

## 4. 需要决策的事

1. 选 B 还是 C？（B 需要允许新增 `bctls` 依赖，并接受真机验证周期）
2. 若选 B：是否接受「先只做配对的密码学 + 封装，再在真机上迭代到成功」的节奏？
3. 若选 C：对外文案要明确写「无线配对需要 root 或 Shizuku」，不再暗示「将来免 root」。

## 5. 现状小结（可发布的事实）

- ADB **直连**（已实现、已测）：协议客户端、RSA 身份、socket 传输、shell 流控、自动重连。
- ADB **密钥登记**：有 root 一键完成；无 root 需手动。
- ADB **无线配对**：**未实现**，本文档解释原因并给出路线。

## 6. 当前实现状态与真机验证清单（2026-11 更新）

状态：**实验性入口已接线，默认关闭**。

已实现（纯 JVM 单测覆盖，79 例全绿）：

| 部件 | 文件 | 状态 |
|---|---|---|
| 证书 TLS 1.3 + `exportKeyingMaterial("", null, 64)` | `AdbPairingTls.kt`（BC `TlsClientProtocol`，自签 EC P-256 证书由手写 DER 生成） | 编译通过（真实 bctls 1.86），**未真机验证** |
| Curve25519 SPAKE2（BoringSSL 语义） | `Spake2Curve25519.kt`（M/N、口令标量 hack、transcript、64B key material 均按 `spake25519.cc` 逐行实现） | 单测通过，**未与真 adbd 互通** |
| adb 配对包封装 | `AdbPairingPackets.kt`（6 字节头 + 大端 payload、protobuf 编码、AES-128-GCM、nonce 计数器） | 单测通过，**线格式未确认** |
| 状态机与门控 | `AdbPairingController.kt` / `DshAdbPairingPort.kt` / `DshAdbChannel.pair` | 单测通过（5s/20s 超时、单飞、取消、稳定原因码） |
| 控制台入口 | `ui/DshDeviceAccessSection.kt`（默认收起的「实验性：配对无线调试」） | 需 Gradle 编译验证 |

### 四条未验证假设与翻 flag 的方法

`AdbPairingWireAssumptions`（`AdbPairingPackets.kt`）有四个布尔项，默认全 `false`；为 `false` 时
`AdbPairingController` 在任何网络动作之前返回 `UNSUPPORTED`，控制台会显示 `unsupported` 与假设名清单。
**只有在真机抓到证据后才允许置为 `true`**，不要为了「跑通」而翻转：

1. `tlsExporterLabel` — TLS exporter 的 label/context 用空串（对应 AOSP `SSL_export_keying_material(..., nullptr, 0, 0)`）。
   证据：与 adbd 完成握手后 key material 能解出 PeerInfo。
2. `aesGcmDerivation` — AES-128-GCM 的 key/nonce 如何从 SPAKE2 key material 派生（当前默认
   `SHA-256(keyMaterial)` 前 16B/后 12B 是**假设**）。证据：`pairing_auth/aes_128_gcm.cpp` 源码或抓包。
3. `peerInfoLayoutProtobuf` — `PeerInfo` 是 protobuf（字段 1/2/3）还是 `sizeof(PeerInfo)` 的裸结构体。
   AOSP `pairing_connection.cpp` 两者线索都存在。证据：`pairing_connection.h` 的 `PeerInfo` 定义。
4. `packetTypeValues` — `PairingPacket::Type` 的数值（当前假设 `SPAKE2_MSG=0`、`PEER_INFO=1`）。
   证据：`pairing_connection/pairing.proto`。

翻转方式：在构造 `DshAdbPairingPort` 时传入
`assumptions = AdbPairingWireAssumptions(peerInfoLayoutProtobuf = true, aesGcmDerivation = true, packetTypeValues = true, exporterLabel = true)`
（或逐项打开），并在文档里记录证据来源与日期。

### 失败原因码（控制台原样显示）

`tls_failed` · `spake2_failed` · `peer_info_rejected` · `timeout` · `cancelled` ·
`concurrent_pairing` · `invalid_input` · `unsupported` · `transport_failed`

成功只在「设备回的 PEER_INFO 能用我们的 key 解开」时产生；`PairingOutcome` 式的
「握手成功即 READY」不在此列。