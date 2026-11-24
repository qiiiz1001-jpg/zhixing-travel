# 知行旅行助手

知行把旅行需求、对话和最近完成的方案放在同一个工作区。通过对话补齐出发地、目的地、时间和人数，分开查看交通、住宿与美食。调整某类要求时，后端按受影响的分支重新规划。账号内可以管理私人攻略和长期偏好。

## 本地运行

需要 JDK 17、Maven、Node.js 22.18+ 和 Docker Compose。Windows 可以使用 Docker Desktop 或已有的 WSL Docker。以下命令在包含本工程 `pom.xml` 的目录执行。

```powershell
./tools/start-local.ps1 -StartMiddleware -Build
```

打开 [本机应用](http://localhost:10020/api/)，注册自己的账号。第一次执行会补齐本机 `.env` 的数据库、Redis 与签名配置。用户资料、计划和偏好保存到该工程独立的 PostgreSQL，近期记忆使用自己的 Redis。默认端口是 PostgreSQL 25432、Redis 26379。

启用真实规划前，在本工程 `.env` 填写 `DASHSCOPE_API_KEY`。地图、地点和餐厅查询使用高德地图，在 `.env` 中将高德 Web 服务 Key 填入 `AMAP_MAPS_API_KEY`。

国内机票、火车票、酒店可以共用一个途牛 Key。在[途牛开放平台](https://open.tuniu.com/mcp/)注册、创建应用并获取 API Key，然后填入 `TUNIU_API_KEY`。留空对应的 `TRAVEL_MCP_FLIGHT_URL`、`TRAVEL_MCP_TRAIN_URL`、`TRAVEL_MCP_HOTEL_URL` 时，应用自动接入途牛官方查询工具。

```powershell
./tools/start-local.ps1 -StartMiddleware -Models -Mcp
```

需要另外接入时间、天气、机票、住宿、火车票或搜索服务时，为对应的 `TRAVEL_MCP_TIME_URL`、`TRAVEL_MCP_WEATHER_URL`、`TRAVEL_MCP_FLIGHT_URL`、`TRAVEL_MCP_HOTEL_URL`、`TRAVEL_MCP_TRAIN_URL` 或 `TRAVEL_MCP_SEARCH_URL` 填入自己的 MCP 服务地址。若使用 ModelScope 托管的端点，同时配置该账号的 `TRAVEL_MCP_API_KEY`。未配置的服务不会被调用。

`TRAVEL_MCP_MAX_TOOL_ROUNDS` 控制一次规划中单个 Agent 的工具调用轮数，默认 8。修改 `.env` 后重启应用。高德或途牛账号的限流、套餐和配额由相应平台管理。

数据由 Docker 卷保存。关闭应用的 PowerShell 终端即可停止 Java 服务；后续启动会复用已有数据。更多实现说明见[学习文档](docs/learning/01-多Agent完整流程与运行.md)。

## 前端开发

Vue 3 源码在 [frontend](frontend)。Maven 打包时会调用 Vite，启动后从 `http://localhost:10020/api/` 打开页面。

单独调试页面时，先启动 Java 服务，再运行下面的命令。Vite 会把 API 请求转发到本机后端。

```powershell
cd frontend
npm ci --cache .npm-cache
npm run dev
```

开发地址为 `http://localhost:5174/`。

## 验证

启动本地中间件并至少成功启动一次应用，确认数据库迁移完成，再从工程目录运行：

```powershell
. ./tools/load-env.ps1
$env:SPRING_PROFILES_ACTIVE = 'infra'
$env:RUN_INFRA_TESTS = 'true'
mvn clean verify
```

请求模型与外部工具的结果取决于实际账号和工具端点权限。
