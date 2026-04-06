package com.example.resultreader

import android.content.Context
import android.util.Log

class SoundManager(private val context: Context) {

    // 判定音の再生抑止用タイムスタンプ（ミリ秒）
    private var lastJudgePlayTime: Long = 0L
    // SoundPool ベースの効果音（短い音向け）
    private var judgeSoundPool: android.media.SoundPool? = null
    private var judgeSoundOkId: Int = 0
    private var judgeSoundCheckId: Int = 0
    private var judgeSoundsLoaded: Boolean = false
    // 個別ロード完了フラグ（SoundPool の各サンプルがロード済みか）
    private var judgeOkLoaded: Boolean = false
    private var judgeCheckLoaded: Boolean = false
    // 直近に再生した判定（null=なし, true=OK, false=NG）を保持して同一状態の連続再生を抑止
    private var lastJudgeState: Boolean? = null

    // 再生中フラグ（重複再生防止）
    private var isPlayingJudge: Boolean = false

    // ★ 判定音を鳴らす（true = 正解, false = 要確認）
    // 変更: SoundPool を優先し、MediaPlayer をフォールバックとして使う。短い効果音は SoundPool が信頼性高い。
    fun ensureJudgeSoundsLoaded() {
        if (judgeSoundsLoaded) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                judgeSoundPool = android.media.SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                judgeSoundPool = android.media.SoundPool(2, android.media.AudioManager.STREAM_MUSIC, 0)
            }

            // リソースは .wav でも .mp3 でも R.raw.* で参照できる
            judgeOkLoaded = false
            judgeCheckLoaded = false
            judgeSoundOkId = judgeSoundPool?.load(context, R.raw.judge_ok, 1) ?: 0
            judgeSoundCheckId = judgeSoundPool?.load(context, R.raw.judge_check, 1) ?: 0

            judgeSoundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                Log.d("JUDGE_SOUND", "onLoadComplete id=$sampleId status=$status")
                try {
                    if (sampleId == judgeSoundOkId && judgeSoundOkId != 0) judgeOkLoaded = true
                    if (sampleId == judgeSoundCheckId && judgeSoundCheckId != 0) judgeCheckLoaded = true
                    // judgeSoundsLoaded はどちらかがロード済みなら true とする（個別判定で再生可否を判断する）
                    judgeSoundsLoaded = judgeOkLoaded || judgeCheckLoaded
                } catch (e: Exception) {
                    Log.w("JUDGE_SOUND", "onLoadComplete handling failed", e)
                }
            }
         } catch (e: Exception) {
             Log.e("JUDGE_SOUND", "SoundPool init failed", e)
             judgeSoundPool = null
             judgeSoundsLoaded = false
         }
     }

    // ★ 判定音を鳴らす（true = 正解, false = 要確認）
    fun playJudgeSound(isOk: Boolean) {
        val resId = if (isOk) R.raw.judge_ok else R.raw.judge_check

        try {
            val mp = android.media.MediaPlayer.create(context, resId)
            if (mp == null) {
                Log.e("JUDGE_SOUND", "MediaPlayer.create() returned null for res=$resId")
                return
            }

            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) { }
                Log.d("JUDGE_SOUND", "completed res=$resId isOk=$isOk")
            }

            mp.setOnErrorListener { player, what, extra ->
                try {
                    player.release()
                } catch (_: Exception) { }
                Log.e("JUDGE_SOUND", "error what=$what extra=$extra for res=$resId")
                true
            }

            mp.start()
            Log.d("JUDGE_SOUND", "start play res=$resId isOk=$isOk")
        } catch (e: Exception) {
            Log.e("JUDGE_SOUND", "play error for res=$resId", e)
        }
    }

    fun release() {
        judgeSoundPool?.release()
        judgeSoundPool = null
    }
}
