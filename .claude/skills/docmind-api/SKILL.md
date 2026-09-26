---
name: docmind-api
description: 阿里云 DocumentMind（文档智能 / docmind_api20220711）文档解析 SDK 的调用方法与本项目集成约定。凡是涉及「把 pdf / docx / png 解析成文本」的代码——新写解析器、改解析参数、排查解析结果不对（页眉混进正文、表格丢了、进度数字离谱）、判断解析这一步该放本项目哪一层、和上传流水线的任务表怎么衔接——都要先读这个 skill。即使对方只说「接一下文档解析」「上传的 pdf 解析不出来」「DocumentMind 怎么调」「解析服务返回的东西怎么用」也该读。本 skill 里的事实全部来自 2026-09-26 的真跑实测，不是从文档抄的。
---

# 阿里云 DocumentMind 文档解析

这个 skill 解决两类问题：**SDK 怎么调**（含实测踩出来的坑），以及**在本项目里接在哪、和谁衔接**。

事实来源是 2026-09-26（合成 docx 真跑一次）。标了「未确认」的地方是真的没验证过，不要当成结论用。

---

## 一、先说三个最容易踩的坑

这三条是只有真跑才会发现的，看文档看不出来。**写任何调用代码之前先扫一遍**。

### 1. `processing` 是 0–100，不是 0–1

```java
// 错：以为进度是百分比小数，判完成写成 >= 1，结果第一次轮询就"完成"了
if (data.getProcessing() >= 1.0f) { ... }

// 对：
if (data.getProcessing() >= 100f) { ... }
```

另外状态字段是 `status`，值为 `success`（小写）。别拿 `processing` 当唯一判据——它和 `status` 一起看。

### 2. 不设 `needHeaderFooter=false`，每页页眉都会变成一个标题块

实测：样本 docx 的页眉「智能导诊系统 · 知识库样本」被识别成 `type=title`，混在正文最前面。而本项目的切分规则是**「`type=title` 的块 = 切分边界」**——页眉每页出现一次，于是每页页眉都会开一个新的 chunk，文档被切成一堆碎片，而且 chunk 的 `title` 全是页眉文字。

```java
req.setNeedHeaderFooter(false);   // 必须设，解析类文档没有例外
```

### 3. 文件要传流，不要传 URL

`setFileUrl(String)` 要求那个 URL 对阿里云**公网可达**——本项目的 MinIO 在局域网（`192.168.193.131`），阿里云根本连不到。

用 `SubmitDocParserJobAdvanceRequest` + `setFileUrlObject(InputStream)`：SDK 内部把流上传走（`tea-fileform`），**不需要 OSS，也不需要文件公网可达**。

```java
try (InputStream in = Files.newInputStream(sample)) {
    SubmitDocParserJobAdvanceRequest req = new SubmitDocParserJobAdvanceRequest()
            .setFileUrlObject(in)            // ← 流，不是 URL
            .setFileName("xxx.docx")         // 带扩展名，服务端按它判格式
            .setFileNameExtension("docx")
            .setNeedHeaderFooter(false);
    ...
}
```

---

## 二、调用是三步异步

```java
Client client = new Client(new Config()
        .setAccessKeyId(ak)                  // 环境变量 ALIBABA_CLOUD_ACCESS_KEY_ID
        .setAccessKeySecret(sk)
        .setRegionId("cn-hangzhou")
        .setEndpoint("docmind-api.cn-hangzhou.aliyuncs.com"));

// ① 提交 —— 拿到任务号就结束，不要在这里等
SubmitDocParserJobResponse submit = client.submitDocParserJobAdvance(req, new RuntimeOptions());
String jobId = submit.getBody().getData().getId();

// ② 轮询
QueryDocParserStatusResponse st = client.queryDocParserStatus(
        new QueryDocParserStatusRequest().setId(jobId));
var data = st.getBody().getData();
data.getStatus();        // "success"
data.getProcessing();    // 0.0 → 100.0

// ③ 取结果（两条路，见下）
```

`queryDocParserStatus` 返回的统计字段都是现成的，可以直接喂进度条：

| 字段 | 说明 |
|---|---|
| `getProcessing()` | 0–100 |
| `getParagraphCount()` | 段落数 |
| `getTableCount()` | **表格数** |
| `getImageCount()` | 图片数 |
| `getTokens()` | token 数 |
| `getNumberOfSuccessfulParsing()` | 已成功解析数 |
| `getPageCountEstimate()` | 页数估算（**docx 恒为 0**，它没有页概念） |

### 取结果有两条路，都是读接口

**反复拉不再计费**——这意味着任务号要长期留着（本项目存在 `ingest_task.external_job_id`），也意味着调试时可以用同一个任务号反复读。

```java
// 路 A：分页拉版面块（结构化，本项目走这条）
GetDocParserResultResponse r = client.getDocParserResult(
        new GetDocParserResultRequest().setId(jobId).setLayoutNum(0).setLayoutStepSize(50));
// 返回体是 Map<String,?>，结构由服务端定义：data.layouts = 全篇按阅读顺序排好的平铺列表

// 路 B：下载整篇文件（markdown）
String url = st.getBody().getData().getOutputFormatResult().get(0).getOutputFileUrl();
// 直接用 HttpClient GET 即可，URL 自带签名，不需要再带 AK
```

**分页语义（2026-09-26 实测确认）**：`layoutNum` 是**块偏移**、`layoutStepSize` 是取多少块，
就在上面那个平铺列表上滑动；取到末尾返回少于 step 块、再往后返回空。

- **没有总数字段**——判"取完了"只能靠"这一页不满 step"（`queryDocParserStatus` 里的
  `numberOfSuccessfulParsing`/`paragraphCount` 是解析计数，**不等于** layouts 条数，别拿它判）
- 实测 `layoutStepSize=500` 时一份两页 PDF 一次返回全部 62 块
- `layoutNum` 取 1 就是"从第 1 块起"（不是页码）

> **结果文件 URL 是 OSS 签名链接，实测有效期 12 小时**（`Expires` 时间戳减当前时间 = 43200 秒），而且是 **http 不是 https**。所以：要么及时下载，要么就别依赖它——**重新解析是要重新付费的**。

`setOutputFormat(List<String>)` 可以传，但实测传 `["markdown","json"]` **只回了 `markdown` 一个输出**；`"json"` 是否合法未确认。**不影响使用**——结构化数据走路 A 拿，不必依赖文件格式。

---

## 三、版面块（layouts）长什么样

`getDocParserResult` 返回的每个块：

| 字段 | 实测值 | 用途 |
|---|---|---|
| `type` | `title` / `text` / `table` | **切分边界的唯一依据** |
| `fontSize` | 页眉 12 / 正文 12 / H2 14 / H1 15 | 能推层级，但本项目**不用**（切分只需要边界） |
| `index` | 0,1,2… | ⚠️ **页内序号**，第 2 页从 0 重来（2026-09-26 实测）。排序键是 **(pageNum, index)**——只按 index 排会把两页逐条交错 |
| `pageNum` | docx 恒 0；pdf 从 0 起 | 页码溯源 + **排序主键** |
| `markdownContent` | `"# 心血管内科分诊知识  \n\n"` | 每块自带，和 markdown 视图同源 |
| `text` | 纯文本 | |
| bbox 坐标 | **没有** | 普通文本块没有坐标 |

⚠️ `fontSize` 的**绝对值不可移植**——换套模板字号就全变了。要推层级只能做文档内相对聚类，而聚类会误判（正文里偶发的放大强调文字会被当成标题，把内容切碎）。所以本项目**不建层级树**。

### 表格是结构化的

表格块带 `numCol` + `cells[]`，每个 cell 有：

```json
{ "xsc": 0, "xec": 0, "ysc": 0, "yec": 0,     // 列起止 / 行起止（网格坐标）
  "cellId": 0, "type": "text", "layouts": [ ...单元格内文本... ] }
```

**它的 markdown 形态是完整的 markdown 表格**：

```
| 症状|首诊科室|鉴别方向|
| ---|---|---|
| 劳力性胸闷|心血管内科|需与呼吸系统疾病鉴别|
```

本项目取 **markdown 形态作为一整块入 chunk**，不参与正文切分——一行的文本本身就是完整自洽的语义单元，向量检索能精准命中，而它对应的患者主诉也高度相似。

---

## 四、本项目里的集成约定

调 SDK 本身不难，难在**放对位置**。这几条是架构基线（见《总体架构与链路设计.md》链路 B），改代码前先对齐：

| 约定 | 为什么 |
|---|---|
| DocumentMind 客户端放 **`llm`** 模块，经它调用 | `llm` 是项目唯一的**外部模型出口**。业务模块直连外部 AI 服务会破坏依赖规范 |
| `pdf`/`docx`/`png` 走 DocumentMind，**是唯一路径、不降级** | 降级路径（POI/PDFBox）产出质量不同，会让知识库混进两种切分风格，而切分质量直接就是检索质量；且降级路径平时不跑，故障时才跑一次，产出没人验证过就进了库 |
| `html` 走 Tika、`txt`/`md` 原生读；**Tika 不得接 pdf/docx** | Tika 本身就能解析这两种格式，躺在依赖里迟早有人把解析失败的 pdf 丢给它"试试"，那条刚被否掉的降级路径会顺着依赖爬回来 |
| 解析编排（格式分流）与**切分策略放 `kb`**；`async` 只管线程池与任务表 | 切分属"文档变切片"的领域逻辑，回流合成 chunk 要复用；编排属离线侧 |
| 编排是**两段式**：线程池只提交，`@Scheduled` 扫任务轮询 | 一份件可能解析几分钟，不能让线程池线程 sleep 等；且 JVM 重启会丢掉正在轮询的任务，`ingest_task` 会永远停在 `running` 变成僵尸 |
| 外部任务号存 **`ingest_task.external_job_id`** | 重启后接着轮询靠它。这个值**推不出来**，丢了就真接不上 |
| 失败分类：依赖故障 → `retryable`；输入问题 → `fatal`；**拿到结果但不合格 → `retryable`** | 判据是「再跑一次可能就好了」。它只决定管理端「重新处理」按钮给不给——那是**给人用的决策入口**，人看一眼日志就分得清是加密件还是服务端抖动 |

### 依赖怎么加

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>docmind_api20220711</artifactId>
    <version>2.0.14</version>
</dependency>
```

- `tea-openapi` / `tea-util` / `tea-fileform` 等**是它的传递依赖，不必显式声明**
- **不要引 `fastjson`**——结果体用项目已有的 Jackson 解析就够了，多引一个 JSON 库没有收益

---

## 五、要复验时

改了参数、加了格式、怀疑行为变了，跑 `scripts/run-probe.sh`（用法见 `scripts/README.md`）。

**关键：`PROBE_JOB_ID` 设了就跳过提交、直接读已有任务的结果——读接口不重复计费。** 所以调试时不要反复重新提交，先看看手上有没有现成的任务号。

探针把报告**落盘 UTF-8** 再读，因为 Windows 控制台会把中文压成乱码——不要去读控制台输出。

---

## 六、还没确认的（别当结论用）

- **计费口径**：按页还是按次、有没有免费额度——jar 里查不到，要去看阿里云控制台/定价页
- `setOutputFormat` 里 `"json"` 是否合法
- `setEnableEventCallback(true)` 的事件回调形态（要公网可达的回调地址，本地开发环境大概率不具备）
- `layoutStepSize` 取很大时有没有上限
