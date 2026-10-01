const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')
const { signPath, unblockPath } = require('./codesign.cjs')

function resolveJava() {
	const javaHome = process.env.JAVA_HOME
	if (javaHome) {
		const candidate = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
		if (fs.existsSync(candidate)) {
			return candidate
		}
	}
	return 'java'
}

function extractBackendJar(appOutDir) {
	const resourcesDir = path.join(appOutDir, 'resources')
	const fatJar = path.join(resourcesDir, 'backend.jar')
	if (!fs.existsSync(fatJar)) {
		console.warn('[afterPack] backend.jar missing, skip extract')
		return
	}

	const dest = path.join(resourcesDir, 'backend')
	const result = spawnSync(
		resolveJava(),
		['-Djarmode=tools', '-jar', fatJar, 'extract', '--destination', dest, '--force'],
		{ encoding: 'utf8' },
	)
	if (result.status !== 0) {
		console.warn(
			'[afterPack] jar extract failed, keep fat jar:',
			result.stderr || result.stdout || result.error,
		)
		return
	}

	const extractedJar = fs
		.readdirSync(dest)
		.filter((name) => name.endsWith('.jar'))
		.map((name) => path.join(dest, name))
		.find((candidate) => fs.existsSync(candidate))
	if (!extractedJar) {
		console.warn('[afterPack] extract produced no jar, keep fat jar')
		return
	}

	fs.unlinkSync(fatJar)
	console.log('[afterPack] extracted backend for faster startup ->', extractedJar)
}

exports.default = async function afterPack(context) {
	if (context.electronPlatformName !== 'win32') {
		return
	}

	const exeName = `${context.packager.appInfo.productFilename}.exe`
	const exePath = path.join(context.appOutDir, exeName)
	const rceditExe = path.join(__dirname, '..', 'node_modules', 'rcedit', 'bin', 'rcedit-x64.exe')
	const result = spawnSync(
		rceditExe,
		[
			exePath,
			'--set-requested-execution-level', 'requireAdministrator',
			'--set-version-string', 'CompanyName', 'TMAM',
			'--set-version-string', 'ProductName', 'TMAM',
			'--set-version-string', 'FileDescription', 'TMAM - Tomcat Service Manager',
			'--set-version-string', 'LegalCopyright', 'TMAM',
		],
		{ encoding: 'utf8' },
	)
	if (result.status !== 0) {
		throw new Error(
			`rcedit failed (${result.status}): ${result.stderr || result.stdout || result.error}`,
		)
	}

	extractBackendJar(context.appOutDir)

	const batSrc = path.join(__dirname, 'TMAM.bat')
	const batDest = path.join(context.appOutDir, 'TMAM.bat')
	fs.copyFileSync(batSrc, batDest)

	signPath(exePath)
	unblockPath(context.appOutDir)

	const allowSrc = path.join(__dirname, '..', '..', 'scripts', 'allow-tmam.bat')
	const allowDest = path.join(context.appOutDir, 'allow-tmam.bat')
	if (fs.existsSync(allowSrc)) {
		fs.copyFileSync(allowSrc, allowDest)
		const codesignSrc = path.join(__dirname, '..', '..', 'scripts', 'windows-codesign.ps1')
		fs.copyFileSync(codesignSrc, path.join(context.appOutDir, 'windows-codesign.ps1'))
	}
	console.log('[afterPack] signed + trusted + requireAdministrator ->', context.appOutDir)
}
