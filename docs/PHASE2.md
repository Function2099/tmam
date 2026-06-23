# Phase 2：路徑分流新增系統（已實作）

## 背景

Phase 1 管理既有的 9 個 `LEGACY_IP` Service，每個綁定獨立 IP（如 `192.168.10.10:36`）。  
Phase 2 讓新系統透過 TMAM 新增，**不需**修改網卡 IP。

## 架構

```
瀏覽器 → Nginx (:80) → Tomcat PathGateway (127.0.0.1:8080)
              ├─ /new-sys/*  → Context /new-sys
              └─ /other/*    → Context /other

既有 9 個 LEGACY_IP Service：維持直連 IP:Port，不經 Nginx
```

## 使用方式

### 環境前置

1. 安裝 [Nginx for Windows](https://nginx.org/en/download.html)
2. 在 `backend/src/main/resources/application.yml` 設定：

```yaml
tmam:
  nginx:
    enabled: true
    executable: "C:/nginx/nginx.exe"
    listen-port: 80
```

3. 確認 80 port 未被占用（或修改 `listen-port`）

### 新增路徑型系統

1. 開啟 TMAM **Service 管理** 頁面
2. 點 **新增路徑型系統**
3. 填寫：系統名稱、路徑前綴（如 `/new-system`）、webapp 目錄（`docBase`）
4. 點 **套用勾選並啟動**

TMAM 會：

- 在 `%USERPROFILE%\.tmam\server-fragments\PathGateway.xml` 產生 Tomcat Context
- 在 `%USERPROFILE%\.tmam\nginx\tmam-locations.conf` 產生 Nginx location
- 組裝 `server.xml`（legacy 片段 + PathGateway）
- reload Nginx 並啟動 Tomcat

### 注意事項

- 新 app 應支援以子路徑部署（靜態資源、API 路徑）
- 若 app 假設掛在根路徑，可啟用「剝除前綴轉發」
- `POST /api/tomcat/import` **不會**刪除已新增的 PATH_PROXY 項目
- 至少需要保留 **1 個啟用的 IP 型 Service**

## API

| 方法 | 路徑 | 說明 |
|------|------|------|
| POST | `/api/tomcat/services` | 新增 PATH_PROXY |
| PUT | `/api/tomcat/services/{name}` | 更新 PATH_PROXY |
| DELETE | `/api/tomcat/services/{name}` | 刪除 PATH_PROXY |
| GET | `/api/nginx/status` | Nginx 狀態 |
| POST | `/api/nginx/apply` | 重寫 Nginx 設定並 reload |

## 設定檔

| 檔案 | 用途 |
|------|------|
| `%USERPROFILE%\.tmam\projects.json` | PATH_PROXY 定義（含 pathPrefix、docBase） |
| `%USERPROFILE%\.tmam\server-fragments\PathGateway.xml` | Tomcat 路徑型 Context 片段 |
| `%USERPROFILE%\.tmam\nginx\nginx.conf` | TMAM 管理的 Nginx 主設定 |
| `%USERPROFILE%\.tmam\nginx\tmam-locations.conf` | location 分流規則 |

## 與 Phase 1 的差異

| 項目 | Phase 1 | Phase 2 |
|------|---------|---------|
| 新增方式 | 僅匯入現有 server.xml | UI 新增 PATH_PROXY |
| 網路 | 每 Service 一個 IP | Nginx :80 + 路徑區分 |
| server.xml | 僅 legacy 片段 | legacy + PathGateway |
