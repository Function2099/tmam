const { spawn } = require('child_process')
const path = require('path')

const rootDir = path.join(__dirname, '..')
const frontendDir = path.join(rootDir, 'frontend')
const backendDir = path.join(rootDir, 'backend')
const mvnw = path.join(backendDir, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')

function run(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: 'inherit',
      shell: process.platform === 'win32',
    })
    child.on('exit', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} ${args.join(' ')} failed with code ${code}`))
    })
  })
}

async function main() {
  console.log('[build-desktop] 1/4 Building frontend...')
  await run('npm', ['run', 'build'], frontendDir)

  console.log('[build-desktop] 2/4 Syncing static assets to backend...')
  await run('node', [path.join(__dirname, 'sync-static.cjs')], rootDir)

  console.log('[build-desktop] 3/4 Packaging backend JAR...')
  await run(mvnw, ['package', '-DskipTests', '-q'], backendDir)

  console.log('[build-desktop] 4/4 Building Electron app...')
  await run('npx', ['electron-builder', '--win'], frontendDir)

  console.log('[build-desktop] Done. Output: build/')
}

main().catch((error) => {
  console.error('[build-desktop] Failed:', error.message)
  process.exit(1)
})
