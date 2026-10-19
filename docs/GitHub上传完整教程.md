# 把 ScreenTranslator 上传到 GitHub —— 零基础完整教程

> 适用项目：`F:\WorkSpace\screen-translator`（Android 原生 Kotlin + Compose）
> 阅读方式：从头顺序做，每一步都给了「**做什么 → 界面在哪 → 预期看到什么 → 为什么要这么做**」。
> 全程约 40 分钟，其中真正操作 15 分钟，剩下是等安装和读说明。

---

## 开篇结论（先看这五条，能省掉 90% 的坑）

1. **你的项目现在 11 GB，但真正该上传的只有约 1 MB。** 11 GB 里 9.1 GB 是 `.tmp-verify` 里的模型转换中间产物，883 MB 是 `apk/`，570 MB 是 `app/build/`。Git 不是网盘，它只该存「人写的源代码」。
2. **有两个文件必须先处理，否则上传就是事故**：`gradle.properties`（里面有明文签名密码）、`keystore/screentranslator.jks`（签名私钥）。私钥泄露等于别人可以冒充你发布"官方更新"。
3. **必须先建 `.gitignore`，再执行 `git add`。** 顺序反了，`git add .` 会去读那 11 GB，卡几分钟到几十分钟，还可能直接失败。
4. **GitHub 不能给"还没进 Git 的历史"打标签。** 你从没用过 Git，所以 0.1.0～0.9.0 这些"历史版本"没有对应的提交可以标记。正确做法是**写进 `CHANGELOG.md` 存档**，而不是硬造标签（第五章详细讲，含一个反例陷阱）。
5. **APK 不要传进代码库**，用 GitHub 的 **Releases** 功能发。这样代码历史和安装包互不干扰，仓库不会越滚越大。

---

## 目录

- [第一章　先弄懂 Git 在干什么（3 分钟）](#第一章先弄懂-git-在干什么3-分钟)
- [第二章　准备工作：装 Git、注册账号、建远程仓库](#第二章准备工作装-git注册账号建远程仓库)
- [第三章　上传前清理：哪些能传、哪些绝对不能传](#第三章上传前清理哪些能传哪些绝对不能传)
- [第四章　第一次提交与推送](#第四章第一次提交与推送)
- [第五章　版本管理：v1.0.0 与 0.x 测试版怎么定](#第五章版本管理v100-与-0x-测试版怎么定)
- [第六章　日常开发流程（以后每天照做）](#第六章日常开发流程以后每天照做)
- [第七章　长期复用的判断原则](#第七章长期复用的判断原则)
- [附录](#附录)

---

# 第一章　先弄懂 Git 在干什么（3 分钟）

## 1.1 一句话定义

**Git 是一个"给文件夹拍快照"的工具。** 你每执行一次提交（commit），它就把当时所有被跟踪的文件状态存成一张快照。以后任何时候都能回到某张快照。

它**不负责**：同步文件（那是网盘）、备份整个硬盘、存构建产物。

## 1.2 三个区域，理解这个就理解了一半

```
你的文件夹（工作区）        暂存区（购物车）           本地仓库            远程仓库
  app/src/**.kt    ──add──▶  已挑选的改动   ──commit──▶  历史快照  ──push──▶  GitHub
      │                          │                        │                    │
   你正在改的代码            准备提交的那部分          存在自己电脑上        存在网上
```

- **工作区**：你在 Android Studio / 编辑器里看到、改动的文件。
- **暂存区**（staging area / index）：一个"购物车"。`git add` 是把改动放进购物车，`git commit` 是结账。**这个中间层的存在，让你可以只提交一部分改动**——比如你同时改了功能和加了一堆调试日志，可以只把功能提交上去。
- **本地仓库**（`.git` 文件夹）：提交后的历史都存在这里。**注意：`git commit` 只是存到本地，还没上网。**
- **远程仓库**（GitHub）：`git push` 才真正传上去。

**新手最常见的误解**：以为 `commit` 就上传了。不是。`commit` 存本地，`push` 才上云。

## 1.3 为什么非要这么麻烦

因为**源码和成品的价值完全不同**：

| | 源码 | 构建产物（APK / build 目录） |
|---|---|---|
| 体积 | 829 KB | 1.4 GB |
| 能否重新生成 | 不能，这是唯一的一份 | 能，`gradle assembleRelease` 一条命令 |
| 改动差异 | 改一行就是一行 | 每次构建二进制全变，无法比较 |
| 价值 | **全部价值在这里** | 零，随时可再生 |

Git 的核心设计就是**存差异、存文本、存人写的东西**。把二进制产物塞进去，会让仓库膨胀、无法 diff、克隆变慢，而且没有任何收益。

---

# 第二章　准备工作：装 Git、注册账号、建远程仓库

## 2.1 安装 Git（Windows）

### 做什么

1. 打开浏览器，访问 **https://git-scm.com/download/win**
2. 页面会自动识别 Windows，点击 **"Click here to download"**（64-bit Git for Windows Setup）
3. 下载完成后双击 `Git-2.xx.x-64-bit.exe`

### 安装向导里只需要改这几个选项

安装向导有十几页，**绝大多数保持默认，一路 Next**。只有下面三页值得留意：

| 向导页面（英文标题） | 选什么 | 为什么 |
|---|---|---|
| **Choosing the default editor used by Git** | 选 **"Use Notepad as Git's default editor"**（或你已装的 VS Code） | 默认是 Vim，零基础用户进去后**不知道怎么退出**（要按 `Esc` 再输入 `:wq`）。选记事本能救命 |
| **Adjusting the name of the initial branch** | 选 **"Override the default branch name for new repositories"**，框里填 `main` | GitHub 现在默认主分支叫 `main`，而老版 Git 默认叫 `master`。这里先设好，后面省掉一条改名命令 |
| **Configuring the line ending conversions** | 选 **"Checkout Windows-style, commit Unix-style line endings"** | Windows 换行是 `CRLF`，Linux/Mac 是 `LF`。这个选项让 Git 提交时统一转成 `LF`，避免整个文件"看起来全改了"的假差异 |

其余页面（Select Components、Adjusting your PATH、HTTPS transport backend、Symbolic links 等）**全部直接 Next**。

### 预期结果

安装完成后，按 `Win` 键，输入 `git bash`，回车，会打开一个黑底窗口（**Git Bash**，本教程全程用它）。

输入：

```bash
git --version
```

**预期输出**（版本号可能不同）：

```
git version 2.47.0.windows.1
```

> **为什么用 Git Bash 而不是 cmd/PowerShell？**
> 网上 99% 的 Git 教程都是 Bash 语法。用 Git Bash 你能直接照抄。而且它的路径写法是 `/f/WorkSpace/screen-translator`，比 Windows 的反斜杠少踩坑。

---

## 2.2 告诉 Git 你是谁（一次性的，但必须做）

### 做什么

在 Git Bash 里逐行执行：

```bash
git config --global user.name "Saltin"
git config --global user.email "你的GitHub注册邮箱@example.com"
```

### 预期结果

没有输出（Linux 哲学：没消息就是好消息）。验证：

```bash
git config --global --list
```

**预期看到**类似：

```
user.name=Saltin
user.email=xxx@example.com
core.autocrlf=true
```

### 为什么必须做

Git 的每一次提交都会**永久记下作者名和邮箱**，写进历史，改不了（改要重写历史）。以后你在 GitHub 上看到的每一次提交、每一个头像，都来自这两行配置。

**邮箱必须和你 GitHub 账号的邮箱一致**，否则提交在 GitHub 上不会关联到你的头像，看起来像别人提交的。

> **隐私提示**：如果你不想公开真实邮箱，可在 GitHub 的 `Settings → Emails` 里勾选 **"Keep my email addresses private"**，它会给你一个 `12345678+用户名@users.noreply.github.com` 的专用地址，用那个填 `user.email` 即可。

### 顺手再配两行（强烈建议）

```bash
# 中文文件名不要显示成 "\344\270\255\346\226\207" 这种八进制转义
git config --global core.quotepath false
```

**为什么**：你的项目路径里有中文用户名（`C:\Users\闫涵`），不设这个，`git status` 里中文会显示成乱码，无法辨认。

---

## 2.3 注册 GitHub 账号

### 做什么

1. 打开 **https://github.com/signup**
2. 依次填写：
   - **Email**：常用邮箱（后面要收验证码）
   - **Password**：至少 15 个字符，或至少 8 位且含数字和小写字母
   - **Username**：英文/数字/连字符，这会出现在你的仓库地址里，比如 `saltin`
   - 通过"验证拼图"（Verify your account）
3. 点 **Create account**
4. 去邮箱收 **8 位验证码**，填进页面

### 预期结果

进入 GitHub 首页。右上角出现你的头像。

> 如果官网打不开（国内网络常见），可以：
> - 换用手机热点试一次；
> - 或者长期方案是给 `github.com` 配 hosts / 用加速器。
> **不要**因此去用非官方的镜像站注册，账号安全没有保障。

---

## 2.4 创建远程仓库（GitHub 上的那个"空盒子"）

### 做什么

1. 登录 GitHub，看**页面右上角**，有一个 **`+`** 号按钮
2. 点它，在下拉菜单里选 **"New repository"**
3. 在新页面填写：

| 字段 | 填什么 | 说明 |
|---|---|---|
| **Owner** | 你的用户名（默认已选） | 仓库归属 |
| **Repository name** | `screen-translator` | 全小写、用连字符。这个名字会成为网址的一部分 |
| **Description** | `Android 屏幕实时翻译：OCR + 大模型 + 悬浮窗原位覆盖` | 可选，但建议填，别人一眼知道是什么 |
| **Public / Private** | 二选一，见下方说明 | |
| **Add a README file** | **不要勾** | ⚠️ 关键 |
| **Add .gitignore** | **不要勾** | ⚠️ 关键 |
| **Choose a license** | **不要勾**（个人项目可暂不选） | ⚠️ 关键 |

4. 点绿色按钮 **"Create repository"**

### 关于 Public 还是 Private

| 选项 | 谁能看 | 适合 |
|---|---|---|
| **Public** | 所有人 | 想当作品集、简历项目、开源 |
| **Private** | 只有你（可邀请协作者） | 私人项目、还在开发中 |

**免费账号两者都无限量**。可以随时在 `Settings → General → 最下方 Danger Zone → Change visibility` 里切换。**建议先选 Private**，等确认没有敏感信息、想展示时再转 Public。

### ⚠️ 为什么那三个勾必须不勾

因为你的**本地项目里已经有 README.md 和马上要建 .gitignore 了**。

如果 GitHub 帮你生成一个 README，远程仓库就**不是空的**，它会有一个初始提交。此时你本地推送，两边的历史"没有共同祖先"，Git 会直接拒绝：

```
! [rejected] main -> main (fetch first)
error: failed to push some refs to ...
hint: Updates were rejected because the remote contains work that you do not have locally.
```

新手最容易卡在这一步。**让远程仓库保持完全空白**，推送才是一路绿灯。

### 预期结果

创建成功后，页面会跳到一个标题是 **"Quick setup — if you've done this kind of thing before"** 的页面。

页面上有一段命令，类似：

```bash
git remote add origin https://github.com/你的用户名/screen-translator.git
git branch -M main
git push -u origin main
```

**先把这个页面留着别关**——第四章要用到这段命令。记下你的仓库地址：`https://github.com/你的用户名/screen-translator.git`

---

## 2.5 准备推送凭证（Personal Access Token）

### 为什么需要

**2021 年 8 月 13 日起，GitHub 不再接受账号密码推送代码。** 你必须用一个"专用令牌"代替密码。这一步是零基础用户最大的拦路虎。

### 做什么

1. 登录 GitHub，点**右上角你的头像** → 下拉菜单最下面 **"Settings"**
2. 在 Settings 页面，**左侧栏拉到最底部**，点 **"Developer settings"**
3. 在新页面左侧栏，点 **"Personal access tokens"** → **"Tokens (classic)"**
4. 右上角点 **"Generate new token"** → 选 **"Generate new token (classic)"**
   - 可能会要求你再输一次密码
5. 填写：

| 字段 | 填什么 |
|---|---|
| **Note** | `screen-translator 推送` （只是备注，方便以后识别） |
| **Expiration** | 选 `90 days`（安全）或 `No expiration`（省事） |
| **Select scopes** | **只勾 `repo` 这一个大类**（它会连带勾选下面 5 个子项） |

6. 拉到页面最底部，点绿色 **"Generate token"**

### 预期结果

页面顶部出现一串以 `ghp_` 开头的字符串，类似：

```
ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### ⚠️ 三个必须知道的事

1. **这串字符只显示这一次。** 刷新页面就再也看不到了。**立刻复制到记事本存好。**
2. **它等同于你的密码**，拿到它就能改你所有仓库。**不要发给任何人、不要粘到聊天工具、不要写进代码。**
3. 如果泄露了：回到同一个页面，找到这个 token，点 **Delete**，然后重新生成一个。**删掉即刻失效。**

---

# 第三章　上传前清理：哪些能传、哪些绝对不能传

> 这一章是整份教程最重要的部分。**先把这一章做完，再碰 `git add`。**

## 3.1 先看清你的项目里到底有什么

这是你的项目实测体积分布（2026-10-19 统计）：

| 路径 | 体积 | 是什么 | 传不传 |
|---|---:|---|---|
| `.tmp-verify/` | **9.1 GB** | ONNX 模型转换中间产物、上传副本、验证脚本 | ❌ |
| `apk/` | **883 MB** | 打包好的安装包 + 历史版本 | ❌ |
| `app/build/` | **570 MB** | Gradle 构建产物（含中间 class、资源、R8 产物） | ❌ |
| `.gradle/` | 7.4 MB | Gradle 缓存与文件索引 | ❌ |
| `build/` | 128 KB | 根工程构建输出 | ❌ |
| `README.md` | 88 KB | 项目说明文档 | ✅ |
| `gradle/` | 49 KB | 版本目录 + wrapper（含 `gradle-wrapper.jar`） | ✅ |
| `.kotlin/` | 21 KB | Kotlin 编译会话缓存 | ❌ |
| `docs/` | 8 KB | 技术文档 | ✅ |
| `keystore/` | 4 KB | **签名私钥 `.jks`** | ❌ **绝不能传** |
| `app/src/` | 829 KB | 67 个源码文件 | ✅ **核心** |
| `gradle.properties` | 1 KB | 含**明文签名密码** | ❌ **改造后再说** |
| `local.properties` | 1 KB | 本机 SDK 路径 | ❌ |
| `gradlew` / `gradlew.bat` | 13 KB | 构建启动脚本 | ✅ |

**结论：11 GB → 约 1 MB。** 你没看错，就是这么夸张。这不是"少传一点"，而是**该传的东西只占 0.01%**。

## 3.2 红线一：`gradle.properties` 里的明文密码

**先看现状**（我已经检查过你的实际文件）：

```properties
# ---- release 签名（keystore/screentranslator.jks）----
st.keyAlias=screentranslator
st.keyStorePass=st2026key      ← 明文密码
st.keyPass=st2026key           ← 明文密码
```

### 为什么这是事故

`gradle.properties` 在 Android 项目里**通常是要提交的**（里面有 `jvmargs`、AndroidX 开关等所有人共用的配置）。但你的这份还额外装了签名口令。

配合 `keystore/screentranslator.jks` 一起泄露的话，**任何人拿到就能用你的签名身份打包 APK**。而 Android 的信任模型是"同签名 = 同应用"，用过你 App 的人能直接覆盖安装恶意版本，系统不会拦。

### 处理方案（推荐方案 A，零改动、零风险）

#### 方案 A：忽略 `gradle.properties`，另提供脱敏模板（**已为你做好**）

我已在项目里创建了 `gradle.properties.example`——内容与原来一模一样，**只把三项口令留空**。同时 `.gitignore` 里已加入 `gradle.properties`。

- **你的电脑上**：`gradle.properties` 原样保留，构建照常，什么都不用改。
- **上传到 GitHub 的**：只有 `gradle.properties.example`，不含任何口令。
- **别人 clone 后**：执行 `cp gradle.properties.example gradle.properties`，填自己的值。

代价：**新机器拉代码后要多做一次复制**。教程会在 `README` 里注明（见 5.6 的 README 片段）。

#### 方案 B：把密码挪到环境变量（更规范，但要改构建脚本）

需要改 `app/build.gradle.kts`，让它从 `System.getenv("ST_KEYSTORE_PASS")` 读。适合要接 CI 自动打包的场景。**现在不用做**，等你以后配 GitHub Actions 自动构建时再升级。

> ⚠️ **注意**：`keystore/` 目录已在 `.gitignore` 里。但如果你以后把 keystore 挪到别的路径，记得同步更新忽略规则——`.jks` / `.keystore` / `.p12` 的扩展名规则已经兜底了。

## 3.3 红线二：`keystore/screentranslator.jks`

这是你的**签名私钥**（2264 字节）。它是全项目最危险的文件：

| 它泄露了会怎样 | 后果 |
|---|---|
| 别人用你的签名打包 | 可冒充官方更新，覆盖安装你的 App |
| 你丢了这个文件 | **无法再给现有用户发更新**（签名不同，系统拒绝覆盖安装，只能卸载重装，用户全丢） |

**所以它要备份，但要用"离线"的方式备份**：U 盘、加密压缩包、密码管理器附件。**绝不能进 Git 仓库**，哪怕仓库是 Private——因为你以后可能转 Public，或者邀请协作者，或者某次误操作。

`.gitignore` 已有 `/keystore/` 和 `*.jks` 双重保险。

## 3.4 `.gitignore` 是什么（原理，理解了就不用背清单）

### 定义

项目根目录下一个纯文本文件，每行一条规则，告诉 Git：**"这些路径假装不存在"**。

### 三条关键规则

**① 只对"未跟踪"的文件生效**

`.gitignore` 管的是"要不要开始跟踪"。如果一个文件**已经被提交过了**，之后再写进 `.gitignore` 也**不会**让它从仓库消失——Git 会继续跟踪它。要取消跟踪必须：

```bash
git rm --cached 文件路径
```

这是新手第二大困惑："我明明写了忽略，怎么还在？"——因为顺序反了。

**② 匹配语法**

| 写法 | 含义 | 例子 |
|---|---|---|
| `build/` | 任意层级的 `build` 目录 | 匹配 `build/`、`app/build/`、`x/y/build/` |
| `/build/` | **仅根目录**的 `build` | 只匹配项目根下的 `build/` |
| `*.apk` | 任意位置、任意层级的 apk 文件 | `apk/a.apk`、`out/b.apk` |
| `*.log` | 所有日志 | |
| `!important.log` | **例外**：不要忽略这个 | 前面有规律、后面开个口子 |
| `# 注释` | 注释行 | |
| `空行` | 忽略 | 用于分组 |

**③ `/` 开头与不开口的区别，会咬人**

比如写 `models/` 会匹配**任何层级**叫 models 的目录。如果哪天你的源码里出现 `app/src/main/java/.../models/`（放数据模型的包），就会被一起忽略——**本地一切正常，别人 clone 后直接编译失败**。

**本项目已按这个教训处理**：所有目录规则都用 `/` 开头精确锚定根目录。

## 3.5 本项目的完整忽略清单（逐条说明为什么）

我已经把下面这份清单**写进 `F:\WorkSpace\screen-translator\.gitignore`** 了，你不需要手抄。这里逐条解释理由：

### ① 构建产物

```gitignore
/build/
/app/build/
*.apk
*.aab
*.ap_
*.dex
*.class
*.idsig
*.hprof
captures/
.cxx/
```

| 规则 | 为什么 |
|---|---|
| `/build/`、`/app/build/` | Gradle 每次构建重写，合计 570 MB。`assembleRelease` 一条命令就能全量再生 |
| `*.apk` | 二进制安装包，走 Releases 发布（见 5.5） |
| `*.aab` | Google Play 上传格式，同样是产物 |
| `*.idsig` | `apksigner` 的签名附属文件，跟 APK 同生共灭 |
| `*.dex`、`*.class` | 编译产物，源码就在这里，没必要存中间态 |
| `*.hprof` | 内存快照，动辄几百 MB，只在排查 OOM 时临时产生 |
| `captures/` | Android Studio 抓的调试截图/录屏 |
| `.cxx/` | NDK 原生编译中间目录 |

### ② Gradle / Kotlin 缓存

```gitignore
/.gradle/
/.kotlin/
local.properties
```

| 规则 | 为什么 |
|---|---|
| `/.gradle/` | 依赖缓存、文件哈希索引。**每台机器都不一样**，提交了会互相覆盖 |
| `/.kotlin/` | Kotlin 编译会话状态，纯缓存 |
| `local.properties` | 里面是 `sdk.dir=F:/WorkSpace/android-sdk`。**这是你本机的绝对路径**，别人电脑上 SDK 在别处，提交了对方构建直接报错。Android 官方标准做法就是忽略它 |

### ③ 签名与密钥（最危险）

```gitignore
/keystore/
*.jks
*.keystore
*.p12
*.pfx
*.pem
*.key
```

| 规则 | 为什么 |
|---|---|
| `/keystore/` | 你的签名库就在这 |
| `*.jks` / `*.keystore` | Java 密钥库标准格式 |
| `*.p12` / `*.pfx` | 同上的另一种容器格式 |
| `*.pem` / `*.key` | 私钥文本格式（SSH 密钥、证书私钥） |

**这一组没有"看在体积小就传上去"的余地。** 4 KB 的私钥比 570 MB 的构建目录危险一万倍。

### ④ 含密码的本地配置

```gitignore
gradle.properties
```

配合同目录的 `gradle.properties.example`（脱敏模板，这个**要传**）。

### ⑤ 本地临时与验证产物

```gitignore
/.tmp-verify/
/.tmp-*/
tmp/
*.log
*.tmp
*.bak
*.orig
```

| 规则 | 为什么 |
|---|---|
| `/.tmp-verify/` | 9.1 GB 的模型转换中间产物（ONNX 全量导出、量化副本、上传暂存）。这是你本机的一次性工作区，别人不需要，也不需要能复现 |
| `/.tmp-*/` | 泛指这类临时目录，防止以后又建 `.tmp-build`、`.tmp-check` 时忘记忽略 |
| `*.log` | 运行日志，含时间戳和本机路径，没有归档价值 |
| `*.bak`、`*.orig` | 编辑器/合并工具留下的备份，属于"手抖残留" |

### ⑥ IDE / 编辑器

```gitignore
.idea/
*.iml
*.ipr
*.iws
.vscode/
*.swp
*~
```

| 规则 | 为什么 |
|---|---|
| `.idea/` | Android Studio 的**个人工作区状态**：窗口布局、打开的文件、断点、历史剪贴板。**每个人都不一样**，提交后天天冲突 |
| `*.iml` | IntelliJ 模块文件，由 Gradle 自动生成 |
| `.vscode/` | VS Code 的同类配置 |
| `*.swp`、`*~` | Vim 临时文件 |

> **注意一个例外**：`.idea/` 里有些文件（如 `codeStyles/`）理论上可共享。但对个人项目，**整体忽略最省事**，不必纠结。

### ⑦ 操作系统文件

```gitignore
.DS_Store
Thumbs.db
desktop.ini
```

| 规则 | 为什么 |
|---|---|
| `.DS_Store` | macOS 访达自动生成的图标位置缓存。你以后如果用 Mac 打开这个项目就会冒出来 |
| `Thumbs.db` | Windows 缩略图缓存 |
| `desktop.ini` | Windows 文件夹自定义配置（图标、显示名） |

这三个的共同点：**系统自动生成、与你写的代码无关、每台机器都不同**。

### ⑧ 本地模型文件

```gitignore
/models/
*.onnx
*.tflite
*.spm
```

| 规则 | 为什么 |
|---|---|
| `*.onnx` | 你的 tc-big 模型单文件 100～170 MB，**超过 GitHub 单文件 100 MB 硬限制会直接拒绝推送**。而且它是"下载物"不是"源文件"——App 运行时从 HF 拉取 |
| `*.tflite` | ML Kit / TFLite 模型，同理 |
| `*.spm` | SentencePiece 分词模型，本项目在 HF 托管 |
| `/models/` | 兜住以后可能建的本地模型目录 |

### ⑨ 打包交付物

```gitignore
/apk/
/release/
```

**为什么不传 APK**：GitHub 对超过 100 MB 的单文件直接拒绝；而 APK 每次构建都是全新的二进制，Git 无法做增量存储，**每个版本都会把完整 51 MB 再存一遍**。发 10 个版本就是 500 MB 仓库，克隆一次要等很久。

正确做法是 **Releases**（见 5.5）：安装包单独存放，不占仓库体积，还能写更新说明、统计下载量。

## 3.6 落地：确认 `.gitignore` 已就位

### 现状

**我已经在 `F:\WorkSpace\screen-translator\.gitignore` 建好了**，内容就是 3.5 的完整清单。同时建好了脱敏模板 `gradle.properties.example`。

### 你要做的：检查两件事

**① 确认编码是 UTF-8（不含 BOM）**

在 Git Bash 里执行：

```bash
cd /f/WorkSpace/screen-translator
file .gitignore
```

**预期输出**：

```
.gitignore: ASCII text
```

如果显示 `UTF-8 Unicode (with BOM) text`，说明有 BOM。用 VS Code 打开 → 右下角点编码 → **"Save with Encoding" → "UTF-8"**（不是 UTF-8 with BOM）。

**为什么**：Git 能容忍 BOM，但第一行规则会带上不可见字符而**静默失效**——你以为是 `build/` 生效了，其实没有。这是极难排查的坑。

**② 确认关键路径确实会被忽略**

```bash
cd /f/WorkSpace/screen-translator
git init          # 先初始化（第四章会详细讲，这里只是为了让下面的检查命令可用）
git status --short | head -20
```

**预期结果**：输出的文件列表里**不应该**出现 `app/build/`、`apk/`、`.tmp-verify/`、`keystore/`、`gradle.properties`。

如果出现了，说明规则没生效，先别继续，检查 `.gitignore` 文件名（**注意开头有个点**）和位置（**必须在项目根目录**）。

---

# 第四章　第一次提交与推送

> 从这里开始，所有命令都在 **Git Bash** 里执行。

## 4.1 初始化本地仓库

### 做什么

```bash
cd /f/WorkSpace/screen-translator
git init
```

### 预期结果

```
Initialized empty Git repository in F:/WorkSpace/screen-translator/.git/
```

执行后，项目里多了一个隐藏文件夹 `.git`。**这里面是全部历史，不要手动动它，也不要删。**

### 为什么

`git init` 只是"声明这个文件夹归 Git 管"，它**没有**开始跟踪任何文件，也**没有**上传任何东西。此时项目还是原来的项目，只是多了个记账的本子。

> **注意**：如果你在第 3.6 步已经执行过 `git init`，这里会提示 `Reinitialized existing Git repository`，**这是正常的，不是错误**。

## 4.2 看清 Git 眼里有什么

### 做什么

```bash
git status
```

### 预期结果（会比较长）

```
On branch master

No commits yet

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	.gitignore
	README.md
	app/
	build.gradle.kts
	docs/
	gradle.properties.example
	gradle/
	gradlew
	gradlew.bat
	settings.gradle.kts

nothing added to commit but untracked files present (use "git add" to track)
```

### 怎么读这个输出（这是最该学会的一步）

| 部分 | 含义 |
|---|---|
| `On branch master` | 当前在 master 分支。等一下要改成 main |
| `No commits yet` | 一个提交都还没有 |
| `Untracked files:` | **Git 看见了但没管的文件**。红色/未跟踪 |
| `nothing added to commit` | 暂存区是空的 |

### 关键自检点 ⚠️

**请逐行核对这个列表**。它应该**只有**：源码目录、构建脚本、文档、`.gitignore`、`gradle.properties.example`。

**绝对不能出现**：

- ❌ `apk/`
- ❌ `app/build/`
- ❌ `.tmp-verify/`
- ❌ `keystore/`
- ❌ `gradle.properties`
- ❌ `local.properties`

出现了就停下，回到 3.6 检查 `.gitignore`。**这一步花 30 秒，能省掉后面半小时的补救。**

> **为什么列表里没有 `build/` 和 `.gradle/`？** 因为 `.gitignore` 生效了，Git 假装它们不存在。这正是我们想要的效果。

## 4.3 把文件放进"购物车"（暂存）

### 做什么

```bash
git add .
```

> 注意 `add` 和 `.` 之间有个空格。`.` 表示"当前目录下所有未忽略的改动"。

### 预期结果

**没有任何输出**（这是正常的）。验证：

```bash
git status --short
```

**预期看到**（第一列是 `A` = Added，绿色）：

```
A  .gitignore
A  README.md
A  app/build.gradle.kts
A  app/proguard-rules.pro
A  app/src/main/AndroidManifest.xml
A  app/src/main/java/com/saltin/screentranslator/...
...
A  gradle/wrapper/gradle-wrapper.jar
...
```

### 再核对一次（重要）

```bash
git status --short | wc -l
```

**预期结果**：大约 **75～80** 行（67 个源码文件 + 若干配置和文档）。

如果这个数字是 **几百甚至上万**，说明 `.gitignore` 没生效，`git add` 把构建产物也加进去了。**立刻撤销**：

```bash
git rm -r --cached .
# 确认 .gitignore 正确后，重新 add
git add .
```

### 为什么要有"暂存"这一步

因为它给了你一次**检查机会**。直接 `commit` 就没有回头路了（虽然能撤销，但麻烦）。

真实场景：你改了三处代码 + 加了五处调试 `Log.d`。你可以 `git add` 只挑那三处，把调试日志留在工作区——这样提交历史是干净的。零基础阶段用 `git add .` 没问题，但机制要懂。

## 4.4 第一次提交

### 做什么

```bash
git commit -m "chore: 初始化项目，导入 ScreenTranslator v1.0.0 完整源码"
```

### 预期结果

```
[master (root-commit) a1b2c3d] chore: 初始化项目，导入 ScreenTranslator v1.0.0 完整源码
 78 files changed, 12345 insertions(+)
 create mode 100644 .gitignore
 create mode 100644 README.md
 ...
```

几个关键信息：
- `root-commit` = 这是第一个提交，没有父提交
- `a1b2c3d` = 提交的短哈希，**你的会和这个不同**
- `78 files changed` = 本次提交包含 78 个文件（数字以实际为准）

### 提交信息怎么写

格式：`类型: 一句话说明`

| 类型 | 用于 | 本项目例子 |
|---|---|---|
| `feat` | 新功能 | `feat: 支持横屏模式下译文重新排版` |
| `fix` | 修 bug | `fix: 混合模式截图被拒时自动降级纯文本` |
| `refactor` | 重构，不改行为 | `refactor: 拆出 DualVocabSentencePiece` |
| `docs` | 只改文档 | `docs: 补充 tc-big 模型上传指南` |
| `chore` | 杂务、依赖、配置 | `chore: 升级 Compose BOM` |
| `perf` | 性能优化 | `perf: OCR 取帧改用 ColorMatrix 避免读像素` |

**好与坏对比**：

| ❌ 别这么写 | ✅ 应该这么写 |
|---|---|
| `update` | `fix: 修复 402 审核拒绝导致整页不翻译` |
| `改了一下` | `feat: 设置页新增 OCR 对比度增强开关` |
| `111` | `docs: 更新 README 到 v1.0.0` |
| 一次提交 30 个不相关改动 | 拆成 3 个提交，每个说清一件事 |

**为什么**：三个月后你只会用 `git log` 找"那次修 402 的改动在哪"，不会记得 `update` 是哪次。

> ⚠️ **中文提交信息在 Windows 上的坑**：如果提交后 `git log` 显示乱码，执行
> `git config --global i18n.commitEncoding utf-8` 和 `git config --global i18n.logOutputEncoding utf-8`。

## 4.5 把主分支改名为 main

### 做什么

```bash
git branch -M main
```

### 预期结果

无输出。验证：

```bash
git branch
```

**预期**：

```
* main
```

（星号表示当前分支）

### 为什么

老版 Git 默认叫 `master`，GitHub 现在默认叫 `main`。两边名字不一致会导致推送后**多出一个分支**，你还得手动切默认分支。

如果你在 2.1 安装时已经选了 "Override the default branch name → main"，这一步可以跳过（执行也无害，因为已经是 main 了）。

## 4.6 关联远程仓库

### 做什么

从 2.4 那个 "Quick setup" 页面复制命令，或者手动执行（把用户名换成你的）：

```bash
git remote add origin https://github.com/你的用户名/screen-translator.git
```

### 预期结果

无输出。验证：

```bash
git remote -v
```

**预期**：

```
origin  https://github.com/你的用户名/screen-translator.git (fetch)
origin  https://github.com/你的用户名/screen-translator.git (push)
```

### 为什么叫 origin

`origin` 只是**远程仓库的默认别名**，就像给电话号码起个名字叫"家"。你也可以叫 `github`、`backup`，但 `origin` 是全网惯例，照做即可。

`fetch` 和 `push` 两行说明：这个别名同时用于下载和上传。

> **如果提示 `remote origin already exists`**：说明之前加过了。改成：
> ```bash
> git remote set-url origin https://github.com/你的用户名/screen-translator.git
> ```

## 4.7 推送（真正上传）

### 做什么

```bash
git push -u origin main
```

### 预期结果（第一次会弹认证）

会弹出一个窗口，标题类似 **"Git Credential Manager"** 或 **"Sign in to GitHub"**：

```
Username for 'https://github.com': 你的GitHub用户名
Password for 'https://你的用户名@github.com': ← 这里粘贴 ghp_ 开头的 token
```

⚠️ **Password 处粘贴的是 Token，不是账号密码。** 而且**粘贴时屏幕不显示任何字符**（连星号都没有），这是正常的安全设计，直接 `Ctrl+V` 然后回车即可。

成功后输出：

```
Enumerating objects: 82, done.
Counting objects: 100% (82/82), done.
Delta compression using up to 12 threads
Compressing objects: 100% (65/65), done.
Writing objects: 100% (82/82), 412.33 KiB | 2.10 MiB/s, done.
Total 82 (delta 8), reused 0 (delta 0), pack-reused 0
remote: Resolving deltas: 100% (8/8), done.
To https://github.com/你的用户名/screen-translator.git
 * [new branch]      main -> main
branch 'main' set up to track 'origin/main'.
```

**注意 `412.33 KiB` 这个数字**——这就是"11 GB 里该传的部分"。如果这里显示几百 MB 甚至 GB，说明忽略规则有问题，立刻停下去检查。

### `-u` 是什么意思

`-u` = `--set-upstream`，把本地 `main` 和远程 `origin/main` **绑定**起来。

绑定之后，以后你只需要敲 `git push`，Git 就知道往哪推、推哪个分支，不用每次都写 `origin main`。**只需要在第一次推送时加 `-u`。**

### 为什么只弹一次认证窗口

Windows 会把凭证存进 **"凭据管理器"**。以后推送自动用，不再问。

想更换账号：`Win` 键 → 搜"凭据管理器" → **Windows 凭据** → 找到 `git:https://github.com` → **删除**。下次推送会重新问。

## 4.8 验收：去 GitHub 页面上确认

### 做什么

刷新你的仓库页面 `https://github.com/你的用户名/screen-translator`

### 预期结果（逐项核对）

| 检查项 | 应该看到 |
|---|---|
| 顶部 | 仓库名 `screen-translator` + 你的用户名 |
| 文件列表 | `.gitignore`、`README.md`、`app/`、`gradle/`、`gradlew`、`gradle.properties.example`、`settings.gradle.kts` 等 |
| 提交记录 | 右侧 **"Commits"** 显示 `1 Commit` |
| 分支 | 左上角分支下拉显示 **`main`** |
| 体积 | 文件列表上方**不应**出现大文件警告 |

**点进 `app/` → `src/` → `main/` → `java/` → `com/saltin/screentranslator/`**，应该能看到 60 多个 `.kt` 文件。点任意一个，能看到带语法高亮的源码。**这说明上传成功了。**

### 三个"应该看不到"（重要）

- ❌ 没有 `apk/` 文件夹
- ❌ 没有 `app/build/`
- ❌ 没有 `keystore/`、`gradle.properties`、`local.properties`

如果看到了任何一个，立刻执行本文 7.6 的"误传补救"。

---

# 第五章　版本管理：v1.0.0 与 0.x 测试版怎么定

## 5.1 先接受一个事实：你没法给"历史"打标签

你的要求是：**当前完整版 = v1.0.0，之前那些 = 从 0.1.0 开始的测试版。**

但有一个绕不过去的物理限制：

> **Git 的标签（tag）必须挂在某个提交（commit）上。** 而你在今天之前**从没用过 Git**，那些历史版本在你的硬盘上**根本不存在对应的代码快照**——每次改完就直接覆盖了，只有一个最终状态。

所以：

| 你想要的 | 能不能做到 |
|---|---|
| 给当前版本打 `v1.0.0` 标签 | ✅ 能，第四章的提交就是 |
| 给"v1.4 三窗口重构"打 `v0.5.0` 标签并让它指向当时的代码 | ❌ **不能**，当时的代码已经不存在了 |
| 在文档里把过去 13 次迭代记录成 `0.1.0 ～ 0.9.0` | ✅ 能，而且**这才是正确做法** |

**我会给你一套"既满足语义、又不伪造历史"的方案。**

## 5.2 推荐方案：文档化 0.x + 标签化 1.0.0

### 版本映射表（照这张表写 CHANGELOG）

把过去的内部版本号重新编号为 0.x 预发布序列：

| 新的版本号 | 对应当时的内部版本 | 主要内容 | 性质 |
|---|---|---|---|
| `0.1.0` | v1.0 | 四阶段流水线跑通（抓屏→OCR→翻译→覆盖） | 原型 |
| `0.2.0` | v1.1 | 三窗口架构重构、多模型接入、内置教程 | 内测 |
| `0.3.0` | v1.2 | 修离线翻译乱码 | 内测 |
| `0.4.0` | v1.3 | OCR 行→段合并、译文折行渲染 | 内测 |
| `0.4.1` | v1.3.1 | 提示条三层分级 | 内测 |
| `0.5.0` | v1.4 | 多模态无译文块兜底、竖排日文支持 | 内测 |
| `0.5.1` | v1.4.5 | 气泡框合并、长文分页 | 内测 |
| `0.6.0` | v1.5 | 横屏支持、译文校验、端侧引擎三档 | 公测 |
| `0.6.1` | v1.5.1 | 修横屏手动死锁、下载校验基准 | 公测 |
| `0.7.0` | v1.6.0 | 修横屏 resize 异常、NLLB 起始符 | 公测 |
| `0.8.0` | v1.6.1 | 修「译文==原文」静默失败、移除 NLLB 大模型 | 公测 |
| `0.9.0` | v1.6.2 | 自转 tc-big 中→日直译模型、HF 托管 | 公测 |
| **`1.0.0`** | **v1.6.3** | **截图被拒自动降级 + OCR 对比度增强，功能完整** | **正式版** |

**为什么这么映射**：`0.1 → 0.9` 有 9 个 minor 位，正好覆盖从原型到公测的 9 个阶段；`1.0.0` 作为"第一个功能完整、可对外交付"的版本，语义准确。

### 这个方案的三个好处

1. **语义真实**：0.x 就是"未完成"，1.0 就是"第一个完整版"，完全符合语义化版本规范。
2. **不伪造历史**：CHANGELOG 里会明确写"以下版本早于 Git 引入，无对应标签"，任何人看了都清楚。
3. **可验证**：`apk/old_versions/` 里那些旧 APK 就是物证。

## 5.3 给当前版本打 `v1.0.0` 标签

### 先确认你在正确的提交上

```bash
cd /f/WorkSpace/screen-translator
git log --oneline
```

**预期**：

```
a1b2c3d (HEAD -> main, origin/main) chore: 初始化项目，导入 ScreenTranslator v1.0.0 完整源码
```

### 打标签（用附注标签，不要用轻量标签）

```bash
git tag -a v1.0.0 -m "首个正式版：屏幕实时翻译功能完整

- 云端/端侧双路径：ML Kit、Opus-MT 三档离线引擎、多家大模型 API
- 混合模式截图被拒时自动降级纯文本，不再整页不翻
- OCR 前灰度 + 对比度增强，改善彩色文字识别
- 中→日 tc-big 直译模型，其余方向英语接力"
```

### 两种标签的区别（这条要知道）

| 类型 | 命令 | 存了什么 | 用哪个 |
|---|---|---|---|
| **轻量标签** lightweight | `git tag v1.0.0` | 只是一个指向提交的指针 | 临时标记、本地随手记 |
| **附注标签** annotated | `git tag -a v1.0.0 -m "..."` | 指针 + 打标签的人 + 时间 + 说明，**是一个独立对象** | **发版一律用这个** |

**为什么发版要用附注标签**：因为它记录了"谁在什么时候、为什么发这一版"。`git show v1.0.0` 能看到完整信息。GitHub 的 Release 也要求基于附注标签才能正常展示说明。

### 验证

```bash
git tag -n5
```

**预期**：

```
v1.0.0          首个正式版：屏幕实时翻译功能完整...
```

## 5.4 推送标签到 GitHub

### ⚠️ 关键认知：`git push` 不会推标签

这是一个非常容易漏的点。**标签必须单独推**，否则 GitHub 上什么都看不到。

### 做什么

```bash
# 推送这一个标签
git push origin v1.0.0

# 或者：推送所有本地标签（推荐，省事）
git push --tags
```

### 预期结果

```
Total 0 (delta 0), reused 0 (delta 0), pack-reused 0
To https://github.com/你的用户名/screen-translator.git
 * [new tag]         v1.0.0 -> v1.0.0
```

### 为什么 `push` 默认不带标签

因为标签是**人为的里程碑**，Git 不想在你每次日常推送时自动把一堆实验性标签也发上去。这是刻意的设计。

> **以后每次打标签，记得跟一条 `git push --tags`。** 忘了的话，本地有、GitHub 没有，你会在 Releases 页面找不到它而抓狂。

## 5.5 创建 GitHub Release（发布说明 + 挂 APK）

标签只是代码上的一个书签。**Release 是给人看的"发布页面"**，可以写更新说明、上传安装包、统计下载量。

### 做什么

1. 打开仓库页面，看**右侧边栏**，找到 **"Releases"**（在 "About" 区块下面）
2. 点右侧的 **"Create a new release"**
3. 填写：

| 字段 | 填什么 |
|---|---|
| **Choose a tag** | 下拉选 **`v1.0.0`**（刚才推上去的会出现在这里） |
| **Target** | 保持 `main` 不动 |
| **Release title** | `ScreenTranslator v1.0.0 —— 首个正式版` |
| **Describe this release** | 粘贴下面模板的内容 |
| **Attach binaries** | 把 `apk/ScreenTranslator-v1.6.3-arm64-v8a.apk` 和 `-armeabi-v7a.apk` **拖进虚线框** |
| **Set as the latest release** | 勾上 |

4. 点绿色 **"Publish release"**

### 发布说明模板（可直接用）

```markdown
## 首个正式版

从原型到可日常使用，经历了 12 个预发布版本（0.1.0 ～ 0.9.0，详见 CHANGELOG.md）。

### 核心能力
- **抓屏 → OCR → 翻译 → 原位覆盖**，译文直接盖在原文位置
- 三种工作模式：端侧离线 / 混合（文字为主 + 图辅助）/ 云端多模态
- 端侧离线三档：ML Kit 标准 / Opus-MT 增强直译 / Opus-MT 全能覆盖
- 支持 OpenAI 兼容、Gemini、Anthropic 三类接口

### 本版修复
- 混合模式下截图被服务商拒绝（如内容审核 402）时，自动降级为纯文本重试，**不再整页无译文**
- OCR 识别前增加灰度 + 温和对比度增强，改善彩色文字与花哨背景下的识别率

### 安装
- 绝大多数手机选 `arm64-v8a`（51.4 MB）
- 老设备选 `armeabi-v7a`（36.9 MB）
- 覆盖安装无需卸载（签名一致）

### 已知限制
- 端侧直译目前仅 **中→日** 为真直译，其余方向走英语接力
- 部分文字系统（西里尔、阿拉伯、泰文）ML Kit 无端侧识别器
```

### 预期结果

页面跳转到 Release 详情页，标题下方显示 `v1.0.0` 标签徽章，下方有两个 APK 可供下载。

**这一步完成了"代码与成品分离"**：源码在仓库里（1 MB），安装包在 Release 里（88 MB），互不干扰。

## 5.6 写 `CHANGELOG.md`（0.x 历史的正式载体）

### 做什么

在项目根目录新建 `CHANGELOG.md`，内容如下（**这是 5.2 映射表的落地，可直接用**）：

```markdown
# 更新日志

本项目的版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

> **关于 0.x 系列**
> `0.1.0` ～ `0.9.0` 属于本项目引入 Git 之前的开发阶段，**没有对应的 Git 提交与标签**，
> 仅以本文件与 `apk/old_versions/` 中的历史安装包为记录。
> 自 `v1.0.0` 起，每个版本都有对应的 Git 标签与 GitHub Release。

## [1.0.0] - 2026-10-19

第一个功能完整的正式版。

### 新增
- OCR 识别前的灰度化与对比度增强（设置项，默认开启），改善彩色文字识别率

### 修复
- 混合模式下附带截图被服务商拒绝（HTTP 402 内容审核等）时，整页文字都拿不到译文。
  现在会立即改用同一批文字发起纯文本请求重试，并进入 60 秒静默期避免反复无效请求
- 纯多模态模式整屏被拒时同样自动降级为「OCR + 纯文本」

---

## [0.9.0] - 2026-10-03
> 对应内部版本 v1.6.2

### 新增
- 本地转换 Helsinki-NLP `opus-mt-tc-big-zh-ja` 为 ONNX 并做两步量化，实现**中→日直译**
- 模型托管至 HuggingFace `SaltinA/screentranslator-models`，App 内按需下载

### 变更
- 离线引擎三档改为按「覆盖范围」递进，增强档与全能档均可独立完成翻译

### 修复
- 模型下载地址拼接错误导致全部 404

---

## [0.8.0] - 2026-09-30
> 对应内部版本 v1.6.1

### 修复
- **「译文等于原文」的静默失败**：模型返回空结果时被当作成功，用户误以为翻译质量差
- 空产出与「译文==原文」现在都计为失败并明确报错，不再回填原文

### 移除
- 870 MB 的 NLLB 离线模型（手机上过慢）

---

## [0.7.0] - 2026-09-27
> 对应内部版本 v1.6.0

### 修复
- 横屏尺寸变化时异常被吞掉
- NLLB 解码起始符错误

---

## [0.6.1] - 2026-09-24
> 对应内部版本 v1.5.1

### 修复
- 横屏模式下手动触发导致的死锁
- 模型下载校验基准由估算值改为 HEAD + manifest

---

## [0.6.0] - 2026-09-20
> 对应内部版本 v1.5

### 新增
- 横屏支持
- 译文质量校验
- 端侧离线引擎三档设计

---

## [0.5.1] - 2026-09-15
> 对应内部版本 v1.4.5

### 新增
- 相邻气泡框合并
- 长文本分页

---

## [0.5.0] - 2026-09-10
> 对应内部版本 v1.4

### 新增
- 多模态模式下无译文区块的兜底处理
- 竖排日文识别

---

## [0.4.1] - 2026-09-05
> 对应内部版本 v1.3.1

### 新增
- 提示条三层分级：致命常驻 / 环境性提醒单次 / 即时反馈带冷却

---

## [0.4.0] - 2026-09-01
> 对应内部版本 v1.3

### 新增
- OCR 行结果合并为段落后再翻译，解决一句话被换行拆断的问题
- 译文按原位宽度自动折行

---

## [0.3.0] - 2026-08-26
> 对应内部版本 v1.2

### 修复
- 离线翻译输出乱码

---

## [0.2.0] - 2026-08-20
> 对应内部版本 v1.1

### 新增
- 三窗口架构重构（悬浮球 / 译文层 / 菜单）
- 多模型接入与内置教程

---

## [0.1.0] - 2026-08-12
> 对应内部版本 v1.0

### 新增
- 首个可运行原型：抓屏 → ML Kit OCR → 大模型翻译 → 悬浮窗原位覆盖
```

### 为什么必须做这一步

1. **这是 0.x 唯一的"存在证明"**。没有 Git 历史，CHANGELOG 就是历史本身。
2. **未来你会感谢自己**。半年后想不起"402 降级是哪版修的"，`Ctrl+F` 就有答案。
3. **GitHub 会渲染它**。`CHANGELOG.md` 在仓库首页可以直接点开看。

### 提交这个文件

```bash
git add CHANGELOG.md
git commit -m "docs: 新增 CHANGELOG，记录 0.1.0 至 1.0.0 的完整版本演进"
git push
```

> 注意这次没有 `-u`，因为 4.7 已经绑定过了。

### 顺手同步 README

你现在的 `README.md` 第 6 行还写着 **"当前版本：v1.6.1"**，实际已经是 v1.6.3 → 现在叫 `v1.0.0`。

用编辑器把那一行改成：

```markdown
当前版本：**v1.0.0**
```

并在"快速开始"里补一段（**因为是方案 A，别人 clone 后必须做这一步**）：

````markdown
### 从源码构建

```bash
git clone https://github.com/你的用户名/screen-translator.git
cd screen-translator

# 1) 还原本地构建配置（仓库里只有脱敏模板）
cp gradle.properties.example gradle.properties

# 2) 指向你自己的 Android SDK
echo "sdk.dir=你的SDK路径" > local.properties

# 3) 构建
./gradlew assembleDebug
```

> release 包需要自备签名库 `keystore/screentranslator.jks`，
> 并在 `gradle.properties` 里填 `st.keyAlias` / `st.keyStorePass` / `st.keyPass`。
> 调试包用 debug 签名即可正常安装。
````

## 5.7 日常发新版的完整节奏

以后每发一版，按这个顺序做（以 v1.0.1 为例）：

```bash
# 1) 改版本号：app/build.gradle.kts 里 versionCode +1，versionName 改成 1.0.1
# 2) 更新 CHANGELOG.md，在顶部加 [1.0.1] 段落
# 3) 提交
git add -A
git commit -m "chore: 发布 v1.0.1"

# 4) 打附注标签
git tag -a v1.0.1 -m "修复 xxx"

# 5) 推送代码和标签（两条都要）
git push
git push --tags

# 6) 到 GitHub Releases 页面点 "Draft a new release"，选 v1.0.1，上传新 APK
```

## 5.8 如果你坚持要给 0.x 打真标签（不推荐）

**先说结论：不建议。** 但把陷阱说清楚，你自己判断。

技术上你能这么做：

```bash
git tag -a v0.1.0 -m "预发布" a1b2c3d   # a1b2c3d 就是那个唯一的初始提交
```

**为什么这是错的**：

- `v0.1.0` 和 `v1.0.0` 会**指向同一个提交**，代码完全一样。这等于宣称"0.1.0 和 1.0.0 是同一天同一份代码"，是**伪造历史**。
- 以后任何人（包括你）查看 `git log`，都会困惑："为什么 0.1 到 0.9 之间的提交全都不见了？"
- GitHub 的 Releases 时间线会出现一堆同一天的发布，看起来很怪。

**什么情况下可以真的重建历史**：如果你**保留着每个版本的完整源码备份**（比如每版都打过 zip），那可以按时间顺序逐个提交、逐个打标签，重建成一条真实的时间线。

**你现在的情况**：`apk/old_versions/` 里只有 APK（二进制），没有源码快照。**所以只能文档化。**

> 💡 **从这次之后开始养成习惯**：每次发版都 `git tag` + `git push --tags`。一年后你就有一条真实的标签历史了。

---

# 第六章　日常开发流程（以后每天照做）

## 6.1 每天开始工作前

```bash
cd /f/WorkSpace/screen-translator
git status
```

**看到 `nothing to commit, working tree clean`** = 上次的改动都提交完了，可以安心开工。

**看到一堆修改** = 上次有没提交的东西，先决定：提交、还是丢弃。

> 单人开发其实可以跳过"拉取"这一步。但如果你**在公司电脑和家里电脑都写代码**，每天开始必须先：
> ```bash
> git pull
> ```
> 否则两边分叉，合并起来很麻烦。

## 6.2 改完代码后的四步

### 第 1 步：看改了什么

```bash
git status
```

**预期**（`M` = Modified，红色表示未暂存）：

```
On branch main
Changes not staged for commit:
	modified:   app/src/main/java/com/saltin/screentranslator/translate/TranslationPipeline.kt
	modified:   app/src/main/java/com/saltin/screentranslator/ui/SettingsScreen.kt

Untracked files:
	app/src/main/java/com/saltin/screentranslator/util/NewHelper.kt
```

### 第 2 步：看具体改了哪几行（**重要习惯**）

```bash
git diff
```

**预期**：逐行显示改动，`-` 开头是删除的，`+` 开头是新增的，红绿高亮。

**为什么这一步不能省**：`git status` 只告诉你"哪个文件变了"，`git diff` 才告诉你"**具体改了什么**"。你会经常在这里发现意外改动——比如调试时临时改的时间戳、忘删的 `Log.d`、手滑删掉的一行。

**按 `q` 退出 diff 查看**（进入的是分页器）。

只看某个文件：

```bash
git diff app/src/main/java/com/saltin/screentranslator/translate/TranslationPipeline.kt
```

### 第 3 步：加入暂存区

```bash
# 全部改动
git add -A

# 或者只加指定文件（推荐，更可控）
git add app/src/main/java/com/saltin/screentranslator/translate/TranslationPipeline.kt
```

> `git add .` 和 `git add -A` 的区别：`.` 只处理当前目录，`-A` 处理整个仓库（包括删除的文件）。**在项目根目录执行时两者等价**，习惯上用 `-A`。

### 第 4 步：提交

```bash
git commit -m "fix: 混合模式带图失败时自动降级纯文本重试"
```

**预期**：

```
[main 3f8a2c1] fix: 混合模式带图失败时自动降级纯文本重试
 2 files changed, 47 insertions(+), 12 deletions(-)
```

### 第 5 步：推送

```bash
git push
```

**预期**：

```
To https://github.com/你的用户名/screen-translator.git
   a1b2c3d..3f8a2c1  main -> main
```

**四个步骤的记忆口诀**：`status`（看）→ `diff`（审）→ `add`（挑）→ `commit`（存）→ `push`（传）。

## 6.3 提交的粒度：一次说清一件事

### ❌ 错误示范

```bash
# 攒了一周，一次提交
git commit -m "改了很多"
```

后果：想回滚其中某个功能时，只能整批回滚。

### ✅ 正确示范

```bash
git commit -m "fix: 截图被拒时降级纯文本"
git commit -m "feat: 设置页新增对比度开关"
git commit -m "docs: 更新教程中的三档说明"
```

**判据**：如果这句话说不清、或者中间有"和"字（"修了 A **和** 加了 B"），就该拆成两个提交。

## 6.4 三种"后悔药"

### 场景一：刚 add 了，还没 commit，想撤出暂存区

```bash
git restore --staged 文件名
```

（改动还在文件里，只是不在购物车里了）

### 场景二：改错了，想丢弃工作区的改动，回到上次提交的样子

```bash
git restore 文件名
```

⚠️ **这会永久丢失你未提交的修改，无法恢复。** 确认后再执行。

### 场景三：已经 commit 了，但还没 push，想改提交信息

```bash
git commit --amend -m "新的提交信息"
```

⚠️ **只能用于还没推送的提交**。已经 push 的要改就得 `--force`，会改写历史，单人项目勉强能用，多人协作绝对禁止。

### 场景四：想撤销最近一次 commit，但保留改动

```bash
git reset --soft HEAD~1
```

（提交没了，改动回到暂存区，可以重新组织再提交）

## 6.5 常用命令速查表

| 我想…… | 命令 |
|---|---|
| 看当前状态 | `git status` |
| 看改了什么内容 | `git diff` |
| 看提交历史（一行一条） | `git log --oneline` |
| 看带图的历史树 | `git log --oneline --graph --all` |
| 看某次提交的详情 | `git show 提交哈希` |
| 看某个文件的历史 | `git log --follow 文件名` |
| 看谁改了某一行 | `git blame 文件名` |
| 加全部改动 | `git add -A` |
| 提交 | `git commit -m "类型: 说明"` |
| 推送 | `git push` |
| 拉取 | `git pull` |
| 打标签 | `git tag -a v1.0.1 -m "说明"` |
| 推标签 | `git push --tags` |
| 看所有标签 | `git tag -n5` |
| 撤销暂存 | `git restore --staged 文件` |
| 丢弃改动 | `git restore 文件` |
| 临时存起改动（切换分支前） | `git stash` / 恢复用 `git stash pop` |
| 查忽略规则为何命中 | `git check-ignore -v 文件路径` |

**最后一条特别有用**：当你不确定某个文件为什么被忽略（或为什么没被忽略）：

```bash
git check-ignore -v app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

**预期输出**：

```
.gitignore:6:/app/build/	app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

它告诉你**命中了第 6 行的哪条规则**。如果没输出，说明这个文件**不被忽略**。

## 6.6 六个高频报错与解法

### ① `Permission denied (publickey)` 或反复弹认证框

**原因**：token 错了、过期了，或凭据管理器存了旧密码。

**解法**：
1. 检查 token 是否过期：GitHub → Settings → Developer settings → Tokens
2. 清掉本地旧凭证：`Win` 键 → "凭据管理器" → Windows 凭据 → 删除 `git:https://github.com`
3. 重新 `git push`，按提示填**用户名** + **新 token**

### ② `Updates were rejected because the remote contains work that you do not have locally`

**原因**：远程有你本地没有的提交（常见于：在 GitHub 网页上直接改了文件，或 2.4 时勾了 "Add a README"）。

**解法**：
```bash
git pull --rebase origin main
git push
```
`--rebase` 会把你本地的提交"挪到"远程提交的后面，保持一条直线，比默认的 merge 干净。

### ③ `src refspec main does not match any`

**原因**：你还在 `master` 分支上，本地没有 `main`。

**解法**：
```bash
git branch -M main
git push -u origin main
```

### ④ `fatal: not a git repository`

**原因**：当前目录不是项目根目录。

**解法**：
```bash
cd /f/WorkSpace/screen-translator
```
用 `pwd` 确认位置。

### ⑤ `File xxx is 128.00 MB; this exceeds GitHub's file size limit of 100.00 MB`

**原因**：有个超过 100 MB 的文件被提交了（大概率是 `*.onnx` 或 APK）。

**解法**（见 7.6，需要清历史）。

### ⑥ 中文文件名显示成 `\344\270\255\346\226\207`

**原因**：Git 默认转义非 ASCII 文件名。

**解法**：
```bash
git config --global core.quotepath false
```

---

# 第七章　长期复用的判断原则

> 这一章的目标：**以后你自己就能判断，不用再问人。**

## 7.1 一句话判据（记住这一句就够 90%）

> **别人 clone 下来、想跑起这个项目，需不需要它？**
> **需要 → 传。不需要 → 不传。**

这一句话能解决绝大部分纠结。下面是对它的展开。

## 7.2 三问法（拿不准时按顺序问）

面对任意一个文件，依次问：

### 问 1：这是"人写的"，还是"机器生成的"？

| 人写的 | 机器生成 |
|---|---|
| `.kt` / `.xml` / `.kts` / `.md` / `.toml` 源码和配置 | `build/`、`*.class`、`*.dex`、`*.apk` |
| 手写的 `.gitignore`、`README` | `.gradle/`、`.kotlin/`、`local.properties` |

**机器生成的 → 一律不传。** 因为它随时能再生，而且每次生成结果不同（时间戳、绝对路径），提交了只会制造噪音。

### 问 2：里面有秘密吗？

看到这些**立刻停手**：

- 密码、口令（`password`、`pass`、`secret`、`token`、`key`）
- 私钥文件（`.jks`、`.keystore`、`.p12`、`.pem`、`.key`）
- API Key（`sk-`、`AIza`、`ghp_`、`hf_` 开头）
- 个人隐私（真实姓名+身份证、手机号、家庭住址）
- 内部地址（内网 IP、公司域名）

**有 → 一律不传，改成环境变量或本地配置文件。**

⚠️ **最容易犯的错**：以为"仓库是 Private 就没事"。**Private 也会**：被转成 Public、被邀请协作者、被 GitHub 员工在工单里看到、被你的 token 泄露后批量下载。**秘密就不该进版本库，这是原则，不是看情况。**

### 问 3：体积大吗？

| 体积 | 判断 |
|---|---|
| < 1 MB | 随便传 |
| 1 ～ 50 MB | 想清楚，确认是必需素材（如小图标、字体）再传 |
| **> 50 MB** | **不要进 Git**。用 Git LFS、Release、网盘、HF 等外部托管 |
| **> 100 MB** | **物理上传不上去**，GitHub 直接拒绝 |

**为什么**：Git 会保存**每一个版本**的完整副本。一个 50 MB 的文件改 10 次，仓库就胖 500 MB，而且**永远删不掉**（历史里还在）。

## 7.3 四象限速查表

|  | **体积小** | **体积大** |
|---|---|---|
| **人写的 / 必需** | ✅ **传**<br>源码、配置、文档、构建脚本 | ⚠️ **外部托管**<br>模型文件、大素材、数据集 → HF / 网盘 / LFS |
| **机器生成 / 非必需** | ❌ **不传**<br>`.class`、`.log`、`.DS_Store` | ❌ **绝对不传**<br>`build/`、`*.apk`、`.tmp-*` |

**记忆法**：只有左上角那一格要传。

## 7.4 本项目 20 个真实文件的判例

| 文件 / 目录 | 判断 | 依据 |
|---|---|---|
| `app/src/main/java/**/*.kt` | ✅ 传 | 核心源码，67 个文件 829 KB |
| `app/src/main/res/**` | ✅ 传 | 布局、字符串、图标，都是手写/手绘 |
| `app/src/main/AndroidManifest.xml` | ✅ 传 | 应用声明，必需 |
| `app/build.gradle.kts` | ✅ 传 | 构建配置，必需 |
| `app/proguard-rules.pro` | ✅ 传 | R8 保留规则（你踩过 ONNX 的坑，这份很重要） |
| `build.gradle.kts` / `settings.gradle.kts` | ✅ 传 | 工程骨架 |
| `gradle/libs.versions.toml` | ✅ 传 | 依赖版本目录，全靠它统一版本 |
| `gradle/wrapper/gradle-wrapper.jar` | ✅ 传 | **虽然 43 KB 二进制，但必需**——没有它别人无法确定 Gradle 版本 |
| `gradle/wrapper/gradle-wrapper.properties` | ✅ 传 | 指定 Gradle 8.13 |
| `gradlew` / `gradlew.bat` | ✅ 传 | 构建入口脚本 |
| `README.md` / `CHANGELOG.md` | ✅ 传 | 项目门面与历史 |
| `docs/tcbig-upload.md` | ✅ 传 | 技术文档，别人复现模型转换时需要 |
| `.gitignore` | ✅ 传 | 规则本身要共享 |
| `gradle.properties.example` | ✅ 传 | 脱敏模板（**这是让方案 A 成立的关键**） |
| `gradle.properties` | ❌ 不传 | **含明文签名密码** |
| `local.properties` | ❌ 不传 | 本机 SDK 绝对路径，别人机器上无效 |
| `keystore/screentranslator.jks` | ❌ **绝对不传** | 签名私钥 = 发布身份 |
| `app/build/` | ❌ 不传 | 570 MB 构建产物 |
| `apk/` | ❌ 不传 | 883 MB 安装包 → 走 Releases |
| `.tmp-verify/` | ❌ 不传 | 9.1 GB 模型转换中间物 |

## 7.5 新增文件时的决策流程（照着走）

```
新建了一个文件
    │
    ├─ 它是我手写的源码/配置/文档吗？
    │      ├─ 是 → 再问：里面有密码/私钥/个人信息吗？
    │      │         ├─ 有 → 不提交，改成环境变量 / 本地文件 + 加进 .gitignore
    │      │         └─ 没有 → 再问：大于 50 MB 吗？
    │      │                    ├─ 是 → 外部托管（HF / Release / 网盘）
    │      │                    └─ 否 → ✅ 提交
    │      └─ 否（是构建/缓存/临时产物）→ ❌ 加进 .gitignore
    │
    └─ 不确定 → 执行 git status 看它是否出现
                 ├─ 出现了但我不知道该不该传 → 问 7.1 那句话
                 └─ 我很确定该忽略但它出现了 → 说明已被跟踪，见 7.6
```

## 7.6 已经误传了怎么办（三种情况）

### 情况 A：刚 `git add` 了，还没 commit

```bash
git restore --staged 文件路径
```

然后把它加进 `.gitignore`。

### 情况 B：已经 commit 了，但还没 push

```bash
# 1) 从仓库移除跟踪，但保留本地文件
git rm --cached 文件路径

# 2) 加进 .gitignore
echo "文件路径" >> .gitignore

# 3) 提交这次移除
git commit -m "chore: 移除误提交的 xxx"

# 4) 如果只是刚刚那次提交，更干净的做法是合并进上一次
git commit --amend --no-edit
```

### 情况 C：已经 push 到 GitHub 了

**这是最麻烦的**，因为历史已经上传。

**如果只是"不想再跟踪"，不介意历史里还有**（适用于密钥已更换的情况）：

```bash
git rm --cached 文件路径
echo "文件路径" >> .gitignore
git add .gitignore
git commit -m "chore: 停止跟踪 xxx"
git push
```

**效果**：文件从当前版本消失，但**旧提交里还在**，别人翻历史仍能看到。所以**如果泄露的是密钥，必须先去更换密钥**，只删文件没用。

**如果要彻底从历史抹掉**（谨慎，会改写所有提交哈希）：

需要 `git filter-repo` 工具（比老的 `filter-branch` 快且安全）：

```bash
# 安装（需要 Python）
pip install git-filter-repo

# 从全部历史中删除该文件
git filter-repo --path 要删除的路径 --invert-paths

# 强制推送（单人项目才可以）
git push --force --all
git push --force --tags
```

⚠️ **执行前必须知道**：
- 这会**改写所有提交的哈希**，所有协作者必须重新 clone
- GitHub 上的旧对象**不会立即消失**，需要联系 GitHub Support 或等待其 GC
- **密钥泄露的第一动作永远是"去服务商那里吊销/更换密钥"**，清历史是补刀，不是解药

### 关于 `keystore` 和密码的特别提醒

你的 `keystore/screentranslator.jks` **从未提交过**（因为一开始就写进了 `.gitignore`）。保持这样就好。

如果哪天你在别的项目里不小心提交了签名库，正确顺序是：

1. **立刻生成新的 keystore**（旧的那个身份已经不可信）
2. 用新 keystore 发一版（用户需要卸载重装，这是代价）
3. 清历史
4. 把新的 keystore 放在**仓库之外**，离线备份三份

---

# 附录

## 附录 A　完整命令清单（从零到推送，可整段复制）

```bash
# ========== 1. 配置（只做一次）==========
git config --global user.name "Saltin"
git config --global user.email "你的邮箱@example.com"
git config --global core.quotepath false
git config --global i18n.commitEncoding utf-8
git config --global i18n.logOutputEncoding utf-8

# ========== 2. 初始化 ==========
cd /f/WorkSpace/screen-translator
git init
git branch -M main

# ========== 3. 安全检查（必须做）==========
git status
# 确认列表里没有 apk/、app/build/、.tmp-verify/、keystore/、gradle.properties、local.properties

# ========== 4. 首次提交 ==========
git add -A
git status --short | wc -l    # 应该是 75~80，不是几百
git commit -m "chore: 初始化项目，导入 ScreenTranslator v1.0.0 完整源码"

# ========== 5. 关联远程 ==========
git remote add origin https://github.com/你的用户名/screen-translator.git
git remote -v

# ========== 6. 推送 ==========
git push -u origin main
# 用户名：你的 GitHub 用户名
# 密码：ghp_ 开头的 Personal Access Token

# ========== 7. 打标签 ==========
git tag -a v1.0.0 -m "首个正式版：屏幕实时翻译功能完整"
git push --tags

# ========== 8. 日常 ==========
git status
git diff
git add -A
git commit -m "fix: 说明"
git push
```

## 附录 B　`.gitignore` 全文

已写入 `F:\WorkSpace\screen-translator\.gitignore`，内容见 3.5。核心结构：

```gitignore
/build/                 # 构建产物
/app/build/
*.apk  *.aab  *.dex  *.class  *.idsig  *.hprof
captures/  .cxx/

/.gradle/               # 缓存
/.kotlin/
local.properties

/keystore/              # 密钥（最危险）
*.jks  *.keystore  *.p12  *.pfx  *.pem  *.key

gradle.properties       # 含密码（配 example 模板）

/.tmp-verify/           # 临时产物
/.tmp-*/
tmp/  *.log  *.tmp  *.bak  *.orig

.idea/  *.iml  *.ipr  *.iws  .vscode/  *.swp  *~

.DS_Store  Thumbs.db  desktop.ini

/models/  *.onnx  *.tflite  *.spm

/apk/  /release/        # 安装包（走 Releases）
```

## 附录 C　上传前最终检查清单（8 项，逐条打勾）

- [ ] **1.** `git status` 的输出里**没有** `apk/`、`app/build/`、`.tmp-verify/`、`keystore/`、`gradle.properties`、`local.properties`
- [ ] **2.** `git status --short | wc -l` 的结果在 **75～80** 之间（不是几百、不是上万）
- [ ] **3.** `gradle.properties` 已被忽略，`gradle.properties.example` 已存在且**不含真实密码**
- [ ] **4.** `keystore/` 已被忽略，并已**离线备份**（U 盘或密码管理器）
- [ ] **5.** 全项目搜一遍 `ghp_`、`hf_`、`sk-`、`AIza`，确认没有硬编码 token
  ```bash
  grep -rIn "ghp_\|sk-[A-Za-z0-9]\{20,\}\|hf_[A-Za-z0-9]\{20,\}\|AIza[0-9A-Za-z_-]\{30,\}" \
    --include=*.kt --include=*.kts --include=*.xml --include=*.md --include=*.properties . \
    | grep -v ".tmp-verify"
  ```
- [ ] **6.** `README.md` 的版本号已更新为 `v1.0.0`，并补上了"从源码构建"说明
- [ ] **7.** `CHANGELOG.md` 已创建，包含 0.1.0 ～ 1.0.0 全部条目
- [ ] **8.** 已在 GitHub 页面上确认源码可见、APK 不可见、标签 `v1.0.0` 存在

## 附录 D　一句话总结

> **Git 存的是"你敲出来的字"，不是"跑出来的东西"。**
> 源码必传，构建产物必弃，密钥永不入库，大文件外挂托管。
> 判断不清时，只问一句：**别人 clone 下来，跑这个项目需不需要它？**

---

*文档版本：2026-10-19 ｜ 对应项目状态：ScreenTranslator v1.0.0（原 v1.6.3）*
