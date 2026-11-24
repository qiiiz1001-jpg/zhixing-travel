# 03：私人资料上传与 RAG 检索

## 本篇目标

把 RAG 定位为用户自己的旅行经验资料库：上传好友攻略、游记、避雷记录，在相关规划 Agent 生成答案前检索参考。先不做公司制度。原文范围仍是 RAG（4718～5562 行）；上传、资料管理、用户隔离、删除和资料版本关联属于本次工程增强，原始课程资料未修改。

## 具体业务场景

用户上传《好友西安攻略.pdf》，其中写了某住宿区域夜间较吵。用户要规划西安旅行时，住宿 Agent 先检索相关片段，再查实时酒店信息，结合偏好筛选。在推荐理由里说明参考了哪份资料。好友体验是个人经验，不能当成永久事实；旧攻略中的价格、房态和营业时间仍需实时核验。

### RAG 到底是什么意思

RAG 是 Retrieval-Augmented Generation，中文是检索增强生成。可以理解为“先找资料，再带着资料回答”，分为两个阶段：

```text
入库：上传文件 → 本地解析文字 → 父子切片 → Embedding 转向量 → 保存文字、来源和向量

使用：当前需求 → 查询向量 → 检索本人相关片段 → 排序与补齐父段
             → 将少量片段加入模型上下文 → 模型结合工具结果生成推荐
```

Embedding 模型把文字变成便于相似检索的数值向量；生成模型负责理解需求、调用工具和组织答案。向量化不等于训练生成模型，也不等于把整份文档变成系统提示词。

本工程在 AgentFactory 中使用 LangChain4j 的 `.contentRetriever(contentRetriever)`。框架在调用模型前检索，再通过内容注入器把片段补充到用户消息上下文；业务代码不用手工拼接每一段。系统规则、需求和资料仍有不同职责：资料是参考数据，不能指挥模型覆盖系统规则。

### PDF 怎么解析

当前采用 Java 的 Apache Tika 3.3.3 标准解析包；PDF 底层使用 PDFBox，Office 使用对应文档解析器。它们是通过 Maven 引入的代码库，不需要用户安装聊天插件，也不直接调用聊天大模型读取 PDF。

| 文件类型 | 当前处理方式 |
|---|---|
| 有文字层的 PDF | 提取文字后切片入库 |
| 纯扫描 PDF、图片型内容 | 当前关闭 OCR；没有可提取文字时拒绝入库，提示先做 OCR |
| 文字与扫描页混合的 PDF | 提取已有文字层，图片里的文字本轮不会识别 |
| 加密文档 | 提示解密后重新上传，不收集密码 |
| 复杂表格、图片与版式 | 当前以文字检索为主，不保证完整保留视觉布局与图表含义 |

将来扫描件可接 Tesseract/PaddleOCR，复杂图表也可接视觉模型；本轮没有把这些写成已实现。解析在本地进行，后续 Embedding 默认仍使用配置的远程向量模型，并非整个入库过程都离线。

## 分步调用流程与源码入口

1. 页面新增“我的旅行资料”，支持上传、列表、查找和删除。旅行资料界面由 Vue 组件 [TravelDialogs.vue](../../frontend/src/components/TravelDialogs.vue) 渲染，构建后由 Spring Boot 提供。
2. [KnowledgeController](../../src/main/java/cn/zhixing/travel/controller/KnowledgeController.java) 从已认证的 RequestContext 获取用户身份。接口不接收客户端自选的 ownerId；接口仍经过原登录拦截器。
3. [TravelDocumentParser](../../src/main/java/cn/zhixing/travel/knowledge/TravelDocumentParser.java) 检查扩展名、大小和提取后的真实类型，处理空文件、解析失败、加密及文字超限。不会执行文档里的宏、脚本，也不解析内嵌附件。
4. [TravelKnowledgeSplitter](../../src/main/java/cn/zhixing/travel/knowledge/TravelKnowledgeSplitter.java) 生成父子片段。每个子段保存 owner_id、document_id、source、parent_id、parent_text 等 metadata；父段随向量记录持久化，不再依赖应用重启前的全局内存。
5. [TravelKnowledgeService](../../src/main/java/cn/zhixing/travel/knowledge/TravelKnowledgeService.java) 先写 INDEXING 元数据，再分批请求 Embedding 并保存向量，最后发布 READY 状态。相同用户、相同文件内容在正在入库或已就绪时不重复导入。
6. [KnowledgeRepository](../../src/main/java/cn/zhixing/travel/knowledge/KnowledgeRepository.java) 管理资料状态与资料版本；[03-knowledge.sql](../../src/main/resources/db/03-knowledge.sql) 幂等建表。仅 READY 资料参与检索，失败或未完成资料不会成为模型参考。
7. 需求完整、进入受影响分支后，[RagContentRetriever](../../src/main/java/cn/zhixing/travel/rag/RagContentRetriever.java) 使用 MemoryScope 从框架传入的版本化记忆 ID 还原原 sessionId，再查服务端会话归属，以 owner_id 和 READY document_id 在向量库中预先过滤，再进行相似检索。不能先跨用户搜出来，再仅靠提示词要求不泄露。
8. 检索结果可经过查询改写、混合排序和父段补齐，片段前携带文件名与文档 ID。缓存键包含用户和资料版本，避免不同用户共用答案或删除后命中旧资料缓存。
9. [AgentFactory](../../src/main/java/cn/zhixing/travel/config/AgentFactory.java) 为美食、住宿、交通执行子 Agent 和普通问答装配检索器。需求提取和交通最终比较不检索：前者收集用户要求，后者比较已有子方案数字。
10. [ChatServiceImpl](../../src/main/java/cn/zhixing/travel/service/impl/ChatServiceImpl.java) 每轮携带当前资料版本。上传或删除资料改变版本后，[RequirementChanges](../../src/main/java/cn/zhixing/travel/agent/requirement/RequirementChanges.java) 使旧方案失效；汇总成功才记录这次使用的版本。

### 什么时候会检索

| 情况 | 当前行为 |
|---|---|
| 需求缺项、正在追问 | 不进入规划 Agent，不检索 |
| 新规划且有个人资料 | 执行各必要 Agent 时，在模型调用前检索 |
| 只修改美食 | 只执行美食 Agent 的检索和规划；交通、住宿复用 |
| 同一请求、需求和资料版本都没变化 | 直接复用方案，不重新检索 |
| 上传或删除资料后再次发消息 | 保守使全部规划分支失效，参考当前资料重新执行 |
| 没有 READY 资料 | 直接返回空检索结果，不调用查询向量或查询改写模型 |
| 普通问答 | 模型生成前检索本人资料；没有命中则正常回答或调用工具 |

这里的“模型调用前”是每个需要参考资料的角色开始生成前，不是整个工作流最开始就统一检索，也不是每次 MCP 调用前都重搜一次。交通三种子 Agent 各自有一次检索入口；查询与版本相同可命中缓存。

## 关键设计和边界

支持 22 种扩展名：PDF；Word DOC/DOCX；Excel XLS/XLSX；PowerPoint PPT/PPTX；OpenDocument ODT/ODS/ODP；RTF；TXT、MD、MARKDOWN、LOG；CSV/TSV；JSON/XML；HTML/HTM；EPUB。

默认单文件最多 20MB、提取文字最多 20 万字符、最多 1500 个子片段。只保留切片文字、父段、向量和元数据，不提供原附件下载或原版式还原。Excel/CSV 用于文字参考，不替代结构化计算。

| 接口（应用统一前缀 /api） | 用途 |
|---|---|
| GET /knowledge/formats | 支持格式与默认限制 |
| GET /knowledge/documents | 当前用户的资料列表 |
| POST /knowledge/documents，multipart 字段 file | 上传并同步完成入库 |
| POST /knowledge/search，JSON 字段 query | 查看本人资料的检索片段 |
| DELETE /knowledge/documents/{id} | 停止参与检索并删除对应向量 |

删除先把元数据标记为 DELETED，之后清理向量；清理失败时资料也不在 READY 检索集合，可以重复调用删除重试。上传过程中删除也不能再将资料发布为 READY。入库失败会标记 FAILED 并尝试清理片段；进程崩溃留下的 INDEXING 记录需要删除后重新上传，本轮没有后台任务与自动续传。

删除不会追溯抹除已经生成的聊天记录。框架保存的角色记忆也可能保留历史引用，因此“删除知识库资料”不等于“删除历史聊天”。资料与需求并发更新时，已开始的请求可能用旧快照，下一轮会根据新版本重新规划；没有宣称跨请求瞬时撤回。

工程自带两份城市攻略保留为示例文件，已停止启动时自动导入。无 owner_id 或未关联 READY 个人资料的历史向量不会被此检索器使用。原 RagConfig 的全局资料加载及未完成的 RagTool 写库接口已由这条认证上传链替代。

infra 模式下仍可管理资料列表与删除；因为没有真实向量模型，上传入库和已有资料的相似搜索会返回明确提示，不生成假向量。真实使用需先配置 Embedding 模型；规划还需要聊天模型，动态查询需要 MCP。

### 验证结果

私人资料阶段的 clean package 记录为 49 项测试全部通过，失败 0、错误 0、跳过 0。其中 26 项解析测试覆盖 22 种扩展名及扫描/空白、加密、超限、中文来源与父段；5 项私人资料集成测试验证入库、隔离、删除、失败不可见、框架实际注入与控制器身份边界；原工作流和真实检查点测试继续通过。三层记忆加入后的当前 71 项验证见 [04](04-三层记忆与偏好规划.md)。该阶段日志详见 [完整日志](../../.codex-work/private-rag-20261120/full-test.log)。解析样本使用本地真实文档解析器；PostgreSQL/pgvector 使用真实实例，Embedding 和聊天生成使用明确替身。浏览器上传、查找、来源展示、删除已用接口替身验证，不能据此声称真实模型或 MCP 已联调。

## 本篇自查

1. PDF 解析和 Embedding 是不是同一件事？
2. RAG 是否把整个文件作为系统提示词？
3. 用户只改美食时，住宿资料会重新检索吗？
4. 为什么没有 owner_id 的老向量不能直接共享检索？
5. 上传或删除资料后，相同请求能否直接复用原方案？

## 自查解答

1. 不是。解析器从文件提取文字，Embedding 把文字转向量，生成模型再利用检索片段回答。文字型 PDF 不需要聊天模型解析；扫描件需要额外 OCR。
2. 不会。检索少量相关片段加入生成上下文，资料仍是参考数据，不拥有系统指令权限，也没有训练到模型参数里。
3. 资料版本没变时不会。只执行美食分支；需求提取与结果复用不触发住宿 Agent。
4. 无法证明这些资料属于当前用户。检索必须在向量库中按服务端身份与 READY 资料集合过滤，不能事后依靠模型保密。
5. 不能。资料版本变化会使旧结果失效并重新规划；同一版本、同一需求才复用。没有 READY 资料时不额外调用检索模型。
