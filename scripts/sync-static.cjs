const fs = require('fs')
const path = require('path')

const rootDir = path.join(__dirname, '..')
const distDir = path.join(rootDir, 'frontend', 'dist')
const staticDir = path.join(rootDir, 'backend', 'src', 'main', 'resources', 'static')

function copyRecursive(source, target) {
  fs.mkdirSync(target, { recursive: true })
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const sourcePath = path.join(source, entry.name)
    const targetPath = path.join(target, entry.name)
    if (entry.isDirectory()) {
      copyRecursive(sourcePath, targetPath)
    } else {
      fs.copyFileSync(sourcePath, targetPath)
    }
  }
}

function clearDirectory(dir) {
  if (!fs.existsSync(dir)) {
    return
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const entryPath = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      fs.rmSync(entryPath, { recursive: true, force: true })
    } else {
      fs.unlinkSync(entryPath)
    }
  }
}

if (!fs.existsSync(distDir)) {
  console.error(`[sync-static] Missing frontend build output: ${distDir}`)
  console.error('Run: cd frontend && npm run build')
  process.exit(1)
}

clearDirectory(staticDir)
copyRecursive(distDir, staticDir)
console.log(`[sync-static] Copied ${distDir} -> ${staticDir}`)
