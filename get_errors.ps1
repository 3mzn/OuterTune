$output = & "./gradlew" ":app:compileDebugKotlin" "--no-daemon" "--info" 2>&1
$output | Out-File -FilePath "build_log.txt" -Encoding UTF8
$errorLines = $output | Select-String -Pattern "error:|e:" | Where-Object { $_ -notmatch "^To honour" }
$errorLines | Out-File -FilePath "build_errors_only.txt" -Encoding UTF8
Write-Host "Done. Check build_errors_only.txt"
