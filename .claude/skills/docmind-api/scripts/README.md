# DocumentMind 探针

`DocMindProbe.java` 是**复验工具**，用来重新确认 DocumentMind 的行为——改了参数、加了格式、怀疑服务端行为变了的时候跑它。

它不是测试，**也不在 Maven 构建路径上**（所以 `mvn test` 不会碰它，不会产生任何费用）。`run-probe.sh` 自己拼 classpath：让 Maven 导出 `llm` 模块的运行期依赖清单，再 `javac` + `java`。

## 怎么跑

```bash
cd .claude/skills/docmind-api/scripts

export ALIBABA_CLOUD_ACCESS_KEY_ID=xxx
export ALIBABA_CLOUD_ACCESS_KEY_SECRET=xxx
export PROBE_SAMPLE=/绝对路径/sample.docx     # 有 PROBE_JOB_ID 时可省

./run-probe.sh
```

## 最要紧的一条：别重复计费

**设了 `PROBE_JOB_ID` 就跳过提交，直接读那个已有任务的结果。**

`getDocParserResult` 和 `queryDocParserStatus` 都是**读接口**——反复拉不再花钱。所以：

- 调试时**先看手上有没有现成的任务号**（`ingest_task.external_job_id`，或上一次探针输出里的那行），有就复用它
- 只有真的要换样本、换参数时才重新提交

```bash
export PROBE_JOB_ID=docmind-20260926-c78fa2871a3c40b1a85e4794b1084103
./run-probe.sh
```

## 看哪里

**看 `$PROBE_OUT/report.txt`（默认 `target/probe-out/report.txt`），不要看控制台。**

Windows 控制台会把中文压成乱码（`��������`），报告是显式按 UTF-8 落盘的。控制台上只有进度信息是有用的。

报告里包含四段：

| 段 | 内容 |
|---|---|
| 轮询日志 | `status` 变化、`processing`（**0–100**）、段落数/表格数/tokens |
| ① 状态查询完整返回 | `outputFormatResult`、`outputFileUrl` 与签名参数 |
| ② 输出文件 | 下载全文（markdown 形态） |
| ③ 版面块 | `layouts[]`：`type` / `fontSize` / `index` / `pageNum` / `markdownContent`，以及表格的 `numCol` + `cells[]` |

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `ALIBABA_CLOUD_ACCESS_KEY_ID` | 是 | |
| `ALIBABA_CLOUD_ACCESS_KEY_SECRET` | 是 | |
| `PROBE_SAMPLE` | 未设 `PROBE_JOB_ID` 时必填 | 样本文件绝对路径 |
| `PROBE_JOB_ID` | 否 | 复用已有任务号，**跳过提交、不计费** |
| `PROBE_OUT` | 否 | 输出目录，默认 `target/probe-out` |
| `PROBE_REGION` | 否 | 默认 `cn-hangzhou` |
| `PROBE_ENDPOINT` | 否 | 默认 `docmind-api.cn-hangzhou.aliyuncs.com` |
| `PROBE_WORK` | 否 | 编译产物目录，默认本目录下的 `.work/` |

## 造样本

要一份带**两级标题 + 表格 + 页眉页脚**的 docx 来观察解析行为时，用 `python-docx` 造一份即可（本机已装）。刻意放页眉是为了验证 `needHeaderFooter=false` 有没有生效——不设的话它会变成第一个 `type=title` 块。

`.work/` 是编译产物，不要提交。
