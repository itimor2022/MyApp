# WebView APK 双入口配置操作手册（当前工程最终版）

适用工程：`com.obs.bili`  
适用版本：当前已接入 **OSS + DNS TXT + 本地缓存** 的客户端实现  
当前状态：**已完成 DNS TXT 多源兜底、Gson 混淆修复、Release 日志隐藏、R8 稳定适配**

---

# 1. 文档目的

本文档基于当前工程的**真实实现**整理成一套可重复执行的发布与维护手册。  
目标是做到：

- 后续只改远端配置，尽量不改 App 逻辑
- OSS 主入口失效时自动切到 DNS TXT
- DNS TXT 支持多个域名轮询
- Release 包不打印敏感日志
- Release 混淆后不闪退、不因 Gson / dnsjava / R8 出问题

---

# 2. 当前工程需要关注的文件

## 2.1 Android 业务代码文件

以下文件位于：

```text
app/src/main/java/com/obs/yl/
```

### 必须重点关注的文件

- `App.kt`
- `SplashActivity.kt`
- `MainActivity.kt`
- `RemoteConfigRepository.kt`
- `DnsTxtResolver.kt`
- `CryptoManager.kt`
- `ConfigCache.kt`
- `RemoteConfigModels.kt`
- `LogUtil.kt`

### 辅助文件

- `SwipeBackHelper.kt`

---

## 2.2 配置与构建文件

### 位于项目根目录或 app 模块下

- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `gradle.properties`

---

## 2.3 平时改配置时最常动哪些文件

### 只换远端地址 / 远端配置时
通常**不需要重新打包**，主要动远端内容即可：

- OSS 上的 `config.json`
- DNS TXT 记录内容

### 需要改客户端时
才需要改这些本地文件：

- `app/src/main/java/com/obs/yl/RemoteConfigRepository.kt`
- `app/src/main/java/com/obs/yl/CryptoManager.kt`
- `app/src/main/java/com/obs/yl/MainActivity.kt`
- `app/src/main/java/com/obs/yl/DnsTxtResolver.kt`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`

---

# 3. 当前工程的完整加载链路

当前客户端真实加载顺序如下：

```text
本地缓存
→ OSS_URLS（主入口）
→ DNS_TXT_DOMAINS（备用入口，按顺序依次尝试）
→ LOCAL_FALLBACK_DOMAINS（本地内置兜底）
```

---

## 3.1 具体由哪个文件负责

### 启动页调度
文件：

```text
app/src/main/java/com/obs/yl/SplashActivity.kt
```

职责：

- 启动后调用远程配置加载逻辑
- 成功后跳转 `MainActivity`
- 失败时显示错误页 / 重试

---

### 配置加载总入口
文件：

```text
app/src/main/java/com/obs/yl/RemoteConfigRepository.kt
```

职责：

- 读取本地缓存
- 拉取 OSS 配置
- 拉取 DNS TXT 配置
- 本地硬编码兜底
- 检测落地域名可用性
- 返回最终可启动的域名方案

---

### DNS 解析
文件：

```text
app/src/main/java/com/obs/yl/DnsTxtResolver.kt
```

职责：

- 查询 TXT 记录
- 依次尝试多个 DNS 服务器
- 返回 TXT 原始内容

---

### 页面加载与首屏切换
文件：

```text
app/src/main/java/com/obs/yl/MainActivity.kt
```

职责：

- 接收 `SplashActivity` 传入的域名列表
- WebView 加载落地域名
- 首屏失败时自动切换下一个域名
- 必要时再次回源获取备用方案

---

### 本地缓存
文件：

```text
app/src/main/java/com/obs/yl/ConfigCache.kt
```

职责：

- 缓存最近一次成功配置
- 缓存上次成功落地域名
- 在网络异常时作为兜底

---

### 解密与验签
文件：

```text
app/src/main/java/com/obs/yl/CryptoManager.kt
```

职责：

- AES 解密
- HMAC / 签名校验
- SHA256 计算

---

# 4. 当前工程的配置格式

---

## 4.1 OSS 配置格式

**对应远端文件**：`config.json`

通常上传到：

- OSS
- COS
- CDN
- 其他 HTTP 可访问静态地址

当前工程在客户端中由以下文件消费：

```text
app/src/main/java/com/obs/yl/RemoteConfigRepository.kt
```

OSS 内容支持加密包格式，例如：

```json
{
  "ts": 1775220659,
  "iv": "Base64IV",
  "data": "Base64CipherText",
  "sign": "SignValue"
}
```

---

## 4.2 上面密文解出来后的明文 JSON

解密后通常得到：

```json
{
  "version": 3,
  "timestamp": 1775000000,
  "expire_at": 1775600000,
  "data": {
    "domains": [
      {
        "url": "https://a.example.com",
        "weight": 100
      },
      {
        "url": "https://b.example.com",
        "weight": 90
      }
    ],
    "feature_x": true,
    "gray_ratio": 20
  }
}
```

对应模型文件：

```text
app/src/main/java/com/obs/yl/RemoteConfigModels.kt
```

涉及类：

- `EncryptedEnvelope`
- `DomainItem`
- `RemoteConfig`
- `RemoteConfigData`
- `DnsTxtPayload`
- `LaunchPlan`

---

## 4.3 DNS TXT 推荐格式

推荐把 DNS TXT 作为**备用入口发现机制**，不要直接塞满完整业务配置。  
优先放一个备用配置地址，或者极简域名列表。

### 推荐方式 1：TXT 中放加密后的 DNS payload

解密后类似：

```json
{
  "backup_config_url": "https://cdn-backup.example.com/app/config.json",
  "domains": [
    {
      "url": "https://ns1.example.com",
      "weight": 100
    },
    {
      "url": "https://ns2.example.com",
      "weight": 90
    }
  ],
  "expire_at": 1775600000
}
```

### 推荐方式 2：TXT 放纯文本域名列表（应急）

```text
https://ns1.example.com,https://ns2.example.com
```

---

# 5. 发布前必须固定好的客户端文件

---

## 5.1 `AndroidManifest.xml`

文件路径：

```text
app/src/main/AndroidManifest.xml
```

要求：

- `application` 指向 `.App`
- 声明网络权限
- 入口页为 `SplashActivity`
- 建议 `MainActivity` 不对外暴露

建议检查：

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`

---

## 5.2 `RemoteConfigRepository.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/RemoteConfigRepository.kt
```

### 发布前必须确认的常量

#### 1）OSS 配置源

```kotlin
private val OSS_URLS = listOf(
    "https://your-main-oss.example.com/app/config.json",
    "https://your-backup-cdn.example.com/app/config.json"
)
```

#### 2）DNS TXT 域名列表

```kotlin
private val DNS_TXT_DOMAINS = listOf(
    "_cfg.example.com",
    "_cfg-backup.example.net"
)
```

#### 3）本地兜底落地域名

```kotlin
private val LOCAL_FALLBACK_DOMAINS = listOf(
    DomainItem("https://fallback1.example.com", 100),
    DomainItem("https://fallback2.example.com", 90)
)
```

---

## 5.3 `DnsTxtResolver.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/DnsTxtResolver.kt
```

### 当前建议的 DNS 服务器顺序

```kotlin
private val dnsServers = listOf(
    "8.8.8.8",
    "1.1.1.1",
    "9.9.9.9",
    null
)
```

含义：

- 优先 Google DNS
- 再试 Cloudflare
- 再试 Quad9
- 最后才试系统 DNS

这样可以降低本地运营商 DNS 污染带来的失败率。

---

## 5.4 `CryptoManager.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/CryptoManager.kt
```

### 发布前必须替换的真实参数

- AES key
- HMAC key / 签名密钥
- RSA 公钥（如果你使用 RSA 验签流程）

要求：

- AES key Base64 解码后长度必须是 `16 / 24 / 32` 字节
- 公钥必须是合法 X509 公钥
- 客户端和配置生成脚本必须使用同一套密钥体系

---

## 5.5 `MainActivity.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/MainActivity.kt
```

### 当前必须保留的修复点

当前版本已经修复了 Gson 在 Release 混淆后崩溃的问题。

**不要再改回 `TypeToken<List<DomainItem>>() {}` 的解析方式。**

当前安全写法是：

```kotlin
val parsed = runCatching {
    gson.fromJson(domainsJson, Array<DomainItem>::class.java)
        ?.toList()
        .orEmpty()
}.getOrDefault(emptyList())
```

如果改回 `TypeToken`，Release 混淆后会再次闪退。

---

## 5.6 `LogUtil.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/LogUtil.kt
```

职责：

- 统一封装日志
- 避免业务代码直接使用 `android.util.Log`
- 配合 Release 混淆移除日志输出

---

## 5.7 `build.gradle.kts`

文件路径：

```text
app/build.gradle.kts
```

### 当前必须保留的配置

#### 1）BuildConfig 开启

```kotlin
buildFeatures {
    buildConfig = true
}
```

#### 2）Release 混淆开启

```kotlin
buildTypes {
    getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        isDebuggable = false
    }
}
```

#### 3）dnsjava 的 SPI 资源排除

```kotlin
packaging {
    resources {
        excludes += setOf(
            "META-INF/services/java.net.spi.InetAddressResolverProvider",
            "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
        )
    }
}
```

---

## 5.8 `proguard-rules.pro`

文件路径：

```text
app/proguard-rules.pro
```

当前必须包含：

- Gson 泛型签名保留
- TypeToken 保留
- 模型类保留
- dnsjava 的 dontwarn
- 日志移除规则

---

# 6. 当前工程的标准操作步骤

---

## 步骤 1：确认客户端固定参数

检查下面这些文件：

### `RemoteConfigRepository.kt`
确认：

- `OSS_URLS`
- `DNS_TXT_DOMAINS`
- `LOCAL_FALLBACK_DOMAINS`

### `DnsTxtResolver.kt`
确认：

- `dnsServers` 是否正确

### `CryptoManager.kt`
确认：

- AES key
- 签名密钥
- RSA 公钥

如果以上都没改，则后续只需更新远端配置，不需要重新打包。

---

## 步骤 2：准备新的业务域名

通常准备这些内容：

- 主站 H5 域名
- API 域名
- 备用 H5 域名
- 备用配置地址
- 版本号
- 过期时间

---

## 步骤 3：生成 OSS 配置

使用你的配置生成脚本生成新的加密 `config.json`。

生成后的文件上传到：

- `OSS_URLS[0]`
- `OSS_URLS[1]`
- 其他 CDN / 备用入口

---

## 步骤 4：生成 DNS TXT 备用包

建议 TXT 内容尽量小。

优先选择：

- 放 `backup_config_url`
- 或放少量直连域名

不要把巨大完整配置都塞进 TXT。

---

## 步骤 5：配置 DNS TXT

在 DNS 服务商后台为这些域名配置 TXT：

- `_cfg.example.com`
- `_cfg-backup.example.net`

如果平台自动加引号属于正常现象，当前工程已兼容：

- 标准 JSON
- 转义 JSON
- 包一层引号的 JSON
- 纯文本域名列表

---

## 步骤 6：验证远端可读性

### 验证 OSS

```bash
curl https://你的OSS地址/config.json
```

### 验证 TXT

```bash
dig TXT _cfg.example.com
nslookup -type=TXT _cfg.example.com
```

### 指定 DNS 验证

```bash
dig TXT _cfg.example.com @8.8.8.8
dig TXT _cfg.example.com @1.1.1.1
```

---

## 步骤 7：联调客户端

启动 App 后：

- 正常情况先走 OSS
- OSS 中落地域名全部失败时，自动切 DNS TXT
- 多个 TXT 源按顺序尝试
- DNS 也失败时，自动尝试本地缓存和本地兜底

---

## 步骤 8：检查日志（Debug 包）

使用：

```bash
adb logcat | grep DNS_FLOW
```

重点看：

- 是否成功拉到 OSS
- 是否成功解析 TXT
- 是否成功选中最终落地域名

---

## 步骤 9：打 Release 包验证

```bash
./gradlew clean
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release 验证目标：

- 不闪退
- 不输出敏感业务日志
- 能从 OSS 或 DNS TXT 正常进入页面

---

# 7. Python 配置工具使用说明

如果你使用 `config_tool.py` 或同类脚本，建议支持以下能力：

- 生成密钥
- 生成 OSS 配置
- 生成 DNS TXT 配置
- 本地解密和验签

---

## 7.1 安装依赖

```bash
pip install cryptography
```

---

## 7.2 生成 RSA 密钥对

```bash
python config_tool.py gen-keys --out-dir keys
```

输出：

- `keys/private.pem`
- `keys/public.pem`

终端也可以输出 Base64 公钥，供 `CryptoManager.kt` 使用。

---

## 7.3 生成 OSS 配置

示例：

```bash
python config_tool.py gen-oss \
  --aes-key-b64 YOUR_AES_KEY_B64 \
  --private-key keys/private.pem \
  --version 3 \
  --expire-hours 72 \
  --out config.json
```

---

## 7.4 生成 DNS TXT 配置

示例：

```bash
python config_tool.py gen-dns \
  --aes-key-b64 YOUR_AES_KEY_B64 \
  --private-key keys/private.pem \
  --backup-config-url https://cdn.example.com/config.json \
  --expire-hours 72
```

---

## 7.5 本地解密验证

```bash
python config_tool.py decrypt \
  --aes-key-b64 YOUR_AES_KEY_B64 \
  --public-key keys/public.pem \
  --in-file config.json
```

---

# 8. 什么时候需要重新打包 APK

| 变更内容 | 是否需要重新打包 | 说明 |
|---|---:|---|
| 只换 api / h5 域名 | 否 | 更新远端 config 即可 |
| 只更新 OSS 上 config.json | 否 | 客户端逻辑不变 |
| 只更新 DNS TXT 内容 | 否 | 客户端逻辑不变 |
| 修改 `OSS_URLS` | 是 | 需要改 `RemoteConfigRepository.kt` |
| 修改 `DNS_TXT_DOMAINS` | 是 | 需要改 `RemoteConfigRepository.kt` |
| 修改 DNS 解析策略 | 是 | 需要改 `DnsTxtResolver.kt` |
| 修改 AES / HMAC / RSA 参数 | 是 | 需要改 `CryptoManager.kt` |
| 修改 Gson 解析逻辑 | 是 | 需要改 `MainActivity.kt` |
| 修改日志输出逻辑 | 是 | 需要改 `LogUtil.kt` / 混淆规则 |
| 修改混淆规则 | 是 | 需要改 `proguard-rules.pro` |
| 修改 Release 构建配置 | 是 | 需要改 `app/build.gradle.kts` |

---

# 9. 当前工程已经踩过并修复的问题

---

## 9.1 DNS TXT 实际已走，但解析失败

原因：

- TXT 内容被转义成 `{\"ts\":...}` 形式

修复文件：

```text
app/src/main/java/com/obs/yl/RemoteConfigRepository.kt
```

修复方式：

- 增加 `expandDnsTxtCandidates`
- 自动去引号
- 自动反转义
- 自动尝试多候选解析

---

## 9.2 某些 TXT 域名在部分 DNS 下无结果

原因：

- 系统 DNS / 本地 DNS 污染或无记录

修复文件：

```text
app/src/main/java/com/obs/yl/DnsTxtResolver.kt
```

修复方式：

- 优先走 `8.8.8.8`
- 再走 `1.1.1.1`
- 再走 `9.9.9.9`
- 最后走系统 DNS

---

## 9.3 Release 包因为 `TypeToken` 崩溃

原因：

- `TypeToken<List<DomainItem>>() {}` 在 R8 混淆后丢失泛型信息

修复文件：

```text
app/src/main/java/com/obs/yl/MainActivity.kt
```

修复方式：

- 改为 `Array<DomainItem>::class.java` 解析

---

## 9.4 Release 包 R8 报 dnsjava 缺类

原因：

- `dnsjava` 内含桌面 / JVM 环境相关类

修复文件：

```text
app/proguard-rules.pro
app/build.gradle.kts
```

修复方式：

- `-dontwarn`
- `packaging.resources.excludes`

---

## 9.5 BuildConfig 无法识别

原因：

- `buildConfig` 没开

修复文件：

```text
app/build.gradle.kts
```

修复方式：

```kotlin
buildFeatures {
    buildConfig = true
}
```

---

# 10. Release 包稳定配置（最终版）

---

## 10.1 `app/build.gradle.kts`

当前必须保留：

```kotlin
android {
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/services/java.net.spi.InetAddressResolverProvider",
                "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
            )
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

---

## 10.2 `app/proguard-rules.pro`

当前建议最终版：

```proguard
-keepattributes Signature
-keepattributes *Annotation*

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.obs.bili.EncryptedEnvelope { *; }
-keep class com.obs.bili.DomainItem { *; }
-keep class com.obs.bili.RemoteConfig { *; }
-keep class com.obs.bili.RemoteConfigData { *; }
-keep class com.obs.bili.DnsTxtPayload { *; }
-keep class com.obs.bili.LaunchPlan { *; }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.xbill.DNS.spi.**
-dontwarn sun.net.spi.nameservice.**
```

---

# 11. 当前日志方案（最终版）

---

## 11.1 `LogUtil.kt`

文件路径：

```text
app/src/main/java/com/obs/yl/LogUtil.kt
```

要求：

- 业务代码全部统一调用 `LogUtil`
- 只有 `LogUtil.kt` 可导入 `android.util.Log`
- 业务代码中不再直接使用 `Log.e/d/i/w`

---

## 11.2 检查命令

```bash
grep -R "import android.util.Log" app/src/main/java
grep -R "Log\.[ediw](" app/src/main/java/com/obs/yl
```

最终预期：

- 只有 `LogUtil.kt` 还包含 `import android.util.Log`
- 业务代码没有直接 `Log.e/d/i/w`

---

# 12. 常见问题排查

---

## OSS 能打开但 App 不生效

优先检查：

- AES key 是否正确
- 签名是否匹配
- `expire_at` 是否已过期
- 版本号是否被旧缓存覆盖

重点文件：

- `CryptoManager.kt`
- `RemoteConfigRepository.kt`

---

## DNS TXT 查到了但仍然失败

优先检查：

- TXT 是否被 DNS 平台拆段
- TXT 是否被额外加引号
- TXT 内容是否完整
- 是否已被当前代码支持的格式覆盖

重点文件：

- `DnsTxtResolver.kt`
- `RemoteConfigRepository.kt`

---

## Release 闪退

优先检查：

- `MainActivity.kt` 是否还在用 `TypeToken`
- `proguard-rules.pro` 是否缺少 Gson 签名保留
- `build.gradle.kts` 是否开启 `buildConfig`

---

## 总是走缓存

说明：

- OSS 和 DNS TXT 都没成功

重点看：

```bash
adb logcat | grep DNS_FLOW
```

---

## DNS 第一个域名失败，第二个成功

这种情况当前是正常行为。  
当前工程已经支持：

- 第一个 TXT 域名失败自动跳过
- 第二个 TXT 域名成功后继续使用

---

# 13. 最终验证命令

---

## Debug 包

```bash
./gradlew clean
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep DNS_FLOW
```

---

## Release 包

```bash
./gradlew clean
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

# 14. 最终效果

当前工程已经验证通过：

- OSS 中落地域名全部失败时，会自动切 DNS TXT
- 多个 TXT 域名支持按顺序依次尝试
- TXT 支持标准 JSON / 转义 JSON / 纯文本列表
- 第一个 TXT 域名失败时会自动切换第二个
- Release 混淆后不会因 Gson / TypeToken 闪退
- Release 可通过 R8 构建
- 敏感业务日志已隐藏

---

# 15. 后续建议

1. 建议至少准备 2~4 个 TXT 域名
2. TXT 优先放极简备用配置，不要塞满大 JSON
3. 尽量只改远端配置，减少重新打包
4. 正式验证一定使用 Release 包
5. 定期轮换备用域名与配置地址

---

# 16. 附录

当前交付建议保留：

- 本手册 `README.md`
- 远端配置生成脚本 `config_tool.py`
- 密钥生成脚本
- 原始操作文档 `双入口配置操作手册_当前工程版.docx`
