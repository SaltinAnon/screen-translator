# 自转 tc-big 模型：上传到 HuggingFace 指南

> 这份文档说明「模型已经转好了，怎么传到网上去」。
> 如果你（用户）只想让我直接传，把 token 发我即可，不用自己动手。

## 一、背景：为什么必须上传

App 的「端侧离线引擎」是把模型下载到手机上跑。下载源是 **HuggingFace**（一个模型托管网站），
国内访问走 **hf-mirror.com** 镜像。

这部分模型不是官方现成的 —— Helsinki-NLP 只发布了 PyTorch 权重，没有手机能用的 ONNX。
是我在本地用 `torch + optimum` 转成 ONNX、再量化成 int8 的（见 `build_tcbig_all.py`）。
**自转产物必须自己找个地方托管**，否则 App 下不到。

## 二、这次要传什么

三个方向，每个方向三个文件，共 9 个文件、约 **858MB**：

| 方向 | 用途 | 大小 |
| --- | --- | --- |
| `tc-big-zh-ja` | **中→日**（你要的直译） | 285.9MB |
| `tc-big-ko-en` | 韩→英 | 285.9MB |
| `tc-big-en-ko` | 英→韩（**中→韩接力要用**，Xenova 没有这个方向） | 285.9MB |

每个方向的文件（实测字节数）：

```
tc-big-zh-ja/
  tokenizer.json                               1,609,890 B
  onnx/encoder_model_quantized.onnx          112,878,703 B
  onnx/decoder_model_merged_quantized.onnx   171,372,332 B
```

（`tc-big-ko-en` / `tc-big-en-ko` 的 tokenizer 是 1,693,492 B。）

## 三、步骤

### 1. 注册 HuggingFace 账号

https://huggingface.co/join —— 邮箱注册，免费，不需要绑卡。

### 2. 生成 token（写权限）

1. 打开 https://huggingface.co/settings/tokens
2. 点 **+ Create new token**
3. **Token type** 必须选 **Write**（Read 权限传不了文件）
4. Name 随便填，比如 `screentranslator-models`
5. 点 **Create token**
6. 复制那串 `hf_xxxxxxxx`（**只显示一次**）

### 3. 上传

把 token 设成环境变量，然后跑脚本（<产物目录> 就是转换输出目录）：

```bash
export HF_TOKEN=hf_你的token
python upload_tcbig.py <产物目录> <你的用户名>/screentranslator-models
```

例如：

```bash
export HF_TOKEN=hf_AbCdEf123456
python upload_tcbig.py F:/WorkSpace/screen-translator/.tmp-verify/models_upload2 saltin/screentranslator-models
```

脚本会自动：
- 校验 token 是否有效
- 创建仓库（public）
- 按 `tc-big-<方向>/` 分组上传
- 打印上传结果与验证命令

### 4. 验证

```bash
curl -I https://hf-mirror.com/<用户名>/screentranslator-models/resolve/main/tc-big-zh-ja/onnx/encoder_model_quantized.onnx
```

返回 `200` 就说明镜像也拿到了，App 能下。

### 5. 把仓库名写进代码

改 `OfflineModelCatalog.kt`：

```kotlin
private const val SELF_REPO_PREFIX = "SaltinA/screentranslator-models"
```

> **当前实际值就是 `SaltinA/screentranslator-models`（已于 2026-10-03 上传完成）。**

## 四、仓库最终结构

```
SaltinA/screentranslator-models/       ← 这是 repo_id
  tc-big-zh-ja/                        ← 以下是仓库内的路径
    tokenizer.json
    onnx/encoder_model_quantized.onnx
    onnx/decoder_model_merged_quantized.onnx
  tc-big-ko-en/
    ...
  tc-big-en-ko/
    ...
```

### ⚠⚠ 最容易踩的坑：方向必须放在「路径」里，不能放进「仓库名」

HuggingFace 的下载地址格式是：

```
https://{host}/{repo_id}/resolve/main/{path_in_repo}
```

`{repo_id}` 只能是 `<用户名>/<仓库名>` 两段。**方向目录属于 `{path_in_repo}`。**

第一次实现时把方向拼进了 `repo`：

```kotlin
repo = "$SELF_REPO_PREFIX/tc-big-$key"     // ❌ 错
```

拼出来的地址是：

```
https://hf-mirror.com/SaltinA/screentranslator-models/tc-big-zh-ja/resolve/main/onnx/...
                                                                    ^^^^^^^^^^^^
                              HF 把这一段当成仓库名的一部分 → 404
```

**9 个地址全 404**。正确写法：

```kotlin
repo = SELF_REPO_PREFIX                     // ✓ 只有 <用户名>/<仓库名>
file.path = "tc-big-$key/onnx/encoder_model_quantized.onnx"   // ✓ 方向在路径里
```

对应地，`OfflineModelStore` 取本地文件名用的是
`file.path.substringAfterLast('/')`，所以路径前缀**不会污染本地存储结构**
（三个方向的 `encoder_model_quantized.onnx` 依然各自落在自己的包目录里）。

### 验证方法

改完一定要按 **App 的 `urlOf` 规则**逐条试探（不能只看仓库文件列表）：

```bash
for key in zh-ja ko-en en-ko; do
  for f in tokenizer.json onnx/encoder_model_quantized.onnx onnx/decoder_model_merged_quantized.onnx; do
    curl -sL -o /dev/null -w "%{http_code}  tc-big-$key/$f\n" \
      -r 0-0 "https://hf-mirror.com/SaltinA/screentranslator-models/resolve/main/tc-big-$key/$f"
  done
done
```

期望：`tokenizer.json` → 200，两个 `.onnx` → 206（206 = 支持 Range，即断点续传可用）。
**任何 404 都说明路径规则不对。**

## 五、上传后自查清单

- [ ] `hf-mirror.com/api/models/<repo>` 能列出 9 个文件（注意此接口有**分钟级缓存延迟**，
      刚传完可能少显示；以逐文件 `curl` 的结果为准）
- [ ] 9 个地址按 App 的 URL 规则全部 200/206
- [ ] 远端 `Content-Length` 与本地字节数**逐一相等**
- [ ] 两个 `.onnx` 的响应带 `Accept-Ranges: bytes`

本次（2026-10-03）实测结果：**9/9 通过，字节数全部一致，均支持 Range。**

## 六、安全

token 等于账号写权限。模型传完后不放心就删掉：

https://huggingface.co/settings/tokens → 对应那条 → **Delete**

删除不影响已上传的模型（文件是独立存在的）。
