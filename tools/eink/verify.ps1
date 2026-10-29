param()

# Flugel: use the standard flavor so isolated device fixtures keep their existing package IDs.
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

Push-Location $root
try {
    & .\gradlew.bat `
        spotlessCheck `
        verifyEInkMotion `
        :app:testStandardDebugUnitTest `
        --tests "eu.kanade.tachiyomi.ui.eink.*" `
        --tests "eu.kanade.tachiyomi.ui.reader.transition.PageTransitionPreferenceTest" `
        :app:compileDebugKotlin
    if ($LASTEXITCODE -ne 0) {
        throw "E-Ink verification failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
