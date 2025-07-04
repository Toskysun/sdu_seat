# Sdu-Seat

![Build Status](https://github.com/Toskysun/sdu_seat/actions/workflows/build.yml/badge.svg)
[![Release](https://img.shields.io/github/v/release/Toskysun/sdu_seat?include_prereleases)](https://github.com/Toskysun/sdu_seat/releases/latest)
[![License](https://img.shields.io/github/license/Toskysun/sdu_seat)](https://github.com/Toskysun/sdu_seat/blob/main/LICENSE)

山东大学图书馆自动选座脚本

> ⚠️ **重要提示**：由于山东大学图书馆系统使用微信OAuth认证，且Cookie只能在微信公众号环境中获取，本程序需要手动配置Cookie才能正常工作。程序无法自动完成微信登录过程，必须使用手机端微信+抓包工具获取Cookie。


## 功能特点

- 支持多个图书馆和阅览室的座位预约
- 支持自定义预约时间段
- 支持座位筛选规则
- 支持自动重试
- 日志记录功能
- 定时预约功能
- 微信OAuth会话管理
- 自动会话刷新和配置文件同步
- 邮件通知功能

## 系统要求

- Java 24 
- Gradle 8.14.2

## 快速开始

### 环境准备

1. 确保已安装 Java 24 或更高版本
2. 下载最新的发布版本 jar 文件
3. 准备配置文件（参考下方配置说明）

### 认证配置

**重要说明：** 由于山东大学图书馆系统使用微信OAuth认证，且Cookie只能在微信公众号环境中获取，程序需要手动配置Cookie才能正常工作。

#### 步骤1：获取微信会话Cookie

1. **微信公众号中访问图书馆系统**：
   - 在手机微信中打开：`http://seatwx.lib.sdu.edu.cn/`
   - 完成微信OAuth授权登录
   - 确保能看到图书馆主页面（显示已登录状态）

2. **手机端提取会话Cookie**：
   - **重要**：Cookie只能在微信公众号环境中获取，普通浏览器无法获取
   - 在微信中访问图书馆页面后，长按页面空白处
   - 选择"调试" → "vConsole" 或 "检查元素"（如果可用）
   - 或者使用微信开发者工具的调试功能

3. **替代方法 - 使用抓包工具**：
   - 使用手机抓包工具（如Charles、Fiddler、HttpCanary等）
   - 在微信中访问图书馆系统并登录
   - 查看HTTP请求头中的Cookie信息
   - 提取以下Cookie值：
     - `userObj`
     - `school`
     - `dinepo`
     - `user`
     - `connect.sid`

4. **配置会话信息**：
   - 将获取的Cookie值填入配置文件的`wechatSession`部分
   - 确保`wechatSession.autoInject`设置为`true`

#### 步骤2：运行程序

程序支持两种Cookie配置方式：

**方式1：命令行即时输入（推荐）**

```bash
java -jar sdu-seat-2.0.jar [配置文件路径]
```

程序启动时会自动检查微信会话状态：
- 如果会话过期或缺失，会提示输入最新的Cookie
- 只需输入会过期的字段（`user` 和 `connect.sid`）
- 固定字段（`userObj`, `school`, `dinepo`）只需在配置文件中设置一次

**方式2：手动编辑配置文件**

将获取的Cookie值填入配置文件的`wechatSession`部分，然后运行程序。

## 会话管理功能

### Cookie管理

程序具备智能Cookie管理功能：

1. **自动检测**：启动时自动检查会话状态和剩余时间
2. **即时输入**：会话过期时提示输入最新Cookie
3. **智能更新**：只需更新会过期的字段（user、connect.sid）
4. **配置保存**：新Cookie自动保存到配置文件

### 会话数据格式

程序支持以下微信OAuth会话数据：

- `userObj`: 用户信息（包含id、name、card等）
- `school`: 学校配置信息
- `dinepo`: 微信OpenID
- `user`: 用户会话（包含userid、access_token、expire）
- `connect.sid`: 会话ID

### 注意事项

- ⚠️ **手动获取Cookie**：由于微信OAuth安全限制，程序无法自动获取Cookie
- 🚀 **即时输入**：程序启动时会自动检查并提示输入过期的Cookie
- 💾 **自动保存**：输入的Cookie会自动保存到配置文件
- ⏰ **智能检测**：程序会提前检测会话过期（提前2分钟）
- 🔧 **分类管理**：固定字段只需配置一次，过期字段支持即时更新

### 常见问题

**Q: 如何知道Cookie是否有效？**
A: 程序启动时会显示会话剩余时间，如"会话剩余时间: 15 分钟"

**Q: 会话过期了怎么办？**
A: 程序会自动检测并提示输入新的Cookie。只需在微信中重新访问图书馆系统，获取新的user和connect.sid字段即可

**Q: 为什么不能自动登录？**
A: 微信OAuth有严格的安全限制，且Cookie只能在微信公众号环境中获取，普通浏览器无法获取有效的认证信息

**Q: 为什么普通浏览器无法获取Cookie？**
A: 山东大学图书馆系统的微信OAuth认证只在微信公众号环境中有效，普通浏览器访问会被重定向到登录页面

**Q: 命令行输入Cookie有什么优势？**
A:
- 无需每次手动编辑配置文件
- 程序自动检测过期状态
- 只需输入会过期的字段，固定字段保持不变
- 支持多种Cookie格式输入
- 自动保存到配置文件

## 配置说明

配置文件使用JSON格式，默认路径为 `config/config.json`。以下是所有配置项的说明：

### 基础配置（必需）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| userid | 学号（用于标识用户和验证会话） | - | ✅ | "202500001001" |
| deviceId | 设备ID（可选，用于调试） | 默认值 | ❌ | "464ab241e9a1c1e9" |
| area | 选座区域 | - | ✅ | "趵突泉馆-一楼" |
| seats | 预约座位信息，格式为{"区域": ["座位号"]} | {} | ✅ | 见下方示例 |

**座位号格式说明：**
- 支持2位数和3位数座位号格式（如"11"和"011"）
- 程序会自动识别并转换座位号格式
- 配置时可以使用更简洁的2位数格式

**注意：**
- 已移除`passwd`字段，现在使用微信OAuth认证，无需密码
- `deviceId`为可选配置，程序会自动使用默认值

### 预约策略配置（可选）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| filterRule | 座位筛选规则，支持JavaScript脚本 | "" | ❌ | "" |
| only | 是否仅预约指定座位 | false | ❌ | true |
| time | 运行时间，格式为"HH:mm[:ss[.SSS]]" | "06:02" | ❌ | "12:29:57.500" |
| retry | 预约重试次数 | 10 | ❌ | 3 |
| retryInterval | 预约重试间隔（秒） | 2 | ❌ | 1 |
| delta | 预约日期偏移（天） | 0 | ❌ | 1 |
| bookOnce | 是否立即预约一次 | false | ❌ | true |

**说明：**
- `retryInterval`建议设置为1秒以提高预约成功率
- `bookOnce`设置为true时程序会立即执行一次预约而不等待定时



### 微信OAuth会话配置（必需）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例                                  |
|--------|------|--------|----------|-------------------------------------|
| wechatSession.autoInject | 是否自动注入会话数据 | true | ✅ | true                                |
| wechatSession.userObj | URL编码的用户对象数据 | - | ✅ | "%7B%22id%22%3A..."                 |
| wechatSession.school | URL编码的学校配置数据 | - | ✅ | "%7B%22school%22..."                |
| wechatSession.dinepo | 微信OpenID | - | ✅ | "o-k7HjtQmaO4..."                   |
| wechatSession.user | URL编码的用户会话数据 | - | ✅ | "%7B%22userid%22..."                |
| wechatSession.connectSid | 会话ID | - | ✅ | "s%3AnLni-xNY..."                   |

| wechatSession.redirectUri | 自定义微信OAuth回调地址 | - | ❌ | "http://example/wx/callback" |

**重要说明：**
- ⚠️ **必须手动配置**：由于微信OAuth安全限制，必须手动获取并配置会话Cookie
- 🔄 **自动刷新**：配置完成后程序会自动维护会话状态
- 💾 **自动保存**：会话刷新后会自动更新配置文件
- ⏰ **过期监控**：程序会实时监控会话状态并提前刷新
- 📝 **获取方法**：参考上方"认证配置"部分的详细步骤

### 邮件通知配置（可选）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| emailNotification.enable | 是否启用邮件通知 | false | ❌ | true |
| emailNotification.smtpHost | SMTP服务器地址 | - | 启用时必需 | "smtp.qq.com" |
| emailNotification.smtpPort | SMTP端口 | 465 | ❌ | 465 |
| emailNotification.username | 发件人邮箱 | - | 启用时必需 | "your-email@qq.com" |
| emailNotification.password | 发件人密码（授权码） | - | 启用时必需 | "your-auth-code" |
| emailNotification.recipientEmail | 收件人邮箱 | - | 启用时必需 | "recipient@example.com" |
| emailNotification.sslEnable | 是否启用SSL | true | ❌ | true |

### 配置示例

#### 基础配置模板（需要填入实际Cookie数据）

```json
{
  "userid": "202500001001",
  "area": "趵突泉馆-一楼",
  "seats": {
    "趵突泉阅览室112": ["21","20","11","12","18","19","13","06","07","08","10","14","05","09"],
    "趵突泉专业阅览室104": ["11","08","14"]
  },
  "filterRule": "",
  "only": false,
  "time": "06:02",
  "retry": 10,
  "retryInterval": 1,
  "delta": 0,
  "bookOnce": false,
  "wechatSession": {
    "autoInject": true,
    "userObj": "请填入从浏览器获取的userObj Cookie值",
    "school": "请填入从浏览器获取的school Cookie值",
    "dinepo": "请填入从浏览器获取的dinepo Cookie值",
    "user": "请填入从浏览器获取的user Cookie值",
    "connectSid": "请填入从浏览器获取的connect.sid Cookie值",
    "redirectUri": ""
  },
  "emailNotification": {
    "enable": false,
    "smtpHost": "smtp.qq.com",
    "smtpPort": 465,
    "username": "your-email@qq.com",
    "password": "your-auth-code",
    "recipientEmail": "recipient@example.com",
    "sslEnable": true
  }
}
```

**注意：** 上述示例中的Cookie值仅为演示，请使用您自己从浏览器获取的实际Cookie数据。

## 座位过滤规则

过滤规则使用JavaScript脚本，用于筛选符合条件的座位。

### 过滤规则说明

- 运行脚本时将会传入两个参数：
  - `seats`：座位列表，类型为`List<SeatBean>`，用于过滤座位
  - `utils`：用于脚本打印日志等的工具，如：`utils.log()`
- 过滤完成后需要返回结果，返回类型须是JS中的Array或者Java中的List/Array，其元素类型须是SeatBean

### 过滤规则示例

```javascript
// 过滤靠近门口和打印机的座位
seats.filter(it => {
    return !["001", "002", "003", "004", "005", "006"].includes(it.name)
})

// 过滤指定区域的座位
seats.filter(it => {
    utils.log("正在过滤座位: " + it.name)
    // 只选择靠窗的座位
    return it.name.endsWith("1") || it.name.endsWith("8")
})
```

### 过滤规则文件

您可以将过滤规则保存在一个JavaScript文件中（例如`filter.js`），然后在配置中指定该文件路径：

```json
{
  "filterRule": "/path/to/filter.js"
}
```

或者直接在配置中使用内联JavaScript（以`@js:`开头）：

```json
{
  "filterRule": "@js:seats.filter(it => !['001', '002'].includes(it.name))"
}
```


## 开发说明

### 构建项目
```bash
./gradlew clean build
```

### 生成可执行jar
```bash
./gradlew shadowJar
```

### 运行测试
```bash
./gradlew test
```

## 注意事项

### 使用规范
1. 请勿频繁预约和取消座位
2. 使用本程序需要遵守图书馆相关规定
3. 建议将运行时间设置在图书馆开放预约的时间点
4. 建议设置`retryInterval`为1秒以提高预约成功率

### 认证相关
5. **必须手动配置Cookie**：Cookie只能在微信公众号环境中获取，需要使用抓包工具
6. **仅支持手机端**：普通浏览器无法获取有效的微信OAuth Cookie
7. **定期更新Cookie**：Cookie有过期时间，需要定期更新
8. **保护隐私信息**：请勿在公共场所或代码仓库中泄露Cookie信息
9. **会话自动管理**：配置完成后程序会自动维护会话状态

### 技术要求
10. 需要Java 24或更高版本
11. 需要抓包工具（如HttpCanary、Charles等）
12. 建议使用最新版本的程序以获得最佳体验

## 许可证

本项目基于 GNU General Public License v3.0 开源，详见 [LICENSE](LICENSE) 文件。

## 感谢

- [fengyuecanzhu/Sdu-Seat](https://github.com/fengyuecanzhu/Sdu-Seat) - 感谢该项目提供的思路和参考