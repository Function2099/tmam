const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron')
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')
const http = require('http')

const BACKEND_URL = 'http://127.0.0.1:8899'
const HEALTH_URL = `${BACKEND_URL}/api/health`
const DEV_UI_URL = 'http://localhost:5173'
const JAR_NAME = 'tmam-backend-0.0.1-SNAPSHOT.jar'
const BACKEND_WAIT_MS = 60_000

let mainWindow
let springBootProcess
let quitting = false
let backendLaunchError = null
let backendExitedEarly = false
let lastJarPath = null

function resolveJavaExecutable() {
  const javaHome = process.env.JAVA_HOME
  if (javaHome) {
    const candidate = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    if (fs.existsSync(candidate)) {
      return candidate
    }
  }
  return 'java'
}

function resolvePackagedJar() {
  const extracted = [
    path.join(process.resourcesPath, 'backend', 'backend.jar'),
    path.join(process.resourcesPath, 'backend', JAR_NAME),
  ].find((candidate) => fs.existsSync(candidate))
  if (extracted) {
    return extracted
  }
  return path.join(process.resourcesPath, 'backend.jar')
}

function resolveDevJar() {
  const candidates = [
    path.join(__dirname, '..', '..', 'backend', 'target', JAR_NAME),
    path.join(process.cwd(), '..', 'backend', 'target', JAR_NAME),
    path.join(process.cwd(), 'backend', 'target', JAR_NAME),
  ]
  return candidates.find((candidate) => fs.existsSync(candidate)) ?? null
}

function resolveJarPath() {
  if (app.isPackaged) {
    return resolvePackagedJar()
  }
  if (process.env.TMAM_BACKEND_JAR && fs.existsSync(process.env.TMAM_BACKEND_JAR)) {
    return process.env.TMAM_BACKEND_JAR
  }
  return resolveDevJar()
}

function shouldStartBackend() {
  if (process.env.ELECTRON_START_BACKEND === 'false') {
    return false
  }
  if (app.isPackaged) {
    return true
  }
  return process.env.ELECTRON_START_BACKEND === 'true' || !!resolveDevJar()
}

function buildJavaArgs(jarPath) {
  const args = []
  if (app.isPackaged) {
    args.push(
      '-Xms32m',
      '-Xmx512m',
      '-XX:+UseSerialGC',
      '-XX:TieredStopAtLevel=1',
      '-Dlogging.level.com.tmam=INFO',
    )
  }
  args.push(
    '-Dfile.encoding=UTF-8',
    '-Djava.net.preferIPv4Stack=true',
    '-Djava.awt.headless=true',
    '-Dspring.jmx.enabled=false',
    '-Dspring.main.banner-mode=off',
    '-jar',
    jarPath,
  )
  return args
}

function startBackend(jarPath) {
  backendLaunchError = null
  backendExitedEarly = false
  const javaExecutable = resolveJavaExecutable()
  console.log('[TMAM] Starting backend:', javaExecutable, jarPath)
  springBootProcess = spawn(javaExecutable, buildJavaArgs(jarPath), {
    cwd: path.dirname(jarPath),
    stdio: 'pipe',
    windowsHide: true,
  })

  springBootProcess.on('error', (error) => {
    backendLaunchError = error
    console.error('[TMAM] Failed to spawn Java:', error)
    springBootProcess = null
  })

  springBootProcess.stdout?.on('data', (data) => {
    console.log('[Spring Boot]', data.toString().trim())
  })

  springBootProcess.stderr?.on('data', (data) => {
    console.error('[Spring Boot Error]', data.toString().trim())
  })

  springBootProcess.on('exit', (code) => {
    if (!quitting) {
      backendExitedEarly = true
      if (code !== 0) {
        console.error(`[TMAM] Backend exited with code ${code}`)
      }
    }
    springBootProcess = null
  })
}

function checkHealth() {
  return new Promise((resolve) => {
    const request = http.get(HEALTH_URL, (response) => {
      resolve(response.statusCode === 200)
      response.resume()
    })
    request.on('error', () => resolve(false))
    request.setTimeout(1000, () => {
      request.destroy()
      resolve(false)
    })
  })
}

async function waitForBackend(timeoutMs = BACKEND_WAIT_MS) {
  const deadline = Date.now() + timeoutMs
  await new Promise((resolve) => setTimeout(resolve, 600))
  while (Date.now() < deadline) {
    if (await checkHealth()) {
      return true
    }
    if (backendLaunchError || backendExitedEarly) {
      return false
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  return false
}

function describeBackendFailure() {
  if (!lastJarPath || !fs.existsSync(lastJarPath)) {
    return '找不到 backend.jar。'
  }
  if (backendLaunchError) {
    if (backendLaunchError.code === 'ENOENT') {
      return '找不到 Java。請確認已安裝 Java 17，且 java 已加入系統 PATH。'
    }
    return `無法啟動 Java：${backendLaunchError.message}`
  }
  if (backendExitedEarly) {
    return '後端程式已異常結束。請查看 %USERPROFILE%\\.tmam\\logs\\tmam-backend.log'
  }
  return '後端啟動逾時（等待 60 秒仍無法連線 127.0.0.1:8899）。\n請查看 %USERPROFILE%\\.tmam\\logs\\tmam-backend.log'
}

async function ensureBackendReady() {
  if (await checkHealth()) {
    return true
  }

  if (!shouldStartBackend()) {
    return false
  }

  lastJarPath = resolveJarPath()
  if (!lastJarPath || !fs.existsSync(lastJarPath)) {
    console.warn('[TMAM] Backend JAR not found, skip auto-start')
    return false
  }

  startBackend(lastJarPath)
  return waitForBackend()
}

function stopBackend() {
  if (springBootProcess && !springBootProcess.killed) {
    springBootProcess.kill()
    springBootProcess = null
  }
}

function createMainWindow() {
  return new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    title: 'TMAM - Tomcat Manager',
    show: false,
    backgroundColor: '#f5f7fa',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  })
}

async function showSplash() {
  const splashPath = path.join(__dirname, 'splash.html')
  await mainWindow.loadFile(splashPath)
  if (!mainWindow.isVisible()) {
    mainWindow.show()
  }
}

async function createWindow() {
  mainWindow = createMainWindow()
  mainWindow.once('ready-to-show', () => {
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
      mainWindow.show()
    }
  })

  const backendAlreadyUp = await checkHealth()
  if (!backendAlreadyUp && (app.isPackaged || shouldStartBackend())) {
    await showSplash()
  }

  const backendReady = await ensureBackendReady()
  const usePackagedUi = app.isPackaged || (backendReady && process.env.ELECTRON_USE_BACKEND_UI === 'true')
  const uiUrl = usePackagedUi ? BACKEND_URL : DEV_UI_URL

  if (!backendReady && usePackagedUi) {
    await dialog.showErrorBox(
      'TMAM 啟動失敗',
      `無法連線至後端服務（127.0.0.1:8899）。\n${describeBackendFailure()}`,
    )
    app.quit()
    return
  }

  await mainWindow.webContents.session.clearCache()
  await mainWindow.loadURL(uiUrl)

  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  }
}

ipcMain.handle('shell:openPath', async (_event, targetPath) => {
  if (!targetPath || typeof targetPath !== 'string') {
    return '路徑無效'
  }
  return shell.openPath(targetPath)
})

ipcMain.handle('dialog:selectDirectory', async (_event, defaultPath) => {
  const options = {
    properties: ['openDirectory'],
  }
  if (defaultPath && fs.existsSync(defaultPath)) {
    options.defaultPath = defaultPath
  }
  const result = await dialog.showOpenDialog(mainWindow ?? undefined, options)
  if (result.canceled || result.filePaths.length === 0) {
    return null
  }
  return result.filePaths[0]
})

app.whenReady().then(async () => {
  await createWindow()

  app.on('activate', async () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      await createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', () => {
  quitting = true
  stopBackend()
})
