@echo off
echo === ファイルの存在と重複チェック ===

setlocal

set BASE_DIR=%CD%
set FILE_LIST=CameraActivity.kt activity_camera.xml AndroidManifest.xml file_paths.xml build.gradle

for %%F in (%FILE_LIST%) do (
    echo.
    echo -- %%F の検索中 --
    dir /s /b %%F 2>nul
)

echo.
echo === チェック完了！ ===
endlocal
pause
