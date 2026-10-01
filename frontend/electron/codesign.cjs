const path = require('path')
const { spawnSync } = require('child_process')

const SCRIPT = path.join(__dirname, '..', '..', 'scripts', 'windows-codesign.ps1')

function runCodesign(args) {
	const result = spawnSync(
		'powershell.exe',
		['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', SCRIPT, ...args],
		{ encoding: 'utf8' },
	)
	if (result.stdout) {
		process.stdout.write(result.stdout)
	}
	if (result.stderr) {
		process.stderr.write(result.stderr)
	}
	if (result.status !== 0) {
		throw new Error(`windows-codesign failed (${result.status}): ${result.stderr || result.stdout || result.error}`)
	}
}

function prepareMachine() {
	runCodesign(['-PrepareMachine'])
}

function signPath(targetPath) {
	runCodesign(['-SignPath', targetPath])
}

function unblockPath(targetPath) {
	runCodesign(['-UnblockPath', targetPath])
}

module.exports = {
	prepareMachine,
	signPath,
	unblockPath,
}
