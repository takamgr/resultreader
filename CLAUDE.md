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
- □：クリック=誤操作防止トースト / 長押し=DNF/DNS
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
Ver2.5.7（タグ付け済み）

## UI構成
右上ボタン（縦並び）：
- 電球：単押し=フラッシュON/OFF / 長押し=EntryNoプレビューON/OFF
- □：クリック=誤操作防止トースト / 長押し=DNF/DNS
- 歯車：単押し=大会設定 / 長押し=3択メニュー（ROI調整・全リセット・バックアップ復元）
- ↓：単押し=EntryListファイル選択 / 長押し=entrylist読込ロック解除
- フォルダ：単押し=CSVファイル一覧（複数選択削除対応） / 長押し=CSV/PDF出力

## SharedPreferencesキー一覧（ResultReaderPrefs）
### 大会設定系（全リセット対象）
- lastSetDate：大会設定ダイアログの日付比較用
- lastPattern：前回パターン（PATTERN_4x2等）
- lastSession：前回セッション（AM/PM）
- tournamentType：大会種別（championship/beginner）
- tournamentName：大会名（PDF/HTML出力用）
- eventDate：開催日（PDF/HTML出力用）
- eventTitle：大会タイトル（PrintableExporter用）
- entrylist_loaded_once：EntryList読み込み済みフラグ

### ROI設定系（全リセット対象外・機種変更後も保持）
- roi_active_device：使用中の機種名
- roi_device_list：登録機種名カンマ区切り
- roi_{device}_base_x/base_y/base_width/base_height/rotation/image_width/image_height
- base_x/base_y/base_width/roi_rotation/image_width/image_height（機種未設定時フォールバック）

## バックアップ仕様
- 保存先：getExternalFilesDir("ResultReader") 内（スコアCSVと同フォルダ）
- ファイル名：result_{pattern}_{date}_backup.csv
- 全リセット時に自動生成、バックアップ復元後も元ファイルは残す

## バージョン履歴

### Ver2.4（タグ付け済み）
- ファイル分割完了（CameraActivity 2200行→685行）
- ROI調整機能実装（複数機種対応）
- 多機種での動作確認済み（3機種）
- 画面サイズ自動フィット対応
- スリープ機能無効化
- OCR後のカメラ自動停止を削除
- 撮影ボタン単押しでオートモードが解除されるバグを修正

### Ver2.5（2026-04-28）
多数決ロジック修正（OcrProcessor.kt）
- 撮影エラー時にtakeNext(count+1)を追加→3回完走
- 多数決条件をmajorityGroup.value.size >= 2に変更
- 多数決完了前はconfirmButtonを非表示
- 判定一致せず時はconfirmButtonを出さない

### Ver2.5.2（2026-05-06）
- 起動時カメラON（onCreate()末尾にstartCamera()追加）
- □ボタン：カメラON/OFF削除→クリック=トースト/長押し=DNF/DNS専用
- 多数決不一致時にresultText.text = "要確認"
- SPクラスのランク除外（CsvExporter.kt）

### Ver2.5.3（2026-05-07）
- 電球ボタン長押しでEntryNoプレビューON/OFF
- EntryNo切り出し画像をFrameLayout左上に表示（60×40dp・半透明黒背景）
- ROI設定後にカメラが復帰しない不具合を修正
  - RoiSettingActivity.onDestroy()のunbindAll()を削除
  - CameraActivity.onResume()にstartCamera()を追加

### Ver2.5.5（2026-05-07）
- calcOcrRect()に調整用パラメータを追加
  - entryNoOffsetY/entryNoOffsetX（上下左右位置）
  - entryNoScaleW/entryNoScaleH（縦横倍率）
- EntryNo OCR読み取り位置の調整完了

### Ver2.5.6（2026-05-08）
- ResultChecker.kt 未集計エントリー表示を改善
  - 表示形式：「No:1 相川 秀斗 [IA]」（クラス付き）
  - ソート順：IA→IB→NA→NB→オープン→ビギナー→SP、同クラス内EntryNo昇順
  - CSV保存0件・CSVなしでも全entryMapを未集計として表示
  - entryMapが空の場合はトースト「エントリーリストが読み込まれていません」
- 歯車ボタン長押しを3択メニューに変更
  - ROI調整（従来と同じ）
  - 全リセット：result_*.csvをバックアップ後削除→大会設定系PrefsをremoveしてROI設定は保持→アプリ再起動
  - バックアップから復元：_backup.csvを元ファイル名にコピー→アプリ再起動

### Ver2.5.7（2026-05-08）
- CsvFileManager.kt CSVファイル一覧を複数選択削除に変更
  - setMultiChoiceItems（チェックボックス付きリスト）
  - タップでチェックON/OFF、削除ボタンで確認後一括削除
  - 長押しで1件操作メニュー（開く/ダウンロードへコピー/共有/削除）は従来通り

### Ver2.5.4（2026-05-11）
- カウンターバグ修正（EntryProgressCounter.kt）
  - PMで保存するたびAMカウントが減るバグを修正
  - カウント基準をセッション列からAmG/PmG列に変更
  - DNS/DNFはAM・PM両方カウント済みとして扱う
- セッション上書きバグ修正（CsvExporter.kt）
  - PM保存時に既存AM行のセッション列を上書きしていたバグを修正
  - 新規行のときのみセッション列を書き込むよう変更
- ランク計算改善（CsvExporter.kt）
  - タイブレーク実装（G→C→1点数→2点数→3点数）
  - 同率ランク実装（例：1・1・3・4）
  - SPクラスは引き続きランク計算除外
- 同点チェック改善（ResultChecker.kt）
  - checkAmStatus()・checkFinalStatus()の同点判定をタイブレークまで対応
  - G・Cだけでなく1点・2点・3点セクション数まで一致した場合のみ完全同点と判定
- ノルバ用CSV出力追加（PrintableExporter.kt・CsvFileManager.kt）
  - フォルダアイコン長押しメニューに「ノルバ用CSVを保存」を追加
  - 1人2行（Lap1=AM・Lap2=PM）
  - 列順：順位, エントリーNo, 名前, Lap, S1〜Sn, G, G計, C計
  - パターン別セクション数：4x2=8・5x2=10・4x3=12
  - ファイル名：noluba_{pattern}_{yyyyMMdd}.csv

## 次の課題
- 順位手動上書き機能（完全同点時に画面上でランクを直接入力できるUI）
- whiteRatio（自動撮影の白カード検知閾値）の機種対応検討
- 実際の競技会場でのテスト
