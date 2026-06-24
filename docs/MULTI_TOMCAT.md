# 多 Tomcat 並行管理

## 架構

```
首頁（Tomcat 實例列表）
  └─ /tomcats/{id}（Service 勾選 / 套用 / 啟停）
        └─ 獨立 JVM + CATALINA_BASE + server-fragments
Nginx（單一進程）聚合所有實例的 PATH_PROXY → 各實例 gatewayPort
```

## 儲存路徑

| 路徑 | 用途 |
|------|------|
| `%USERPROFILE%\.tmam\projects.json` | v2 設定（`tomcatInstances`） |
| `%USERPROFILE%\.tmam\instances\{id}\catalina-base\` | 可寫入的 CATALINA_BASE |
| `%USERPROFILE%\.tmam\instances\{id}\server-fragments\` | Service 片段 |
| `%USERPROFILE%\.tmam\instances\{id}\backups\` | server.xml 備份 |
| `%USERPROFILE%\.tmam\projects.json.v1.bak` | 自動遷移備份 |

## 從 v1 遷移

首次啟動 v2 時，會將舊的 `catalinaHome` + `services` 轉為 `tomcatInstances.default`，並搬移：

- `server-fragments/` → `instances/default/server-fragments/`
- `native-tomcat/` → `instances/default/catalina-base/`

## API（節錄）

| 方法 | 路徑 | 說明 |
|------|------|------|
| GET | `/api/tomcats` | 實例列表 |
| GET | `/api/tomcats/discover` | 掃描本機 Tomcat |
| POST | `/api/tomcats` | 註冊新實例並 import |
| POST | `/api/tomcats/{id}/apply` | 套用勾選並啟動 |
| POST | `/api/tomcats/{id}/services` | 新增系統（`LEGACY_IP` 或 `PATH_PROXY`） |

舊版 `/api/tomcat/*` 仍委派至 `default` 實例。

## Port 分配

- 新實例 `gatewayPort`：8080 起遞增避開已用
- 新實例 `shutdownPort`：8005 起遞增；匯入時從 `server-header.xml` 讀取
- `detectPortConflicts` 跨實例檢查 shutdown、gateway、LEGACY_IP

## 新增系統

- **路徑型**：Nginx `:80` + PathGateway（實例 `gatewayPort`）
- **IP 型**：需網卡綁定 IP；使用者自行新增的 IP 型可編輯/刪除，匯入的僅能勾選
