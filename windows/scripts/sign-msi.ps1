<#
.SYNOPSIS
    Signs a Recly MSI (docs/14 N7 · M6-L3 deliverable 4).

.DESCRIPTION
    Two ways to sign, in this order:

    1. **Microsoft Trusted Signing** (formerly Azure Code Signing / "Artifact Signing") — the one
       this project wants. There is no certificate to keep: the signing key lives in Azure and the
       runner authenticates with a service principal. Set
       AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET,
       TRUSTED_SIGNING_ENDPOINT (e.g. https://eus.codesigning.azure.net),
       TRUSTED_SIGNING_ACCOUNT, TRUSTED_SIGNING_PROFILE.

    2. **An EV certificate** in a PFX, for a machine that has one:
       WINDOWS_CERT_PFX_BASE64, WINDOWS_CERT_PASSWORD.

    With neither set the script says so and **exits 0**: an unsigned MSI is still the artifact CI is
    asked for, and a build must not fail because a fork has no credentials (the SmartScreen
    consequence is in windows/README.md).

    Neither path can be exercised on the development machine (macOS, no certificate — M6-L3
    "환경 제약"). It is written to be run by `.github/workflows/windows.yml` and by a person on a
    Windows PC.

.EXAMPLE
    pwsh windows/scripts/sign-msi.ps1 -Msi windows/app/build/compose/binaries/main/msi/Recly-0.0.1.msi
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Msi
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Msi)) {
    throw "no MSI at $Msi"
}
$Msi = (Resolve-Path -LiteralPath $Msi).Path

function Find-SignTool {
    # signtool.exe is in the Windows SDK, which is versioned and not on PATH. Newest first.
    $command = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Path }
    $roots = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin",
        "$env:ProgramFiles\Windows Kits\10\bin"
    ) | Where-Object { Test-Path $_ }
    foreach ($root in $roots) {
        $found = Get-ChildItem -Path $root -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\x64\\' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw 'signtool.exe not found — install the Windows SDK signing tools'
}

$trusted = @(
    $env:AZURE_TENANT_ID, $env:AZURE_CLIENT_ID, $env:AZURE_CLIENT_SECRET,
    $env:TRUSTED_SIGNING_ENDPOINT, $env:TRUSTED_SIGNING_ACCOUNT, $env:TRUSTED_SIGNING_PROFILE
)
$hasTrusted = -not ($trusted | Where-Object { [string]::IsNullOrWhiteSpace($_) })
$hasCert = -not ([string]::IsNullOrWhiteSpace($env:WINDOWS_CERT_PFX_BASE64))

if (-not $hasTrusted -and -not $hasCert) {
    Write-Host 'sign-msi: no signing credentials — leaving the MSI unsigned (see windows/README.md).'
    exit 0
}

if ($hasTrusted) {
    Write-Host 'sign-msi: Microsoft Trusted Signing'
    if (-not (Get-Module -ListAvailable -Name TrustedSigning)) {
        Install-Module -Name TrustedSigning -Force -AllowClobber -Scope CurrentUser -Repository PSGallery
    }
    Import-Module TrustedSigning

    # The module reads the service principal out of the environment (Azure.Identity's
    # EnvironmentCredential), so there is nothing to write to disk.
    Invoke-TrustedSigning `
        -Endpoint $env:TRUSTED_SIGNING_ENDPOINT `
        -CodeSigningAccountName $env:TRUSTED_SIGNING_ACCOUNT `
        -CertificateProfileName $env:TRUSTED_SIGNING_PROFILE `
        -FilesFolder (Split-Path -Parent $Msi) `
        -FilesFolderFilter 'msi' `
        -FileDigest 'SHA256' `
        -TimestampRfc3161 'http://timestamp.acs.microsoft.com' `
        -TimestampDigest 'SHA256'
} else {
    Write-Host 'sign-msi: EV certificate'
    $pfx = Join-Path ([System.IO.Path]::GetTempPath()) 'recly-signing.pfx'
    try {
        [System.IO.File]::WriteAllBytes($pfx, [System.Convert]::FromBase64String($env:WINDOWS_CERT_PFX_BASE64))
        $signtool = Find-SignTool
        & $signtool sign /fd SHA256 /f $pfx /p $env:WINDOWS_CERT_PASSWORD `
            /tr http://timestamp.digicert.com /td SHA256 $Msi
        if ($LASTEXITCODE -ne 0) { throw "signtool sign failed ($LASTEXITCODE)" }
    } finally {
        # The certificate is the only thing here that must not outlive the step.
        if (Test-Path -LiteralPath $pfx) { Remove-Item -LiteralPath $pfx -Force }
    }
}

# Whatever signed it, this is what the user's machine will check.
$signtool = Find-SignTool
& $signtool verify /pa /v $Msi
if ($LASTEXITCODE -ne 0) { throw "the MSI is not verifiably signed ($LASTEXITCODE)" }
Write-Host "sign-msi: signed $Msi"
