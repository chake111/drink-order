# drink-order

饮品店点单系统。用户端 H5、管理后台、后端接口与部署配置收在同一个仓库。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `backend/` | Spring Boot 3 + MyBatis + MySQL + Redis，端口 8080 |
| `frontend/user/` | 用户端 H5，Vue 3 + Vant |
| `frontend/admin/` | 管理后台，Vue 3 + Element Plus + ECharts |
| `deploy/` | nginx 配置、MySQL 初始化脚本、前端镜像 Dockerfile |

## 一键启动

```bash
docker compose up -d --build
```

| 入口 | 地址 |
| --- | --- |
| 用户端 | http://localhost/ |
| 管理端 | http://localhost/admin/ |
| 后端接口 | http://localhost/api/ |

改端口：`APP_PORT=8081 docker compose up -d --build`

演示账号：

- 管理端：`admin` / `123456`
- 用户端：`wx_user_001`（按 openid 登录，无密码）

## 本地开发

需要本机 MySQL（`drink_order` 库）和 Redis：

```bash
# 后端
mvn -f backend/pom.xml spring-boot:run

# 用户端（http://localhost:5173/）
cd frontend/user && npm install && npm run dev

# 管理端（http://localhost:3000/admin/）
cd frontend/admin && npm install && npm run dev
```

## 测试

```bash
mvn -f backend/pom.xml verify
```

测试固定连接 `127.0.0.1:3306/drink_order_perf` 与 Redis db 15；CI 里由 MySQL / Redis service 容器提供。

## 数据库初始化

首次启动 compose 时自动执行 `deploy/mysql/init/` 下的脚本，按文件名顺序建表、插入种子数据并补索引。