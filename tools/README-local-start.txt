本地启动 WxFbsir-admin 依赖 MySQL 8 与 Redis。

一、构建可执行 JAR（与常见「加载 jar 启动」方式一致）
  主节点 Admin（仓库根目录执行）:
    mvn -pl WxFbsir-admin -am package -DskipTests
  产物: WxFbsir-admin\target\WxFbsir-admin.jar

  Engine 副节点（独立工程，在 WxFbsir-engine 目录执行）:
    mvn -DskipTests package
  产物: WxFbsir-engine\target\wxfbsir-engine-<engine.version>.jar（版本见该模块 pom 的 engine.version）

二、启动
  JAR 方式（后端+引擎为 JAR；前端为打包静态 + vite preview，无独立 JAR）:
    tools\start-jar.ps1
    等价: tools\start-stack.ps1 -UiMode prod
    首次会从 WxFbsir-ui\.env.production.example 复制出 .env.production（若不存在）；
    也可手动复制并修改 VITE_APP_BASE_API。

  开发模式前端（Vite dev + 代理 /dev-api）:
    tools\start-stack.ps1
    或 -UiMode dev（默认）

  一键编排（检测 3306，必要时 Docker compose，再后台拉起 Admin JAR、Engine JAR，可选 UI）:
    tools\start-stack.ps1

  仅主节点:
  tools\start-admin.ps1
    默认: java -jar WxFbsir-admin\target\WxFbsir-admin.jar（若存在）
    自定义 JAR 路径: set WXFBSIR_ADMIN_JAR=D:\path\WxFbsir-admin.jar
    强制 Maven 源码运行: set WXFBSIR_USE_MAVEN_RUN=1  或  start-admin.ps1 -UseMavenRun
    JVM 附加参数: set WXFBSIR_JVM_OPTS=-Xms512m -Xmx1024m

  Engine: tools\start-engine.ps1
    或: set WXFBSIR_ENGINE_JVM_OPTS=... 与 WXFBSIR_ENGINE_JAR=...

三、依赖（MySQL / Redis）
方式 A（推荐，已安装 Docker Desktop）
  若本机 6379 已有 Redis，只拉 MySQL，避免端口冲突：
    docker compose --profile mysql up -d
  否则同时拉 MySQL+Redis：
    docker compose --profile full up -d
  （tools\start-local-deps.ps1 会按 6379 是否已占用自动选择）
  旧式「无 profile」已不再适用；服务均带 profile。
  1. 仓库根目录执行上述其一，或：
       若未把 docker 加入 PATH，仍可使用 tools\start-local-deps.ps1
    2. 等待约 1 分钟（首次会导入 sql/wxfbsir.sql）
    3. 再执行 tools\start-admin.ps1

  清空 Docker 卷并重新导入基线: tools\reset-local-docker.ps1 后再 start-local-deps

  方式 B（已有本机 MySQL/Redis）
    - 创建库 wxfbsir 并导入 sql/wxfbsir.sql
    - 默认连接见 application-druid.yml（可用环境变量 WXFBSIR_MYSQL_* 覆盖）
    - 仅使用本机服务、勿用 Docker 时: set WXFBSIR_SKIP_LOCAL_DEPS=1 后再运行 start-admin.ps1

  方式 C（本机已安装 MySQL 8.4 / 8.x，不用 Docker）
    1) 启动 MySQL 服务（未监听 3306 时 Admin 无法连库）:
         管理员 PowerShell: .\tools\start-mysql-service.ps1
       或 services.msc / net start MySQL84（服务名以本机为准）
    2) 将 MySQL Server 的 bin 加入 PATH（可选，脚本会尝试 8.4 默认路径）。
    3) 初始化库表（默认 root/root；若与安装时设置不一致请加 -Password）:
         .\tools\init-mysql-wxfbsir.ps1
         .\tools\init-mysql-wxfbsir.ps1 -Password 你的密码 -Port 3306
    4) 若 root 密码不是 root，启动 Admin 前设置:
         set WXFBSIR_MYSQL_PASSWORD=你的密码
    5) set WXFBSIR_SKIP_LOCAL_DEPS=1
       然后: .\tools\start-jar.ps1 或 .\tools\start-admin.ps1

故障排查
  - 3306 被占用: 修改本机 MySQL 端口或改 WXFBSIR_MYSQL_URL
  - Docker 数据卷已损坏: docker compose down -v 后重新 up（会丢库内数据）
