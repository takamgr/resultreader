@echo off
cd /d C:\Users\tk\Documents\GitHub\resultreader

echo [1] Gradleクリーンアップ中...
call gradlew clean

echo [2] ビルド実行中...
call gradlew build

echo [3] デバイスに直接インストール中...
call gradlew installDebug

echo [4] ログ取得（リアルタイム表示）...
adb logcat -s AndroidRuntime
