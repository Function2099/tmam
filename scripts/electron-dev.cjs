const { spawn } = require('child_process')
const http = require('http')
const path = require('path')

const rootDir = path.join(__dirname, '..')
const backendDir = path.join(rootDir, 'backend')
const frontendDir = path.join(rootDir, 'frontend')

const children = []

function run(command, args, options = {}) {
  const child = spawn(command, args, {
    stdio: 'inherit',
    shell: process.platform === 'win32',
    ...options,
  })
  children.push(child)
  return child
}

function waitFor(url, retries = 60, intervalMs = 500) {
  return new Promise((resolve, reject) => {
    let attempts = 0

    const tick = () => {
      const request = http.get(url, (response) => {
        if (response.statusCode && response.statusCode < 500) {
          resolve(true)
        } else {
          retry()
        }
        response.resume()
      })
      request.on('error', retry)
      request.setTimeout(1000, () => {
        request.destroy()
        retry()
      })
    }

    const retry = () => {
      attempts += 1
      if (attempts >= retries) {
        reject(new Error(`Timeout waiting for ${url}`))
        return
      }
      setTimeout(tick, intervalMs)
    }

    tick()
  })
}

function shutdown() {
  for (const child of children) {
    if (!child.killed) {
      child.kill()
    }
  }
}

process.on('SIGINT', () => {
  shutdown()
  process.exit(0)
})

async function main() {
  console.log('[electron-dev] Starting backend...')
  run(path.join(backendDir, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'), ['spring-boot:run', '-q'], {
    cwd: backendDir,
  })

  await waitFor('http://localhost:8899/api/health')
  console.log('[electron-dev] Backend is ready')

  console.log('[electron-dev] Starting Vite...')
  run('npm', ['run', 'dev'], { cwd: frontendDir })

  await waitFor('http://localhost:5173')
  console.log('[electron-dev] Vite is ready')

  console.log('[electron-dev] Launching Electron...')
  const electron = run('npm', ['run', 'electron:dev'], {
    cwd: frontendDir,
    env: {
      ...process.env,
      ELECTRON_START_BACKEND: 'false',
    },
  })

  electron.on('exit', (code) => {
    shutdown()
    process.exit(code ?? 0)
  })
}

main().catch((error) => {
  console.error('[electron-dev] Failed:', error.message)
  shutdown()
  process.exit(1)
})
