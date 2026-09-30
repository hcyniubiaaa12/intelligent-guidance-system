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
| `type` | `title` / `text`（表格靠结构字段判，见下） | 切分边界依据之一，**但只看它不够**（会漏判），见「`type` 只看视觉格式」一节 |
| `fontSize` | 页眉 12 / 正文 12 / H2 14 / H1 15 | 能推层级，但本项目**不用**（切分只需要边界） |
| `index` | 0,1,2… | ⚠️ **页内序号**，第 2 页从 0 重来（2026-09-26 实测）。排序键是 **(pageNum, index)**——只按 index 排会把两页逐条交错 |
| `pageNum` | docx 恒 0；pdf 从 0 起 | 页码溯源 + **排序主键** |
| `markdownContent` | `"# 心血管内科分诊知识  \n\n"` | 每块自带，和 markdown 视图同源 |
| `text` | 纯文本 | |
| bbox 坐标 | **没有** | 普通文本块没有坐标 |

⚠️ `fontSize` 的**绝对值不可移植**——换套模板字号就全变了。要推层级只能做文档内相对聚类，而聚类会误判（正文里偶发的放大强调文字会被当成标题，把内容切碎）。所以本项目**不建层级树**。

### ⚠️ `type` 只看视觉格式，编号标题会漏

`type=title` 的含义是"**看起来**像标题"（大字号/加粗/居中）。标题**只靠编号**标注、没做视觉强调时，就会漏判成 `text`；而服务端的判定有波动——**同样样式、同样层级的两个标题，一个认出一个没认出**（2026-09-30 实测，见下）。

2026-09-30 拿**真实解析结果**数过（「多级标题测试文档.pdf」62 块，`getDocParserResult` 的真实返回，不是文本层代理）：28 个章节标题里服务端认出 26 个，**漏 2 个**——恰好都是四级编号：

| 块 | 字号 / 加粗 / 字体 | 服务端判定 |
|---|---|---|
| `2.1.1.1对症处理` | 14 / true / Verdana | `title` ✅ |
| `1.1.1.1术语约定` | 14 / true / Verdana | `text` ❌ |
| `3.1.2.1直接四级` | 14 / true / 黑体 | `title` ✅ |
| `4.2.1.1四级` | 14 / true / Times_New_Roman | `text` ❌ |

**样式上看不出差别**，所以别指望"再调调参数就能让服务端全认出来"——漏判只能靠本地兜底接住。

所以本项目的块类型判定是**两步**（`LayoutBlock.BlockType.fromExternalType` + `DocMindLayoutReader.toBlock`）：

1. **按 `type` 查表**（`type` 缺省时依次回退看 `subType`、`layoutType`）。取值表按参考实现补全，**顺序即优先级**：

   | 外部 type 命中 | 块类型 | 说明 |
   |---|---|---|
   | `title` / `heading` / `header1` / `header2` | `TITLE` | **必须排在 `header` 之前**，否则 `header1` 会先被子串 `header` 吃掉、标题变成页眉 |
   | `table` | `TABLE` | 实际不靠它判，见下 |
   | `figure` / `chart` | `FIGURE` | |
   | `image` / `picture` | `IMAGE` | |
   | `formula` / `equation` | `FORMULA` | |
   | `code` | `CODE` | |
   | `footer` | `FOOTER` | 排最后：其余都匹配不上才轮到它 |
   | `header` | `HEADER` | 同上 |
   | `text` / `paragraph` | `TEXT` | |
   | 其它（如实测出现过的 `caption`） | `UNKNOWN` | **照表判，不按"看着像"归类** |

2. **类型给不出结论时，回到文字本身看章节编号**（≤80 字、多级阿拉伯编号或 `第X章`/`一、`，且编号之后还有中文 → 标题）。与参考实现同口径：只在外层**没有命中任何类型**时才走这一步

> ⚠️ **两个只有真跑才见过的形态**（2026-09-30 实测「O2-扫描OCR验收样例-文字截图.png」，25 块）：
> - `type=stamp`（**印章**）：不在上面那张表里，落到 `unknown`。它没有文本，服务端给的 `text` 是**字面量 `[empty]`**——**不是空串**。判空必须认这个哨兵，否则它会当正文写进切片（实测确实进过库）。本项目按空块丢弃，并把"空块判定"放在类型判定之前，免得一块没内容的印章被算成"未映射的 type"而刷 WARN
> - 图片/水印上的文字（如页面角落的 `图片型PDF/OCR测试`）**直接以 `text` 块返回**，没有 `type=image` 占位——这就是「图片内文字混进正文」那条损耗，目前**按正文收下**（要不要过滤是另一个决定）

**这 10 类里只有 `TITLE` 与 `TABLE` 改变切分行为**，其余 6 类（figure/image/formula/code/header/footer）在切分里都按正文处理。单独建类型是为**可观测**（`chunk_type` 落 MySQL/ES/pgvector 三处）与**后续过滤的抓手**——认得出与要不要动是两件事。

> ⚠️ 动这里必须同步 `DocSplitter` 的 `switch`：**新类型要逐一点名**，写 `default` 或漏写都不会报错，那块内容会被静默丢掉。`DocSplitterTest.everyBlockTypeIsHandled` 遍历枚举值兜住这条。

#### 编号兜底的两条收紧（照抄参考实现会踩）

参考实现（`nexus-agent-rag-tools` 的 `_classify_text_block`）的规则是
「≤80 字 **且**（以编号开头 **或** 以 `章`/`节`/`：`/`:` 结尾）→ 标题」。本项目**只保留了「编号开头」那一半，并把阿拉伯编号收紧成「至少两级 + 编号之后必须有中文」**（`DocMindLayoutReader.looksLikeSectionHeading`）——每条都是用真实数据验出来的：

| 判据 | 挡掉了什么 |
|---|---|
| 阿拉伯编号**至少两级**（`1.1` 算，`1.` 不算） | 有序列表项：`列表与图片混排测试文档.pdf` 里就是 `1. 冻结代码分支，停止合并新的改动。`，单级编号与列表项在文本上**无法区分** |
| 编号之后**必须还有中文** | ① IP 地址——`10.0.1.1`、`114.114.114.114`、`10.0.1.100/24` 独立成行时与章节编号字形完全一样；② 规格值 `1.2 kg` |
| 不收「以冒号结尾」 | 中文文档里 ≤80 字且以 `：` 结尾的行绝大多数是**引导句**（本项目 md 语料 10 篇里 8 篇命中，如"以下信息必须归为 L4："）。误提升的代价不只是多一个边界：新标题会顶掉它前面真正的章节标题，**那一段的溯源出处就没了** |
| 括号编号 `（一）`/`(1)` 不收 | 它同时是有序列表的编号形态 |

> ⚠️ **「编号后必须跟空白」是个坑，别用**（2026-09-30 踩过）：DocumentMind 的 `text` 会**吃掉编号与标题之间的空格**，真实解析结果里是 `1.1编写目的`、`1.1.1.1术语约定`（PDF 文本层里明明是 `1.1 编写目的`），所以按空白判的兜底**一条都匹配不上、等于没写**。改成"编号之后还有中文"才对这份服务端的产物成立。残留在"IP + 中文说明"（`10.0.1.1 网关地址`）上会误判，但目前没见真实语料这样排版。

**兜底到底补了多少（2026-09-30 实测「多级标题测试文档.pdf」62 块）**：DocumentMind 自己认出 26 个标题，**漏了 2 个**（`1.1.1.1术语约定`、`4.2.1.1四级`，都是四级编号，样式与相邻标题无异，服务端就是判成了 `text`）——兜底正好把这 2 个救回来，误判 0。所以：**兜底是"补漏"，不是主力**；主力仍是服务端的 `type`。

**同一条链路的两个字段分工**（有人问过"为什么标题识别成 text"）：
- `chunk.title` = 这一片的**章节归属**（标题列的正文片带着最近标题）
- `chunk.chunk_type` = **这一片是什么**：`title` = 就是一个小节标题（**每个标题块都单独成片**，2026-09-30 起，content 等于标题本身）、`text` = 正文片、`table` = 表格片。所以"标题有没有被认出来"**直接看有没有 `chunk_type=title` 的片**，不用再反推

> 提醒：标题单独成片会让切片数明显变多（实测「多级标题测试文档」28 → 57，标题占一半），且标题片很短。ES 的 `title^2` 与 content 都会命中它，**检索时可能同时召回标题片与正文片**。这是**召回冗余不是召回错误**（同一小节出现两次、引用不同片号），**已定案不降权、不过滤**（2026-09-30）——降权要先有真实误召回的案例，否则就是凭猜调参。

#### 提升标题会撞上一个已有隐患：连续标题丢字

类型判定的产物直接喂切分，而切分规则是「标题块 = 边界，**标题文字不进正文**」（它进 chunk 的 `title`，向量化文本是「标题 + 正文」，信息不丢）。于是**两个标题相邻、中间没有正文时，前一个标题的文字会谁都不含**——`4.2 深层编号` 紧跟 `4.2.1 三级` 正是这种形态。

编号兜底会让相邻变多，所以 `DocSplitter` 补了一条：没人承接的标题**补成独立一块**（标题即正文）。动切分或动类型判定之前，先看一眼 `DocSplitterTest.consecutiveHeadingsAreNotLost`。

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
