$targets = @(
  "app/src/main/java/com/example/resultreader/CsvUtils.kt",
  "app/src/main/java/com/example/resultreader/ScoreAnalyzer.kt",
  "app/src/main/java/com/example/resultreader/TournamentPattern.kt",
  "app/src/main/java/com/example/resultreader/GuideOverlayView.kt",
  "app/src/main/java/com/example/resultreader/OcrGuideOverlay.kt",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/res/xml/file_paths.xml",
  "app/src/main/res/layout/activity_camera.xml",
  "app/src/main/res/layout/dialog_tournament_setting.xml",
  "app/src/main/java/com/example/resultreader/CameraActivity.kt",
  "app/src/main/java/com/example/resultreader/CsvExporter.kt",
  "app/src/main/java/com/example/resultreader/MainActivity.kt"
)

$kotlinGuard = "// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)`r`n" +
"// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.`r`n" +
"// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.`r`n"

$xmlGuard = "<!-- 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)`r`n" +
"     Do NOT change absolute ROI/GuideOverlay geometry, view IDs referenced by code,`r`n" +
"     or CSV-related assumptions. If unsure: STOP and ask. -->`r`n"

$missing = @()
$modified = @()
$skipped = @()

foreach ($path in $targets) {
  if (-not (Test-Path $path)) { $missing += $path; continue }

  $raw = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)

  if ($raw -match "RRv1_6_GUARD") { $skipped += $path; continue }

  if ($path -match "\.xml$") {
    $declEnd = $raw.IndexOf("?>")
    if ($declEnd -ge 0) {
      $insertPos = $declEnd + 2
      $new = $raw.Substring(0,$insertPos) + "`r`n" + $xmlGuard + $raw.Substring($insertPos)
    } else {
      $new = $xmlGuard + $raw
    }
  } else {
    $lines = [IO.File]::ReadAllLines($path, [Text.Encoding]::UTF8)
    $match = Select-String -InputObject $lines -Pattern "^\s*package\s" -List
    if ($match) {
      $i = $match.LineNumber
      $before = if ($i -gt 1) { $lines[0..($i-2)] } else { @() }
      $after  = $lines[($i-1)..($lines.Length-1)]
      $new = (@($before) + ($kotlinGuard -split "`r`n") + $after) -join "`r`n"
    } else {
      $new = $kotlinGuard + $raw
    }
  }

  [IO.File]::WriteAllText($path, $new, [Text.Encoding]::UTF8)
  $modified += $path
}

if ($missing.Count -gt 0) {
  Write-Host "ERROR: 次が見つかりません。コミットせず中断します：" -ForegroundColor Red
  $missing | ForEach-Object { " - $_" }
  exit 1
}

if ($modified.Count -eq 0) {
  Write-Host "No files modified. 既にガード済みか変更不要です。"
  if ($skipped.Count -gt 0) {
    Write-Host "Skipped (already guarded):"
    $skipped | ForEach-Object { " - $_" }
  }
  exit 0
}

Write-Host "Modified files:"
$modified | ForEach-Object { " - $_" }

git add -- $modified
$commitMsg = "chore(guard): add RRv1.6 Copilot guard headers (no logic changes)"
git commit -m $commitMsg
