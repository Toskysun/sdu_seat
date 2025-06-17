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
- 可配置提前登录时间（默认5分钟）和最大尝试次数（默认50次），登录成功后立即停止

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

在 `config/config.json` 中配置：

```json
{
  "userid": "202500001001",        // 学号
  "passwd": "password",            // 密码
  "deviceId": "device_id",         // 设备ID
  "area": "威海馆-主楼(3-10)",     // 选座区域
  "seats": {                       // 座位信息
    "三楼阅览室": ["001", "011"],
    "四楼阅览室": ["011", "012"]
  },
  "filterRule": "",               // 座位筛选规则
  "only": false,                  // 是否仅预约指定座位
  "time": "12:32:00",            // 运行时间
  "period": "08:00-22:30",       // 预约时间段
  "retry": 10,                   // 重试次数
  "retryInterval": 30,           // 重试间隔（秒）
  "delta": 0,                    // 预约日期偏移
  "bookOnce": false,             // 是否只预约一次
  "webVpn": false,               // 是否使用WebVPN
  "maxLoginAttempts": 50,        // 最大登录尝试次数
  "earlyLoginMinutes": 5,        // 提前登录的分钟数
  "emailNotification": {          // 邮件通知配置
    "enable": false,              // 是否启用邮件通知
    "smtpHost": "",              // SMTP服务器地址
    "smtpPort": 465,             // SMTP端口
    "username": "",              // 发件人邮箱
    "password": "",              // 发件人密码（授权码）
    "recipientEmail": "",        // 收件人邮箱
    "sslEnable": true            // 是否启用SSL
  }
}
```

## 座位过滤规则

过滤规则为JavaScript脚本，用于筛选符合条件的座位。

### 过滤规则说明

- 运行脚本时将会传入两个参数：
  - `seats`：座位列表，类型为`List<SeatBean>`，用于过滤座位
  - `utils`：用于脚本打印日志等的工具，如：`utils.log()`
- 过滤完成后需要返回结果，返回类型须是JS中的Array或者Java中的List/Array，其元素类型须是SeatBean

### 过滤规则示例

```javascript
// 过滤靠近门口和打印机的座位
seats.filter(it => {
    // 避开靠近门口和打印机的座位
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