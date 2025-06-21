# Sdu-Seat

![Build Status](https://github.com/Toskysun/sdu_seat/actions/workflows/build.yml/badge.svg)
[![Release](https://img.shields.io/github/v/release/Toskysun/sdu_seat?include_prereleases)](https://github.com/Toskysun/sdu_seat/releases/latest)
[![License](https://img.shields.io/github/license/Toskysun/sdu_seat)](https://github.com/Toskysun/sdu_seat/blob/main/LICENSE)

山东大学图书馆自动选座脚本

## 功能特点

- 支持多个图书馆和阅览室的座位预约
- 支持自定义预约时间段
- 支持座位筛选规则
- 支持自动重试
- 支持WebVPN访问
- 日志记录功能
- 定时预约功能
- 可配置提前登录时间和最大尝试次数
- 邮件通知功能

## 系统要求

- Java 17 或更高版本
- Gradle 8.0 或更高版本（如果需要从源码构建）

## 快速开始

1. 下载最新的发布版本 jar 文件
2. 准备配置文件（参考下方配置说明）
3. 运行程序：
   ```bash
   java -jar sdu-seat-1.0.jar [配置文件路径]
   ```

## 配置说明

配置文件使用JSON格式，默认路径为 `config/config.json`。以下是所有配置项的说明：

### 基础配置（必需）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| userid | 学号 | - | ✅ | "202500001001" |
| passwd | 密码 | - | ✅ | "Chsya9" |
| deviceId | 设备ID（获取方法见下方说明） | - | ✅ | "464ab241e9a1c1e9" |
| area | 选座区域 | - | ✅ | "趵突泉馆-一楼" |
| seats | 预约座位信息，格式为{"区域": ["座位号"]} | {} | ✅ | 见下方示例 |

### 预约策略配置（可选）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| filterRule | 座位筛选规则，支持JavaScript脚本 | "" | ❌ | "" |
| only | 是否仅预约指定座位 | false | ❌ | true |
| time | 运行时间，格式为"HH:mm[:ss[.SSS]]" | "06:02" | ❌ | "12:29:57.500" |
| period | 预约时间段，格式为"HH:mm-HH:mm" | "08:00-22:30" | ❌ | "08:00-22:30" |
| retry | 预约重试次数 | 10 | ❌ | 3 |
| retryInterval | 预约重试间隔（秒） | 2 | ❌ | 3 |
| delta | 预约日期偏移（天） | 0 | ❌ | 1 |
| bookOnce | 是否立即预约一次 | false | ❌ | false |

### 登录配置（可选）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| webVpn | 是否使用WebVPN | false | ❌ | false |
| earlyLoginMinutes | 提前登录的分钟数 | 5 | ❌ | 5 |
| maxLoginAttempts | 最大登录尝试次数 | 50 | ❌ | 50 |
| enableEarlyLogin | 是否启用提前登录功能 | true | ❌ | true |

### 邮件通知配置（可选）

| 配置项 | 说明 | 默认值 | 是否必需 | 示例 |
|--------|------|--------|----------|------|
| enable | 是否启用邮件通知 | false | ❌ | true |
| smtpHost | SMTP服务器地址 | - | 启用时必需 | "smtp.qq.com" |
| smtpPort | SMTP端口 | 465 | ❌ | 465 |
| username | 发件人邮箱 | - | 启用时必需 | "12345678@qq.com" |
| password | 发件人密码（授权码） | - | 启用时必需 | "66666666" |
| recipientEmail | 收件人邮箱 | - | 启用时必需 | "sss@163.com" |
| sslEnable | 是否启用SSL | true | ❌ | true |

### 配置示例

```json
{
  "userid": "202500001001",
  "passwd": "Chsya9",
  "deviceId": "464ab241e9a1c1e9",
  "area": "趵突泉馆-一楼",
  "seats": {
    "趵突泉阅览室112": ["11","12","13"],
    "趵突泉专业阅览室104": ["11","08","14"]
  },
  "filterRule": "",
  "only": true,
  "time": "12:29:57.500",
  "period": "08:00-22:30",
  "retry": 3,
  "retryInterval": 3,
  "delta": 1,
  "bookOnce": false,
  "webVpn": false,
  "earlyLoginMinutes": 5,
  "maxLoginAttempts": 50,
  "enableEarlyLogin": true,
  "emailNotification": {
    "enable": true,
    "smtpHost": "smtp.qq.com",
    "smtpPort": 465,
    "username": "12345678@qq.com",
    "password": "66666666",
    "recipientEmail": "sss@163.com",
    "sslEnable": true
  }
}
```

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

## 获取设备ID

设备ID是登录必需的参数，可以通过以下步骤获取：

1. **打开信息化门户登录界面**
   - 如果无需二次认证能够直接登录，转到第三步
   - 否则转到第二步

2. **二次认证**
   - 在二次认证对话框中选择"信任此设备"
   - 然后获取验证码进行登录

3. **退出登录**
   - 成功登录后直接退出登录
   - 或者关闭浏览器后再次使用之前的浏览器打开信息化门户登录界面

4. **获取设备ID**
   - 在登录界面按`F12`，选择"控制台"
   - 复制下面的代码粘贴到控制台，然后回车
   - 然后就会输出设备ID

```javascript
Fingerprint2.get(function(components){
    var details_s = "";
    for(var index in components){
        var obj = components[index];
        if(obj.key=='deviceMemory'||obj.key=='screenResolution'||obj.key=='availableScreenResolution'){
            continue;
        };
        var line = obj.key+" = "+String(obj.value).substr(0, 100);
        details_s+=line+"\n";
    };
    console.log(hex_md5(details_s));
})
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

1. 请勿频繁预约和取消座位
2. 使用本程序需要遵守图书馆相关规定
3. 建议将运行时间设置在图书馆开放预约的时间点
4. 建议设置提前登录时间，提高预约成功率

## 问题反馈

如果遇到问题，请提供以下信息：

1. 程序版本
2. Java版本
3. 操作系统信息
4. 错误日志（位于logs目录）
5. 问题描述

## 许可证

本项目基于 GNU General Public License v3.0 开源，详见 [LICENSE](LICENSE) 文件。

## 感谢

- [fengyuecanzhu/Sdu-Seat](https://github.com/fengyuecanzhu/Sdu-Seat) - 感谢该项目提供的思路和参考