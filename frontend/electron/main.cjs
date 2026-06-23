const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')
const http = require('http')

const BACKEND_URL = 'http://localhost:8899'
const HEALTH_URL = `${BACKEND_URL}/api/health`
const DEV_UI_URL = 'http://localhost:5173'
const JAR_NAME = 'tmam-backend-0.0.1-SNAPSHOT.jar'

let mainWindow
let springBootProcess
let quitting = false

function resolvePackagedJar() {
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

function startBackend(jarPath) {
  console.log('[TMAM] Starting backend:', jarPath)
  springBootProcess = spawn('java', ['-Dfile.encoding=UTF-8', '-jar', jarPath], {
    stdio: 'pipe',
    windowsHide: true,
  })

  springBootProcess.stdout.on('data', (data) => {
    console.log('[Spring Boot]', data.toString().trim())
  })

  springBootProcess.stderr.on('data', (data) => {
    console.error('[Spring Boot Error]', data.toString().trim())
  })

  springBootProcess.on('exit', (code) => {
    if (!quitting && code !== 0) {
      console.error(`[TMAM] Backend exited with code ${code}`)
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

async function waitForBackend(retries = 20) {
  for (let i = 0; i < retries; i++) {
    if (await checkHealth()) {
      return true
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  return false
}

async function ensureBackendReady() {
  if (await checkHealth()) {
    return true
  }

  if (!shouldStartBackend()) {
    return false
  }

  const jarPath = resolveJarPath()
  if (!jarPath || !fs.existsSync(jarPath)) {
    console.warn('[TMAM] Backend JAR not found, skip auto-start')
    return false
  }

  startBackend(jarPath)
  return waitForBackend(app.isPackaged ? 20 : 40)
}

function stopBackend() {
  if (springBootProcess && !springBootProcess.killed) {
    springBootProcess.kill()
    springBootProcess = null
  }
}

async function createWindow() {
  const backendReady = await ensureBackendReady()
  const usePackagedUi = app.isPackaged || (backendReady && process.env.ELECTRON_USE_BACKEND_UI === 'true')
  const uiUrl = usePackagedUi ? BACKEND_URL : DEV_UI_URL

  if (!backendReady && usePackagedUi) {
    await dialog.showErrorBox(
      'TMAM 啟動失敗',
      '無法連線至後端服務（localhost:8899）。\n請確認已安裝 Java 17，且 backend.jar 存在。',
    )
    app.quit()
    return
  }

  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    title: 'TMAM - Tomcat Manager',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  })

  mainWindow.once('ready-to-show', () => mainWindow.show())
  await mainWindow.loadURL(uiUrl)

  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  }
}

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
