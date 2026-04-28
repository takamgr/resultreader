# ResultReader 開発引き継ぎ（CLAUDE.md）

## アプリ概要
トライアル競技のスコアカードをカメラで読み取り、集計・CSV出力するAndroidアプリ。
完全オフライン動作必須（競技会場は電波不安定）。

## 開発者について
- プログラミング素人だがセンスがある
- PCはWindows（自宅・ガレージの2台）
- AndroidStudio + Claude Code + このチャットで開発
- GitHubでコード管理（takamgr/resultreader）

## 技術スタック
- 言語：Kotlin
- カメラ：CameraX
- OCR：Google ML Kit
- 最低SDK：26
- ストレージ：getExternalFilesDir("ResultReader")
- 出力：UTF-8 + BOM付CSV・PDF

## ファイル構成と役割
- CameraActivity.kt：UIと画面制御のみ（ロジックは持たない）
- CameraManager.kt：カメラ起動・停止・タイムアウト
- OcrProcessor.kt：撮影・OCR・パンチ穴検出・スコアROI切り出し
- ScoreManager.kt：スコア計算・UI更新・手入力ダイアログ
- SaveManager.kt：保存フロー（NORMAL/DNF/DNS）
- TournamentManager.kt：大会設定・エントリー管理・ダイアログ
- ResultChecker.kt：AM/PM/最終チェック
- CsvFileManager.kt：CSVファイル操作UI
- SoundManager.kt：判定音管理
- RoiSettingActivity.kt：ROI調整画面（複数機種対応）
- RoiOverlayView.kt：ROI調整画面の枠描画
- ScoreAnalyzer.kt：パンチ穴検出ロジック（触らない）
- CsvExporter.kt：CSV出力・ランク計算（触らない）
- EntryProgressCounter.kt：AM/PMカウンター
- PrintableExporter.kt：PDF出力

## 絶対に触らない領域
- ScoreAnalyzer.ktのスコア判定ロジック
- CsvExporter.ktのCSV列構成・ランク計算
- ROI座標の計算式（相対座標方式で管理）

## ROI座標管理（重要）
機種変更対応のため相対座標方式を採用。
基準値：baseX=436 baseY=750 baseWidth=2033
この3値からEntryNo OCRとスコアグリッド座標を自動計算。
SharedPreferencesキー：roi_{機種名}_base_x/base_y/base_width/rotation
アクティブ機種：roi_active_device

## スコア仕様
- 正常値：0/1/2/3/5
- 異常値：99（空欄・未読・範囲外）
- 99が1つでもあると保存不可・confirmButton非表示
- CSV保存時に99→空欄に正規化

## CSV仕様（変更禁止）
EntryNo, Name, Class, Sec01〜, AmG, AmC, AmRank, SecXX〜, PmG, PmC, PmRank, TotalG, TotalC, TotalRank, 時刻, 入力, セッション

## 大会設定
- パターン：4x2/5x2/4x3
- セッション：AM/PM
- 大会種別：選手権（IA/IB/NA/NB）/ ビギナー（オープン/ビギナー）
- SharedPreferencesで復元

## UI構成
右上ボタン（縦並び）：
- 電球：フラッシュON/OFF
- □：カメラON/OFF
- 歯車：単押し=大会設定 / 長押し=ROI調整画面
- ↓：単押し=DLフォルダ / 長押し=entrylist読込・ロック解除
- フォルダ：単押し=CSVファイル一覧 / 長押し=CSV/PDF出力

下部ボタン：
- 撮影準備（OCR）：単押し=撮影 / 長押し=AUTO/MANUAL切替
- 確認して保存：単押し=保存 / 長押し=DNF/DNS保存

インジケーター：
- MANUAL/AUTO背景色で読み取り状態を表示
- スコアグリッド背景色でも同時表示
- 赤=待機中 / 黄=要確認 / 緑=読み取りOK

## 重要な技術情報（Ver2.4判明）
- CameraManager.startCamera() は未使用
  実際はCameraActivity.kt内のprivate fun startCamera()（L811）が動いている
- CameraManagerのスリープ機能は無効化済み
  resetInactivityTimer()の呼び出し（L620・L650）を削除
  isCameraSuspendedがfalseに戻る処理がなくバグの原因だったため
- stopCameraIfAutoMode() はOcrProcessor.ktから削除済み
  OCR後のカメラ自動停止が手動・オート両モードの撮影エラーの原因だったため
- prepareButton単押しのオートモード強制解除バグを修正済み
  isAutoModeEnabled=falseの時のみisManualCameraControl=trueにする

## 開発ルール
- 既存の動作は一切変えない
- ロジックは移動するだけで書き換えない
- 新機能は別ファイルに実装
- 1ファイルずつ作成・完了ごとに報告
- 作業前にgit pull・作業後にgit push

## 現在のバージョン
Ver2.4（タグ付け済み）
- ファイル分割完了（CameraActivity 2200行→685行）
- ROI調整機能実装（複数機種対応）
- 多機種での動作確認済み（3機種）
- 画面サイズ自動フィット対応
- スリープ機能無効化（10秒タイマーによるカメラ自動停止を削除）
- OCR後のカメラ自動停止を削除（手動・オート両モードの撮影エラー解消）
- 撮影ボタン単押しでオートモードが解除されるバグを修正

## 次の課題
- whiteRatio（自動撮影の白カード検知閾値）の機種対応検討
- 実際の競技会場でのテスト
