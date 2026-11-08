# 健身管家 / Fitness Butler

一个**自用的**安卓减脂 & 健身记录 App。把「吃、练、称重、睡觉」四件事串成一条线，尽量不让你手敲。

- **拍一张饭菜照片** → AI 自动认出菜名、估算克重和热量，直接入账
- **跟 AI 说一句话** → 「把中午的红烧肉改成 200 克」，它自己去改账本
- **站上体脂秤** → 蓝牙直连，体重体脂自动进来
- **戴上手表** → 睡眠、心率、血氧、压力自动同步
- **AI 点评** → 嘴毒 / 鸡汤 / 跪求 / 自定义四种风格，看得见地损你

> 不是商业产品，是个人项目公开分享。**没有云端、没有账号、没有服务器**，记录数据只存在你自己的手机本地数据库里（系统自动备份已关闭 `allowBackup=false`，不会被同步到云备份）。

---

## ⚠️ 先看这个：硬件依赖

这个 App **不是装完就能用的通用软件**，有三样依赖：

| 依赖 | 具体要求 | 缺了会怎样 |
|---|---|---|
| 手机 | Android 8.0+（API 26+） | 装不上 |
| 体脂秤 | **蚂蚁阿福智能体脂秤 A1**（支付宝「蚂蚁阿福」活动中领取的型号，蓝牙名 `AFU-WL` 开头；价格以官方活动为准） | 连秤、自动记录体重用不了 |
| 手表 | **iQOO WATCH GT** | 睡眠/心率/血氧/压力同步用不了 |
| 手机品牌 | **vivo / iQOO**（读手表数据必需） | 其他品牌手机：手动记账、拍照识别、体脂秤、锻炼计划正常，**手表数据同步不可用** |

**为什么只有 vivo / iQOO？** 手表数据走的是 `com.vivo.health.widget.permission`——一个 `signature|privileged` 级系统权限。iQOO 是 vivo 家的，同一套系统所以能通；换小米/华为/OPPO，这套权限根本不存在，读不到数据。

---

## 下载安装

| 你在哪 | 去哪下 |
|---|---|
| 国内 | **百度网盘**（链接见小红书笔记 / 联系作者）—— 含 APK + 《零基础上手说明》PDF |
| 国外 | 本仓库 **[Releases](https://github.com/xingxing68876/fitness-butler/releases)** 页下载 `fitness-butler-v0.1.0.apk` |

详细图文上手教程（含如何申请 DeepSeek API Key）只放在百度网盘，本仓库不重复维护。

> ⚠️ **关于安装包**：目前发布的是 **debug 版 APK**（个人项目，未申请正式发布签名）。
> debug 版可被 `adb` 调试，**不要装到共用/二手机器上**；如需长期使用建议自行 clone 后构建 release 版。
> 由于使用通用 debug 签名，理论上第三方可伪造同签名升级包——只从本仓库 Releases 或作者提供的网盘链接下载。

---

## 功能

### 首页 · 记账
- 拍照记账：拍饭菜，AI 识别菜品 + 估算克重/热量/三大营养素
- 相册多选、批量识别、后台继续跑（切走 App 也不中断）
- 拍照时同步存一份到系统相册 `Pictures/FitnessButler`
- AI 对话改账本：说人话增删改当天的账，未提到的菜自动保留
- 对话压缩 / 开新对话（超长上下文自动摘要）

### 日历 · 趋势
- 月度汇总，按天看热量缺口
- 9 项指标折线图（其中「训练负荷」视 vivo 机型可能长期为空），X 轴短日期、按住拖动连续选日
- 当日详情：三餐明细、逐次称重记录、心率/血氧/压力分时段曲线

### 锻炼 · 打卡
- 内置训练计划模板，AI 可定制
- 22 个动作的 3D 演示动画（正/侧视角、倍速、镜像）+ 中文语音讲要领
- 打卡完成后生成分享图

### 设置
- 称呼、性别、身高、年龄（算基础代谢用）
- 四种 AI 风格：嘴毒 / 鸡汤 / 跪求 / 自定义
- AI 大脑接线（OpenAI 兼容端点）+ 视觉识别引擎（DeepSeek / 豆包，界面可切）
- 每日提醒：记账午间 / 记账晚间 / 锻炼打卡，三个时间可调可单独关
- 头像：本人 + AI 四风格，可换相册图

### 桌面小组件 ×3
今日运动清单 / 一键拍照 / 一键称重

---

## 配置 AI

App 里有两处要用 API Key，**都要填**，可以共用同一个 Key：

**① 设置 → AI 大脑接线**（对话用）

| 字段 | 值 |
|---|---|
| API 地址 | `https://api.deepseek.com/v1` |
| 模型 | `deepseek-chat` |
| API Key | 你的 `sk-` 开头的 Key |

**② 设置 → 视觉识别**（拍照识别用）

引擎选 `DeepSeek`，填同一个 Key，模型 ID 留空即用内置默认。

> Key 只存手机本地数据库，直接连服务商官方端点，不经过任何中间服务器。

如何申请 Key：上述百度网盘 PDF 有分步截图（注册 → 充值 → 创建 Key → 复制 → 填入）。

---

## 自行构建

**环境**：JDK 17 + Android SDK（compileSdk 35）

```bash
git clone https://github.com/xingxing68876/fitness-butler.git
cd fitness-butler
# 在项目根目录建 local.properties，写 SDK 路径：
#   sdk.dir=/path/to/Android/Sdk   （Windows 用反斜杠转义：sdk.dir=C\:\\Android\\Sdk）
./gradlew assembleDebug
```

> 若提示 `Permission denied: ./gradlew`，先 `chmod +x gradlew`（Windows 用 `gradlew.bat`）。

产物：`app/build/outputs/apk/debug/app-debug.apk`

**除 Kotlin 标准库外零第三方依赖**：没有 AndroidX、没有 WorkManager、没有 OkHttp、没有 Room。全是系统 API——`SQLiteOpenHelper`、`android.app.Fragment`、`HttpURLConnection`、`AlarmManager`、`Notification.Builder`。APK 约 17 MB，其中 14 MB 是动作演示动画帧。

### Gradle 下载超时（国内常见）

`./gradlew` 首次运行要从 `services.gradle.org` 下 Gradle 本体，国内常超时。两个办法：

1. **用本机已装的 Gradle 8.13+** 直接构建，绕过 wrapper：
   ```bash
   gradle assembleDebug
   ```
2. **改用国内镜像**：把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
   `https\://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip`

> AGP 8.13.2 要求 Gradle **8.13 或更高**，wrapper 版本正好踩在最低要求线上，请勿降级。

### vivo / iQOO 装机补充

用睡眠/心率数据，装完需要授 `com.vivo.health.widget.permission`（系统级权限，权限界面里点不到）：

```bash
adb shell pm grant dev.xd.fitnessbutler com.vivo.health.widget.permission
```

**每次覆盖安装（`install -r`）后必须重跑**——vivo 会撤销 shell 授予的特权权限。

---

## 隐私

- 无账号、无登录、无云端、无统计 SDK、无广告
- 所有数据落本机 SQLite，卸载即消失且不可恢复
- 照片与文字仅在你主动使用 AI 功能时，直连**你自己配置的**服务商端点
- 仓库内不含任何密钥；API Key 由用户在 App 内填写，存 `settings` 表

---

## 已知限制

- **手表数据只支持 vivo / iQOO 手机**（系统权限限制，无解）
- 最大摄氧量（VO₂max）、训练负荷拿不到（vivo 未开放）
- **体脂秤只实测过蚂蚁阿福 A1**，其他型号不保证
- AI 识别的热量是估算值，不是精确营养计算
- 22 个动作演示里缺少划船、引体、绳索类等（免费合规的 3D 素材找不到），这些动作显示文字要领
- 每日提醒用非精确闹钟（未申请 `SCHEDULE_EXACT_ALARM`），误差几分钟
- 无数据导出/导入功能

---

## 免责声明

**本软件为个人自用的免费工具，按「现状」提供，不提供任何担保。**

- **不是医疗器械**，未经任何医疗监管认证。记录的所有体重、体脂、热量、心率、睡眠数据仅供参考，**不构成医疗、营养或运动建议**。有疾病、孕期、慢性病情况请咨询执业医师。
- **AI 生成内容为娱乐性文字**（尤其「嘴毒」风格），不构成任何建议。
- 数据准确性不作保证：AI 热量识别为估算值，硬件数据由第三方设备负责。
- 开发者不收集、不上传你的数据；但**卸载或换机将导致数据永久丢失**。
- API Key 由你自行保管，泄露产生的费用自担。
- 第三方 AI 服务费用由你与服务商直接结算，开发者不从中获益。
- 在适用法律允许的最大范围内，开发者不对使用本软件造成的任何损害承担责任。

完整条款见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 许可证

[MIT](LICENSE) © 2026 阿星 (xingxing68876)

## 致谢

- 动作演示动画基于 **OpenGym3D Exercise Pack**（MIT, © 2026 SylvesterAssiamahGK,
  [演示站](https://assiamahs.github.io/opengym3d/)）的 GLB 素材预渲染而成，
  原始许可证见 [third_party/opengym3d/LICENSE](third_party/opengym3d/LICENSE)
- 分享卡片的 6 张背景图（`assets/sharebg/`）为 AI 生成图像，随本项目以 MIT 许可分发
- 感谢所有开源社区的先例

---

## 反馈

装不上、有 BUG、想加功能：开 [Issue](https://github.com/xingxing68876/fitness-butler/issues)。

微信：`15858831458`（备注「健身管家」）
