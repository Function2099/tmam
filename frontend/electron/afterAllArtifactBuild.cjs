const { signPath, unblockPath } = require('./codesign.cjs')

exports.default = async function afterAllArtifactBuild(buildResult) {
	const artifacts = buildResult.artifactPaths || []
	for (const artifact of artifacts) {
		if (artifact.toLowerCase().endsWith('.exe')) {
			signPath(artifact)
			unblockPath(artifact)
		}
	}
	return []
}
