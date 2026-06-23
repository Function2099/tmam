# TMAM — Tomcat Multi-Instance Manager

## 開發實施計畫書 v2.0（Spring Boot + Vue 3 + Electron）

> **後端：** Java 17 + Spring Boot 3.x
> **前端：** Vue 3 + Vite + Electron
> **儲存：** 純 JSON 檔案
> **授權：** Apache License 2.0

---

## 目錄

1. [整體架構概覽](#一整體架構概覽)
2. [後端：Spring Boot API](#二後端spring-boot-api)
3. [前端：Vue 3 + Electron](#三前端vue-3--electron)
4. [資料結構設計](#四資料結構設計)
5. [API 端點規格](#五api-端點規格)
6. [程式碼骨架](#六程式碼骨架)
7. [開發階段規劃](#七開發階段規劃)

---

## 一、整體架構概覽

### 1.1 技術棧

| 層級     | 技術              | 版本        | 用途                                   |
| ------ | --------------- | --------- | ------------------------------------ |
| 桌面殼層   | Electron        | 31.x      | 將 Web UI 打包成 .exe / .dmg / .AppImage |
| 前端框架   | Vue 3 + Vite    | 3.x / 5.x | 管理介面 UI                              |
| UI 元件庫 | Element Plus    | 2.x       | 表格、對話框、狀態標籤                          |
| 後端框架   | Spring Boot     | 3.3.x     | REST API、進程管理                        |
| 語言版本   | Java            | 17 LTS    | 後端邏輯                                 |
| 資料儲存   | JSON 檔案         | —         | projects.json 儲存專案配置                 |
| 進程通訊   | HTTP（localhost） | —         | Electron ↔ Spring Boot               |

### 1.2 運作模式

```
┌─────────────────────────────────────────────────────┐
│                  Electron Shell                      │
│  ┌───────────────────────────────────────────────┐  │
│  │              Vue 3 Web UI                     │  │
│  │  （BrowserWindow 載入 localhost:8899）         │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │ HTTP / Axios                   │
│  ┌──────────────────▼────────────────────────────┐  │
│  │         Spring Boot API Server                │  │
│  │              port: 8899                       │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │  │
│  │  │Config    │ │Environ   │ │Process       │  │  │
│  │  │Manager   │ │Manager   │ │Manager       │  │  │
│  │  └──────────┘ └──────────┘ └──────┬───────┘  │  │
│  └─────────────────────────────────  │ ──────────┘  │
└─────────────────────────────────────  │ ────────────┘
                                        │ ProcessBuilder
                          ┌─────────────▼─────────────┐
                          │  Tomcat 實例 A（port 8081）│
                          │  Tomcat 實例 B（port 8082）│
                          │  Tomcat 實例 ...           │
                          └───────────────────────────┘
```

**關鍵設計決策：** Electron 啟動時同步以 `child_process.spawn` 拉起 Spring Boot JAR，關閉視窗時同步終止 Spring Boot 進程。所有業務邏輯均在 Spring Boot 端，Electron 僅作為殼層，Vue 3 負責 UI 渲染。

### 1.3 專案目錄結構

```
tmam/
├── backend/                        # Spring Boot 專案
│   ├── pom.xml
│   └── src/main/java/com/tmam/
│       ├── TmamApplication.java
│       ├── controller/
│       │   ├── ProjectController.java
│       │   └── InstanceController.java
│       ├── service/
│       │   ├── ConfigService.java
│       │   ├── EnvironmentService.java
│       │   ├── XmlConfiguratorService.java
│       │   └── ProcessService.java
│       ├── model/
│       │   ├── ProjectConfig.java
│       │   ├── PortConfig.java
│       │   ├── TmamConfig.java
│       │   └── InstanceStatus.java
│       └── resources/
│           ├── application.yml
│           └── server-template.xml
│
├── frontend/                       # Vue 3 + Electron 專案
│   ├── package.json
│   ├── vite.config.js
│   ├── electron/
│   │   ├── main.js                 # Electron 主進程
│   │   └── preload.js
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── api/
│       │   └── tmam.js             # Axios API 封裝
│       ├── views/
│       │   ├── Dashboard.vue       # 首頁：所有實例狀態
│       │   ├── ProjectList.vue     # 專案管理
│       │   └── Logs.vue            # 即時 Log 檢視
│       ├── components/
│       │   ├── InstanceCard.vue    # 單一實例狀態卡片
│       │   ├── AddProjectDialog.vue
│       │   └── PortConflictAlert.vue
│       └── stores/
│           └── instance.js         # Pinia 狀態管理
│
└── build/                          # 打包輸出
    ├── tmam-win.exe
    ├── tmam-mac.dmg
    └── tmam-linux.AppImage
```

---

## 二、後端：Spring Boot API

### 2.1 Spring Boot 專案設定

**application.yml**

```yaml
server:
  port: 8899

tmam:
  config-path: ${user.home}/.tmam/projects.json
  instances-root: ${user.home}/.tmam/instances
  pids-root: ${user.home}/.tmam/pids

spring:
  web:
    cors:
      allowed-origins: "*"   # 開發階段，Electron BrowserWindow 需要
```

**pom.xml 核心依賴**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.2</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- JSON 讀寫 -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- 測試 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2.2 Service 層設計

#### ConfigService

```java
@Service
public class ConfigService {

    @Value("${tmam.config-path}")
    private String configPath;

    private final ObjectMapper mapper = new ObjectMapper();

    public TmamConfig load() throws IOException {
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            // 首次啟動：建立預設空配置
            TmamConfig defaultConfig = new TmamConfig();
            save(defaultConfig);
            return defaultConfig;
        }
        return mapper.readValue(path.toFile(), TmamConfig.class);
    }

    public void save(TmamConfig config) throws IOException {
        Path path = Path.of(configPath);
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter()
              .writeValue(path.toFile(), config);
    }

    public List<PortConflict> detectPortConflicts(TmamConfig config) {
        Map<Integer, String> registry = new HashMap<>();
        List<PortConflict> conflicts = new ArrayList<>();

        config.getProjects().forEach((name, project) -> {
            PortConfig ports = project.getPorts();
            checkPort(ports.getHttp(),     "HTTP",     name, registry, conflicts);
            checkPort(ports.getShutdown(), "Shutdown", name, registry, conflicts);
            checkPort(ports.getAjp(),      "AJP",      name, registry, conflicts);
        });
        return conflicts;
    }

    private void checkPort(int port, String type, String projectName,
                           Map<Integer, String> registry, List<PortConflict> conflicts) {
        if (registry.containsKey(port)) {
            conflicts.add(new PortConflict(port, type, registry.get(port), projectName));
        } else {
            registry.put(port, projectName);
        }
    }
}
```

#### ProcessService

```java
@Service
public class ProcessService {

    private static final String SUCCESS_MARKER = "Server startup in";
    private static final int TIMEOUT_SEC = 30;

    @Value("${tmam.instances-root}")
    private String instancesRoot;

    @Value("${tmam.pids-root}")
    private String pidsRoot;

    // 用於追蹤運行中的進程（重啟 Spring Boot 後 PID 文件仍可追蹤）
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public StartResult start(ProjectConfig project) throws IOException, InterruptedException {
        String name = project.getName();
        Path catalinaBase = Path.of(instancesRoot, name);
        Path catalinaHome = Path.of(project.getCatalinaHome());

        // 選擇對應 OS 的啟動腳本
        String script = System.getProperty("os.name").toLowerCase().contains("win")
            ? catalinaHome.resolve("bin/catalina.bat").toString()
            : catalinaHome.resolve("bin/catalina.sh").toString();

        ProcessBuilder pb = new ProcessBuilder(script, "start");
        pb.environment().put("CATALINA_HOME", catalinaHome.toString());
        pb.environment().put("CATALINA_BASE", catalinaBase.toString());
        pb.environment().put("JAVA_OPTS", project.getJvmOpts());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(name, process);

        // 寫入 PID 文件
        writePid(name, process.pid());

        // 監控啟動日誌
        return monitorStartup(name, catalinaBase, process);
    }

    public void stop(String projectName) throws IOException, InterruptedException {
        long pid = readPid(projectName);
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        Files.deleteIfExists(Path.of(pidsRoot, projectName + ".pid"));
        activeProcesses.remove(projectName);
    }

    public InstanceStatus status(String projectName) {
        try {
            long pid = readPid(projectName);
            boolean running = ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive)
                .orElse(false);
            return running ? InstanceStatus.RUNNING : InstanceStatus.STOPPED;
        } catch (IOException e) {
            return InstanceStatus.STOPPED;
        }
    }

    private StartResult monitorStartup(String name, Path catalinaBase, Process process)
            throws InterruptedException, IOException {
        Path logFile = catalinaBase.resolve("logs/catalina.out");
        long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;

        // 等待 log 檔案出現
        while (!Files.exists(logFile) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile.toFile()))) {
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line != null && line.contains(SUCCESS_MARKER)) {
                    return StartResult.success(name);
                }
                if (line == null) Thread.sleep(300);
            }
        }
        return StartResult.timeout(name, getLastLines(logFile, 20));
    }

    private void writePid(String name, long pid) throws IOException {
        Path pidsPath = Path.of(pidsRoot);
        Files.createDirectories(pidsPath);
        Files.writeString(pidsPath.resolve(name + ".pid"), String.valueOf(pid));
    }

    private long readPid(String name) throws IOException {
        return Long.parseLong(
            Files.readString(Path.of(pidsRoot, name + ".pid")).trim()
        );
    }
}
```

#### XmlConfiguratorService

```java
@Service
public class XmlConfiguratorService {

    @Value("classpath:server-template.xml")
    private Resource templateResource;

    public void generate(ProjectConfig project, Path outputPath)
            throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(templateResource.getInputStream());

        // 修改 Shutdown Port
        doc.getDocumentElement()
           .setAttribute("port", String.valueOf(project.getPorts().getShutdown()));

        // 修改 HTTP / AJP Connector Port
        NodeList connectors = doc.getElementsByTagName("Connector");
        for (int i = 0; i < connectors.getLength(); i++) {
            Element c = (Element) connectors.item(i);
            String protocol = c.getAttribute("protocol");
            if ("HTTP/1.1".equals(protocol)) {
                c.setAttribute("port", String.valueOf(project.getPorts().getHttp()));
            } else if (protocol.startsWith("AJP")) {
                c.setAttribute("port", String.valueOf(project.getPorts().getAjp()));
            }
        }

        // 寫出格式化 XML
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        Files.createDirectories(outputPath.getParent());
        transformer.transform(
            new DOMSource(doc),
            new StreamResult(outputPath.toFile())
        );
    }
}
```

---

## 三、前端：Vue 3 + Electron

### 3.1 Electron 主進程（electron/main.js）

```javascript
const { app, BrowserWindow } = require('electron')
const { spawn } = require('child_process')
const path = require('path')

let mainWindow
let springBootProcess

// 啟動 Spring Boot JAR
function startBackend() {
  const jarPath = path.join(process.resourcesPath, 'backend.jar')
  springBootProcess = spawn('java', ['-jar', jarPath], {
    stdio: 'pipe'
  })

  springBootProcess.stdout.on('data', (data) => {
    console.log('[Spring Boot]', data.toString())
  })

  springBootProcess.stderr.on('data', (data) => {
    console.error('[Spring Boot Error]', data.toString())
  })
}

// 等待後端就緒後開啟視窗
async function waitForBackend(retries = 20) {
  for (let i = 0; i < retries; i++) {
    try {
      const res = await fetch('http://localhost:8899/api/health')
      if (res.ok) return true
    } catch (e) { /* 尚未就緒 */ }
    await new Promise(r => setTimeout(r, 500))
  }
  return false
}

app.whenReady().then(async () => {
  startBackend()

  const ready = await waitForBackend()
  if (!ready) {
    console.error('Spring Boot 啟動失敗')
    app.quit()
    return
  }

  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'TMAM - Tomcat Manager',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true
    }
  })

  mainWindow.loadURL('http://localhost:8899')  // Spring Boot 同時提供靜態前端資源
})

// 關閉視窗時終止 Spring Boot
app.on('will-quit', () => {
  if (springBootProcess) springBootProcess.kill()
})
```

> **靜態資源整合策略：** Vue 3 打包後的 `dist/` 複製至 Spring Boot `src/main/resources/static/`，由 Spring Boot 一併提供前端靜態資源，Electron 只需開啟 `localhost:8899` 即可，不需要額外的 Vite Dev Server。

### 3.2 API 封裝（src/api/tmam.js）

```javascript
import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8899/api',
  timeout: 35000   // 略大於後端 30 秒啟動超時
})

export const projectApi = {
  list: ()                  => api.get('/projects'),
  add:  (project)           => api.post('/projects', project),
  remove: (name)            => api.delete(`/projects/${name}`),
  checkConflicts: ()        => api.get('/projects/port-conflicts'),
}

export const instanceApi = {
  start:   (name)  => api.post(`/instances/${name}/start`),
  stop:    (name)  => api.post(`/instances/${name}/stop`),
  restart: (name)  => api.post(`/instances/${name}/restart`),
  status:  (name)  => api.get(`/instances/${name}/status`),
  allStatus: ()    => api.get('/instances/status'),
  logs:    (name)  => api.get(`/instances/${name}/logs`),
}
```

### 3.3 Dashboard.vue（核心頁面骨架）

```vue
<template>
  <div class="dashboard">
    <el-row :gutter="16">
      <el-col :span="6" v-for="project in projects" :key="project.name">
        <InstanceCard
          :project="project"
          :status="statusMap[project.name]"
          @start="handleStart"
          @stop="handleStop"
        />
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { projectApi, instanceApi } from '@/api/tmam'
import InstanceCard from '@/components/InstanceCard.vue'
import { ElMessage } from 'element-plus'

const projects = ref([])
const statusMap = ref({})
let pollingTimer = null

onMounted(async () => {
  await refreshProjects()
  // 每 3 秒輪詢一次狀態
  pollingTimer = setInterval(refreshStatus, 3000)
})

onUnmounted(() => clearInterval(pollingTimer))

async function refreshProjects() {
  const { data } = await projectApi.list()
  projects.value = data
  await refreshStatus()
}

async function refreshStatus() {
  const { data } = await instanceApi.allStatus()
  statusMap.value = data  // { "crm-system": "RUNNING", "erp-backend": "STOPPED" }
}

async function handleStart(name) {
  ElMessage.info(`正在啟動 ${name}...`)
  try {
    await instanceApi.start(name)
    ElMessage.success(`${name} 啟動成功`)
    await refreshStatus()
  } catch (e) {
    ElMessage.error(`啟動失敗：${e.response?.data?.message}`)
  }
}

async function handleStop(name) {
  await instanceApi.stop(name)
  ElMessage.success(`${name} 已停止`)
  await refreshStatus()
}
</script>
```

### 3.4 InstanceCard.vue

```vue
<template>
  <el-card class="instance-card" shadow="hover">
    <div class="card-header">
      <span class="project-name">{{ project.displayName }}</span>
      <el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag>
    </div>

    <div class="card-info">
      <span>HTTP Port：<strong>{{ project.ports.http }}</strong></span>
      <el-link :href="`http://localhost:${project.ports.http}`" target="_blank">
        開啟瀏覽器
      </el-link>
    </div>

    <div class="card-actions">
      <el-button
        type="primary" size="small"
        :disabled="status === 'RUNNING'"
        :loading="loading"
        @click="$emit('start', project.name)"
      >啟動</el-button>

      <el-button
        type="danger" size="small"
        :disabled="status === 'STOPPED'"
        @click="$emit('stop', project.name)"
      >停止</el-button>

      <el-button size="small" @click="viewLogs">查看 Log</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  project: Object,
  status: String   // 'RUNNING' | 'STOPPED' | 'STARTING' | 'ERROR'
})
defineEmits(['start', 'stop'])

const router = useRouter()

const statusType = computed(() => ({
  RUNNING:  'success',
  STOPPED:  'info',
  STARTING: 'warning',
  ERROR:    'danger'
}[props.status] ?? 'info'))

const statusLabel = computed(() => ({
  RUNNING:  '運行中',
  STOPPED:  '已停止',
  STARTING: '啟動中...',
  ERROR:    '異常'
}[props.status] ?? '未知'))

const viewLogs = () => router.push(`/logs/${props.project.name}`)
</script>
```

### 3.5 package.json（前端）

```json
{
  "name": "tmam-frontend",
  "version": "1.0.0",
  "scripts": {
    "dev":     "vite",
    "build":   "vite build",
    "electron:dev":   "electron .",
    "electron:build": "electron-builder"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "element-plus": "^2.7.0",
    "axios": "^1.7.0",
    "pinia": "^2.2.0",
    "vue-router": "^4.4.0"
  },
  "devDependencies": {
    "vite": "^5.3.0",
    "@vitejs/plugin-vue": "^5.1.0",
    "electron": "^31.0.0",
    "electron-builder": "^24.13.0"
  }
}
```

---

## 四、資料結構設計

### 4.1 projects.json

```json
{
  "version": "1.0.0",
  "catalinaHome": "/opt/tomcat",
  "defaults": {
    "jvmOpts": "-Xms256m -Xmx512m -server",
    "startupTimeoutSec": 30
  },
  "projects": {
    "crm-system": {
      "displayName": "CRM 客戶關係管理系統",
      "warPath": "/Users/dev/wars/crm.war",
      "contextPath": "ROOT",
      "ports": {
        "http": 8081,
        "shutdown": 8005,
        "ajp": 8009
      },
      "jvmOpts": "-Xms512m -Xmx1024m",
      "enabled": true
    },
    "erp-backend": {
      "displayName": "ERP 後台系統",
      "warPath": "/Users/dev/wars/erp.war",
      "contextPath": "ROOT",
      "ports": {
        "http": 8082,
        "shutdown": 8015,
        "ajp": 8019
      },
      "jvmOpts": "-Xms256m -Xmx512m",
      "enabled": true
    }
  }
}
```

### 4.2 Java Model

```java
// TmamConfig.java
public class TmamConfig {
    private String version = "1.0.0";
    private String catalinaHome;
    private DefaultConfig defaults;
    private Map<String, ProjectConfig> projects = new LinkedHashMap<>();
    // getters / setters
}

// ProjectConfig.java
public class ProjectConfig {
    @JsonIgnore
    private String name;          // 從 Map key 注入，不存入 JSON
    private String displayName;
    private String warPath;
    private String contextPath;
    private PortConfig ports;
    private String jvmOpts;
    private boolean enabled;
    // getters / setters
}

// PortConfig.java
public record PortConfig(int http, int shutdown, int ajp) {}

// InstanceStatus.java
public enum InstanceStatus { RUNNING, STOPPED, STARTING, ERROR }

// StartResult.java
public record StartResult(boolean success, String projectName, String message) {
    public static StartResult success(String name) {
        return new StartResult(true, name, "啟動成功");
    }
    public static StartResult timeout(String name, List<String> lastLogs) {
        return new StartResult(false, name, "啟動超時，最後日誌：\n" + String.join("\n", lastLogs));
    }
}
```

---

## 五、API 端點規格

### 專案管理 `/api/projects`

| 方法       | 路徑                             | 說明         | 回應                    |
| -------- | ------------------------------ | ---------- | --------------------- |
| `GET`    | `/api/projects`                | 取得所有專案清單   | `List<ProjectConfig>` |
| `POST`   | `/api/projects`                | 新增專案       | `ProjectConfig`       |
| `PUT`    | `/api/projects/{name}`         | 更新專案配置     | `ProjectConfig`       |
| `DELETE` | `/api/projects/{name}`         | 刪除專案配置     | `204 No Content`      |
| `GET`    | `/api/projects/port-conflicts` | 偵測 Port 衝突 | `List<PortConflict>`  |

### 實例控制 `/api/instances`

| 方法     | 路徑                              | 說明           | 回應                            |
| ------ | ------------------------------- | ------------ | ----------------------------- |
| `GET`  | `/api/instances/status`         | 取得所有實例狀態     | `Map<String, InstanceStatus>` |
| `GET`  | `/api/instances/{name}/status`  | 取得單一實例狀態     | `InstanceStatus`              |
| `POST` | `/api/instances/{name}/start`   | 啟動實例         | `StartResult`                 |
| `POST` | `/api/instances/{name}/stop`    | 停止實例         | `204 No Content`              |
| `POST` | `/api/instances/{name}/restart` | 重啟實例         | `StartResult`                 |
| `GET`  | `/api/instances/{name}/logs`    | 取得最後 N 行 Log | `List<String>`                |

### Controller 骨架

```java
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final ProcessService processService;
    private final EnvironmentService environmentService;
    private final ConfigService configService;

    @PostMapping("/{name}/start")
    public ResponseEntity<StartResult> start(@PathVariable String name) throws Exception {
        ProjectConfig project = configService.load().getProjects().get(name);
        if (project == null) return ResponseEntity.notFound().build();

        // 確保 CATALINA_BASE 環境已建立
        environmentService.initialize(project);

        StartResult result = processService.start(project);
        return result.success()
            ? ResponseEntity.ok(result)
            : ResponseEntity.status(500).body(result);
    }

    @PostMapping("/{name}/stop")
    public ResponseEntity<Void> stop(@PathVariable String name) throws Exception {
        processService.stop(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, InstanceStatus>> allStatus() throws IOException {
        TmamConfig config = configService.load();
        Map<String, InstanceStatus> statusMap = new LinkedHashMap<>();
        config.getProjects().keySet()
              .forEach(name -> statusMap.put(name, processService.status(name)));
        return ResponseEntity.ok(statusMap);
    }

    @GetMapping("/{name}/logs")
    public ResponseEntity<List<String>> logs(
            @PathVariable String name,
            @RequestParam(defaultValue = "100") int lines) throws IOException {
        return ResponseEntity.ok(processService.getLastLines(name, lines));
    }
}
```

---

## 六、程式碼骨架

### 6.1 主入口

```java
@SpringBootApplication
public class TmamApplication {
    public static void main(String[] args) {
        SpringApplication.run(TmamApplication.class, args);
    }
}
```

### 6.2 健康檢查端點（供 Electron 輪詢）

```java
@RestController
public class HealthController {
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", "1.0.0");
    }
}
```

### 6.3 CORS 全域設定

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

### 6.4 前端路由（src/router/index.js）

```javascript
import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),   // Electron 需用 Hash 模式
  routes: [
    { path: '/',              component: () => import('@/views/Dashboard.vue') },
    { path: '/projects',      component: () => import('@/views/ProjectList.vue') },
    { path: '/logs/:name',    component: () => import('@/views/Logs.vue') },
  ]
})
```

> **注意：** Electron 中使用 `createWebHashHistory` 而非 `createWebHistory`，避免直接存取 URL 路徑時出現 404。

---

## 七、開發階段規劃

| Phase                   | 工作項目                                                                                                         | 交付物                                      |
| ----------------------- | ------------------------------------------------------------------------------------------------------------ | ---------------------------------------- |
| **Phase 1** 後端核心        | `ConfigService`（JSON 讀寫）、`XmlConfiguratorService`（server.xml 生成）、`EnvironmentService`（CATALINA_BASE 建立）、單元測試 | 可通過測試的三個 Service                         |
| **Phase 2** 進程管理        | `ProcessService`（啟動、停止、狀態監控、PID 管理）、整合測試（真實啟動一個 Tomcat 實例）                                                   | `POST /api/instances/{name}/start` 可正常運作 |
| **Phase 3** REST API    | 實作 `ProjectController`、`InstanceController`、`HealthController`、CORS 設定                                       | Postman 可驗證所有端點                          |
| **Phase 4** Vue 3 前端    | `Dashboard.vue`、`InstanceCard.vue`、`ProjectList.vue`、`Logs.vue`、Pinia 狀態管理、API 封裝                            | `npm run dev` 可操作完整 UI                   |
| **Phase 5** Electron 整合 | `electron/main.js`（拉起 Spring Boot）、`waitForBackend` 輪詢邏輯、視窗關閉時終止後端                                           | `npm run electron:dev` 可開啟桌面視窗           |
| **Phase 6** 打包與發佈       | `electron-builder` 配置、Maven Shade 打包 Spring Boot JAR、GitHub Actions 自動化 Release                              | 三平台可安裝檔案（.exe / .dmg / .AppImage）        |

### 驗收標準

- 開啟桌面應用，10 秒內顯示所有專案的狀態卡片
- 點擊「啟動」後，30 秒內狀態由 `已停止` 轉為 `運行中`，點擊「開啟瀏覽器」可正常存取
- 新增第 9 個專案後，`GET /api/projects/port-conflicts` 回傳空陣列
- 關閉 Electron 視窗，Spring Boot 進程同步終止（`ps aux` 查無殘留）
- 在 Windows 10、macOS 13、Ubuntu 22.04 均可安裝並正常執行

---

*文件版本 v2.0 · Spring Boot + Vue 3 + Electron · JSON 儲存*
