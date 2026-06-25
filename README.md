# TMAM — Tomcat Service Manager

桌面應用程式，用於管理現有 Apache Tomcat 內多個 Service 的啟動、停止與選擇性載入。

## 適用場景

- **Native 模式（預設）**：管理一台或多台 Tomcat，每台可勾選要啟動的 Service，**多台可同時並行運行**。
- **Multi-instance 模式（舊）**：每專案獨立 Tomcat 實例 + WAR 部署（保留供開發測試）。

詳見 [docs/MULTI_TOMCAT.md](docs/MULTI_TOMCAT.md)。

## 技術棧

- **後端：** Java 17 + Spring Boot 3.5 + Maven Wrapper
- **前端：** Vue 3 + Vite + Element Plus + Electron 31
- **受管 Tomcat：** 由使用者於首次啟動時選擇安裝路徑

## 環境需求

- Java 17（`JAVA_HOME` 已設定）
- Node.js 18+

## 快速開始

```powershell
# 終端 1 — 後端
cd backend
.\mvnw.cmd spring-boot:run

# 終端 2 — 前端
cd frontend
npm install
npm run dev
```

開啟 http://localhost:5173

或使用 Electron 一鍵開發：

```powershell
cd frontend
npm run electron:dev:full
```

## Native 模式使用方式

1. **首次啟動**請選擇本機 Tomcat 安裝目錄（掃描或手動輸入），路徑會儲存於 `%USERPROFILE%\.tmam\projects.json`，之後無需重選。
2. 首頁顯示 **Tomcat 實例列表**；可再新增其他 Tomcat 安裝路徑。
3. 點 **管理 Service** 進入該實例，勾選要啟動的 Service。
4. 點 **套用勾選並啟動**；各實例可獨立啟停。
5. **新增系統** 可選路徑型（Nginx）或 IP 型（需網卡 IP）。

從 v1 升級的使用者會自動遷移既有 `catalinaHome` 設定（見 MULTI_TOMCAT.md）。

### 重要限制

- Tomcat **無法**在執行中單獨關閉某一個 IP 型 Service；切換必須重啟整台 JVM。
- TMAM 會依勾選項目**動態組裝** `conf/server.xml`；手動修改後請點「重新匯入」（API: `POST /api/tomcat/import`）。
- 至少需保留 **1 個** enabled 的 IP 型 Service。
- 不需要 WAR；沿用現有 `webapps` / `docBase` 設定。

## Phase 2：路徑型系統（Nginx 分流）

新增系統可走 **路徑型（PATH_PROXY）**，不需在網卡新增 IP：

1. 安裝 Nginx，並在 `application.yml` 設定 `tmam.nginx.executable`
2. 在 **Service 管理** 點 **新增路徑型系統**，填寫路徑前綴與 webapp 目錄
3. 點 **套用勾選並啟動**

- 既有 9 個 IP 型 Service **完全不變**，仍直連 `IP:Port`
- 新路徑型系統對外為 `http://伺服器:80/路徑前綴/`
- 詳見 [docs/PHASE2.md](docs/PHASE2.md)

## 設定檔

| 檔案 | 用途 |
|------|------|
| `%USERPROFILE%\.tmam\projects.json` | v2 設定（`tomcatInstances`） |
| `%USERPROFILE%\.tmam\instances\{id}\` | 每實例 catalina-base、fragments |
| `%USERPROFILE%\.tmam\nginx\` | Nginx 主設定與 location 分流規則 |
| `%USERPROFILE%\.tmam\instances\{id}\backups\` | 每實例 server.xml 備份 |

### application.yml 關鍵項

```yaml
tmam:
  mode: native
  default-catalina-home: "C:/Program Files/apache-tomcat-9.0.115"
  startup-timeout-sec: 90
  nginx:
    enabled: true
    executable: "C:/nginx/nginx.exe"
    listen-port: 80
```

## API 端點（Native 模式）

| 方法 | 路徑 | 說明 |
|------|------|------|
| GET | `/api/tomcat/services` | 列出 Service 與狀態 |
| POST | `/api/tomcat/services` | 新增路徑型（PATH_PROXY）系統 |
| PUT | `/api/tomcat/services/{name}` | 更新路徑型系統 |
| DELETE | `/api/tomcat/services/{name}` | 刪除路徑型系統 |
| PUT | `/api/tomcat/services/enabled` | 更新勾選狀態 |
| POST | `/api/tomcat/start` | 組裝 server.xml 並啟動 |
| POST | `/api/tomcat/stop` | 停止 Tomcat |
| POST | `/api/tomcat/apply` | 套用勾選（停機→組裝→Nginx→啟動） |
| POST | `/api/tomcat/services/{name}/toggle` | 切換單一 Service |
| POST | `/api/tomcat/import` | 重新從 server.xml 匯入 |
| POST | `/api/tomcat/restore-original` | 還原原始 server.xml |
| GET | `/api/tomcat/logs` | 讀取 catalina log |
| GET | `/api/nginx/status` | Nginx 狀態 |
| POST | `/api/nginx/apply` | 重寫 Nginx 並 reload |

## 專案結構

```
tmam/
├── backend/     # Spring Boot REST API（port 8899）
├── frontend/    # Vue 3 + Electron UI
├── docs/        # 架構與 Phase 2 說明
└── build/       # Electron 打包輸出
```

## 開發與測試

### 後端單元測試

```powershell
cd backend
.\mvnw.cmd test
```

預設會排除標記為 `integration` 的測試（需本機安裝 Tomcat）。若要執行整合測試：

```powershell
.\mvnw.cmd test -Dgroups=integration
```

### CI

Push 或 Pull Request 至 `main`/`master` 時，GitHub Actions 會在 `windows-latest` 上執行後端單元測試與前端 build（見 [.github/workflows/ci.yml](.github/workflows/ci.yml)）。

### Release

推送版本 tag 會自動建置 Windows 桌面安裝包並發佈至 GitHub Releases：

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Release workflow 會將 `frontend/package.json` 的 version 同步為 tag 版本（去掉 `v` 前綴），產出 `build/TMAM Setup x.x.x.exe`（見 [.github/workflows/release.yml](.github/workflows/release.yml)）。

## 桌面應用打包

```powershell
cd frontend
npm run build:desktop
```
