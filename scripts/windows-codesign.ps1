param(
	[switch]$PrepareMachine,
	[string]$SignPath = '',
	[string]$UnblockPath = ''
)

$ErrorActionPreference = 'Stop'
$Subject = 'CN=TMAM'
$FriendlyName = 'TMAM Local Code Signing'
$CertsDir = Join-Path $env:USERPROFILE '.tmam\certs'
$CerPath = Join-Path $CertsDir 'tmam-codesign.cer'
$ThumbPath = Join-Path $CertsDir 'thumbprint.txt'

function Test-IsAdmin {
	$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
	$principal = New-Object Security.Principal.WindowsPrincipal($identity)
	return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-TmamCert {
	if (Test-Path $ThumbPath) {
		$thumb = (Get-Content -LiteralPath $ThumbPath -Raw).Trim()
		if ($thumb) {
			$existing = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq $thumb } | Select-Object -First 1
			if ($existing -and $existing.HasPrivateKey) {
				return $existing
			}
		}
	}

	$existing = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue |
		Where-Object { $_.Subject -eq $Subject -and $_.HasPrivateKey } |
		Sort-Object NotAfter -Descending |
		Select-Object -First 1
	if ($existing) {
		return $existing
	}

	New-Item -ItemType Directory -Force -Path $CertsDir | Out-Null
	$cert = New-SelfSignedCertificate `
		-Type CodeSigningCert `
		-Subject $Subject `
		-FriendlyName $FriendlyName `
		-HashAlgorithm SHA256 `
		-KeyLength 2048 `
		-KeyExportPolicy Exportable `
		-CertStoreLocation Cert:\CurrentUser\My `
		-NotAfter (Get-Date).AddYears(10)
	return $cert
}

function Save-TmamCert([System.Security.Cryptography.X509Certificates.X509Certificate2]$cert) {
	New-Item -ItemType Directory -Force -Path $CertsDir | Out-Null
	Set-Content -LiteralPath $ThumbPath -Value $cert.Thumbprint -Encoding ASCII
	Export-Certificate -Cert $cert -FilePath $CerPath -Force | Out-Null
}

function Test-CertInStore($storePath, $thumbprint) {
	return Test-Path -LiteralPath (Join-Path $storePath $thumbprint)
}

function Add-CertToStore($storeName, $storeLocation, $cerPath) {
	$args = @('-f', '-addstore', $storeName, $cerPath)
	if ($storeLocation -eq 'CurrentUser') {
		$args = @('-f', '-user', '-addstore', $storeName, $cerPath)
	}
	& certutil.exe @args | Out-Null
	if ($LASTEXITCODE -ne 0) {
		throw "certutil failed adding $storeName ($storeLocation), exit $LASTEXITCODE"
	}
}

function Get-SmartAppControlState {
	try {
		return [int](Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState).VerifiedAndReputablePolicyState
	}
	catch {
		return -1
	}
}

function Disable-SmartAppControl {
	$state = Get-SmartAppControlState
	if ($state -eq 0) {
		Write-Host '[codesign] Smart App Control already off'
		return
	}
	if ($state -lt 0) {
		Write-Host '[codesign] Smart App Control registry not found, skip'
		return
	}
	Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState -Value 0 -Type DWord
	Write-Host '[codesign] Smart App Control set to Off (reboot may be required)'
}

function Invoke-PrepareMachine {
	$cert = Get-TmamCert
	Save-TmamCert $cert
	Write-Host "[codesign] using cert $($cert.Thumbprint)"

	$needsAdmin = $false
	if (-not (Test-CertInStore 'Cert:\LocalMachine\Root' $cert.Thumbprint)) { $needsAdmin = $true }
	if (-not (Test-CertInStore 'Cert:\LocalMachine\TrustedPublisher' $cert.Thumbprint)) { $needsAdmin = $true }
	$sac = Get-SmartAppControlState
	if ($sac -eq 1 -or $sac -eq 2) { $needsAdmin = $true }

	if ($needsAdmin -and -not (Test-IsAdmin)) {
		Write-Host '[codesign] elevating to trust certificate and disable Smart App Control...'
		try {
			$p = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList @(
				'-NoProfile',
				'-ExecutionPolicy', 'Bypass',
				'-File', $PSCommandPath,
				'-PrepareMachine'
			)
			if ($p.ExitCode -ne 0) {
				Write-Host "[codesign] WARNING: elevation failed ($($p.ExitCode)). Run scripts\allow-tmam.bat as Administrator."
			}
		}
		catch {
			Write-Host "[codesign] WARNING: could not elevate: $_. Run scripts\allow-tmam.bat as Administrator."
		}
		if (-not (Test-CertInStore 'Cert:\CurrentUser\TrustedPublisher' $cert.Thumbprint)) {
			Add-CertToStore 'TrustedPublisher' 'CurrentUser' $CerPath
		}
		Write-Host '[codesign] machine prepare attempted without admin'
		return
	}

	if (-not (Test-CertInStore 'Cert:\CurrentUser\TrustedPublisher' $cert.Thumbprint)) {
		Add-CertToStore 'TrustedPublisher' 'CurrentUser' $CerPath
	}

	if (Test-IsAdmin) {
		if (-not (Test-CertInStore 'Cert:\LocalMachine\TrustedPublisher' $cert.Thumbprint)) {
			Add-CertToStore 'TrustedPublisher' 'LocalMachine' $CerPath
		}
		if (-not (Test-CertInStore 'Cert:\LocalMachine\Root' $cert.Thumbprint)) {
			Add-CertToStore 'Root' 'LocalMachine' $CerPath
		}
		Disable-SmartAppControl
	}

	Write-Host '[codesign] machine prepared'
}

function Sign-OneFile([string]$file, $cert) {
	$current = Get-AuthenticodeSignature -FilePath $file
	if ($current.Status -eq 'Valid') {
		return 'skipped'
	}

	$signed = $null
	try {
		$signed = Set-AuthenticodeSignature -FilePath $file -Certificate $cert -HashAlgorithm SHA256 -TimestampServer 'http://timestamp.digicert.com'
	}
	catch {
		$signed = $null
	}
	if (-not $signed -or $signed.Status -ne 'Valid') {
		$signed = Set-AuthenticodeSignature -FilePath $file -Certificate $cert -HashAlgorithm SHA256
	}
	if ($signed.Status -ne 'Valid') {
		throw "failed to sign ${file}: $($signed.Status) $($signed.StatusMessage)"
	}
	return 'signed'
}

function Invoke-SignPath([string]$target) {
	if (-not (Test-Path -LiteralPath $target)) {
		throw "sign path not found: $target"
	}
	$cert = Get-TmamCert
	Save-TmamCert $cert

	$files = @()
	if (Test-Path -LiteralPath $target -PathType Leaf) {
		$files = @(Get-Item -LiteralPath $target)
	}
	else {
		$files = @(Get-ChildItem -LiteralPath $target -Recurse -Include *.exe, *.dll -File)
	}

	$signed = 0
	$skipped = 0
	foreach ($file in $files) {
		$result = Sign-OneFile $file.FullName $cert
		if ($result -eq 'signed') { $signed += 1 } else { $skipped += 1 }
	}
	Write-Host "[codesign] signed=$signed skipped=$skipped path=$target"
}

function Invoke-UnblockPath([string]$target) {
	if (-not (Test-Path -LiteralPath $target)) {
		return
	}
	Get-ChildItem -LiteralPath $target -Recurse -File -ErrorAction SilentlyContinue |
		Unblock-File -ErrorAction SilentlyContinue
	Write-Host "[codesign] unblocked $target"
}

try {
	if ($PrepareMachine) {
		Invoke-PrepareMachine
	}
	if ($SignPath) {
		Invoke-SignPath $SignPath
	}
	if ($UnblockPath) {
		Invoke-UnblockPath $UnblockPath
	}
	if (-not $PrepareMachine -and -not $SignPath -and -not $UnblockPath) {
		throw 'specify -PrepareMachine, -SignPath and/or -UnblockPath'
	}
}
catch {
	Write-Error $_
	exit 1
}
