# 01：多 Agent 完整流程与运行

## 本篇目标

基本不读源码，先把需求补全、并行、子图、检查点和结果保存串成一个旅行场景。原文范围：环境与 Web 功能（7～1780 行）、多 Agent 与图（1781～4025 行）、Embedding/记忆/MCP（4026～4717 行）、RAG（4718～5562 行）、Checkpoint（5563 行起）。以 [指定原文](../../../../../项目/知行旅游助手项目代码/知行旅游助手项目代码.md) 与当前最终工程对照，不改原文，不重复创建已合入 AgentFactory 的线程池。

## 具体业务场景

用户说“去上海玩三天”，系统追问出发地、时间和人数。信息完整后，交通、住宿、美食各自规划；用户在页面看到完整汇总。缺失信息阶段保存流程，下次补充能继续。完成后说“美食改成清淡”，只更新美食；说“目的地改西安”，全部分支重新执行；单纯重试复用原结果。新增机制见 [02：规划修改与知识库边界](02-规划修改与知识库边界.md)；三层记忆与跨会话默认偏好见 [04：三层记忆与偏好规划](04-三层记忆与偏好规划.md)。

## 分步流程与入口

1. [聊天入口](../../src/main/java/cn/zhixing/travel/controller/ChatController.java) 读取已认证用户。[聊天服务](../../src/main/java/cn/zhixing/travel/service/impl/ChatServiceImpl.java) 验证会话归属，在单实例内按会话串行执行，保存用户消息并推进图。
2. [RequirementAgent](../../src/main/java/cn/zhixing/travel/agent/requirement/RequirementAgent.java) 的 collect 每轮识别新建、修改和重试，由 [需求合并规则](../../src/main/java/cn/zhixing/travel/agent/requirement/RequirementChanges.java) 合并增量、复核缺项并使受影响结果失效。条件边决定追问、进入 planParallel 或直接汇总复用结果；askMissing 后中断。输入通过 JSON 序列化。
3. [TravelWorkflowState](../../src/main/java/cn/zhixing/travel/agent/state/TravelWorkflowState.java) 分别保存原始需求、有效需求、偏好模式、缺失标记和分支结果。节点返回更新，图调度这些动作，不是模型自己任意决定全部代码顺序。
4. 交通子图组织自驾、火车和飞机，再做交通汇总；住宿与美食在父图中并行。每个分支入口先条件路由，未受影响时复用结果，不调用 Agent。子图适配节点只交回自己拥有的结果字段。assemble 检查完整性后汇总，业务层只把 PLAN 完成方案写入 latest_plan，纯记忆操作、澄清和追问都不会覆盖方案。
5. [AgentFactory](../../src/main/java/cn/zhixing/travel/config/AgentFactory.java) 给角色装配记忆和工具集合；Redis 保存角色+会话消息，窗口过期后可从 SQL 回填有效范围内的近期已完成业务对话。[检查点配置](../../src/main/java/cn/zhixing/travel/config/CheckpointConfig.java) 使用 PostgreSQL 保存父图快照，sessionId 关联 threadId。追问后 resume 回到 collect；完成后或失败后新消息携带旧 State 从 collect 重新分析。子图内部不单独存检查点，父分支与交通子任务使用不同线程池，避免嵌套等待耗尽同一个池。
6. [幂等检查点 SQL](../../src/main/resources/db/02-checkpoints.sql) 在 Saver 之前初始化表和索引。框架 1.8.0 的内部建索引不幂等，因此关闭内部重复建表；原文先建外键引用方的顺序也需修正。
7. [私人资料检索器](../../src/main/java/cn/zhixing/travel/rag/RagContentRetriever.java) 按用户与 READY 资料过滤，再查询向量；[上传与切片](../../src/main/java/cn/zhixing/travel/knowledge/TravelKnowledgeService.java) 保存来源及可持久化父段。支持 22 种常见文档扩展名；不再自动导入两份示例攻略。HyDE 和精排默认关闭，动态价格、车次、天气和房态仍需实时工具。详见 [03：私人资料上传与 RAG 检索](03-私人资料上传与RAG检索.md)。

```text
新消息 + 旧 State + 三层记忆 → 每轮分析
  → 纯记忆操作或澄清：保存/回复后结束，不改 latest_plan
  → 正常规划：新建初始化 / 修改合并有效偏好 / 相同有效输入复用
  → 缺项？是 → 追问 → 父图检查点暂停 → 补充后恢复 collect
          否 → 受影响分支执行 + 其余结果复用 → 校验 → 汇总 → 保存完成方案
Redis 保存模型聊天背景；PostgreSQL 检查点保存程序进度；业务表保存最近完成方案。
```

## 关键设计和边界

当前是固定角色分工配合图调度，独立角色的提示词、工具和消息记忆各有范围。检查点不自动保证外部工具副作用恰好一次；固定锁条带只解决单实例内同会话并发，多实例还要另外协调。最终方案目前以完成后返回为主，不因接口名含 stream 或返回 Flux 就称作逐 Token 图执行输出。

已用真实 PostgreSQL 验证新图和新 Saver 读取暂停状态后继续，以及读取完成状态后局部修改；图内模型与工具采用明确替身。当前全量测试 84 项通过并打包成功，详见 [当前验证记录](04-三层记忆与偏好规划.md)。此前页面注册、登录和会话已连接真实数据库/Redis；真实模型和 MCP 按当前安排稍后联调。此前记录见 [运行与交付记录](../../../../../简历/面试准备/两个项目运行与交付说明-2026-11-17.md)。

## 本篇自查

1. 不看源码，说明一次缺失信息补全怎样继续旧流程。
2. 为什么既存 Redis 记忆，又存 PostgreSQL 检查点和最新方案？
3. 两个请求同时推进一个会话有什么风险？
4. 当前真实验证与模型验证的边界是什么？

## 自查解答

1. askMissing 后暂停并保存；下一轮用相同 sessionId 找到 threadId 的下一节点，resume 合并本轮输入，再继续 collect 和后续规划。
2. 记忆给模型语义背景，检查点给程序执行位置和状态，最新方案给业务展示结果，三者用途不同。
3. 可能覆盖需求与图进度；当前单实例按会话串行，扩容时仍需跨实例版本或任务互斥方案。
4. 中间件与持久化已真实运行，图内模型和工具是测试替身；真实答案质量、工具数据和端到端模型时延稍后联调。
