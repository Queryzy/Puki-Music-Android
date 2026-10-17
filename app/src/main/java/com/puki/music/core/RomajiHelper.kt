package com.puki.music.core

import android.util.Log
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.ipadic.Token

object RomajiHelper {
    private const val TAG = "RomajiHelper"

    // Kuromoji Tokenizer - Parses Japanese Kanji into correct Katakana pronunciation
    private val tokenizer: Tokenizer by lazy {
        try {
            Tokenizer()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Kuromoji Tokenizer: ${e.message}")
            Tokenizer.Builder().build()
        }
    }

    /**
     * Converts full CJK text (Japanese Kanji/Kana & Korean Hangul) to clean Romaji (Latin Alphabet).
     * Japanese Example: "それでもこの世界で生きる意味探して" -> "soredemo kono sekai de ikiru imi sagashite"
     * Korean Example: "너의 입술로 말을 해줘" -> "neoui ipsulro maleul haejwo"
     */
    fun toRomaji(text: String): String {
        if (text.isBlank()) return text
        // 1. Process Korean Hangul syllables if present
        val processedKorean = hangulToRomaji(text)

        // 2. Process Japanese Kanji & Kana using Kuromoji Tokenizer
        return try {
            val tokens: List<Token> = tokenizer.tokenize(processedKorean)
            val result = StringBuilder()

            for (token in tokens) {
                val reading = token.reading
                val surface = token.surface

                if (!reading.isNullOrBlank() && reading != "*") {
                    result.append(kanaToRomaji(reading))
                } else {
                    result.append(kanaToRomaji(surface))
                }
            }
            cleanRomajiSpacing(result.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "Romaji parsing fallback: ${e.message}")
            kanaToRomaji(processedKorean)
        }
    }

    // Standard Revised Romanization of Korean (Hangul Syllables 0xAC00 - 0xD7A3)
    private val choseong = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val jungseong = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val jongseong = arrayOf("", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h")

    private fun hangulToRomaji(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val code = c.code
            if (code in 0xAC00..0xD7A3) {
                val sIndex = code - 0xAC00
                val lIndex = sIndex / (21 * 28)
                val vIndex = (sIndex % (21 * 28)) / 28
                val tIndex = sIndex % 28

                sb.append(choseong[lIndex])
                sb.append(jungseong[vIndex])
                sb.append(jongseong[tIndex])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun cleanRomajiSpacing(input: String): String {
        return input
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun kanaToRomaji(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            // Sokuon (っ / ッ) - Doubles the next consonant
            if (input[i] == 'っ' || input[i] == 'ッ') {
                if (i + 1 < input.length) {
                    val nextRomaji = kanaMap[input.substring(i + 1, (i + 3).coerceAtMost(input.length))]
                        ?: kanaMap[input.substring(i + 1, i + 2)]
                    if (!nextRomaji.isNullOrEmpty()) {
                        sb.append(nextRomaji[0])
                        i++
                        continue
                    }
                }
                i++
                continue
            }

            // Check digraphs (2 characters like きゃ / キャ / しょ / ショ)
            if (i + 1 < input.length) {
                val two = input.substring(i, i + 2)
                val mappedTwo = kanaMap[two]
                if (mappedTwo != null) {
                    sb.append(mappedTwo)
                    i += 2
                    continue
                }
            }

            // Check single character
            val one = input.substring(i, i + 1)
            val mappedOne = kanaMap[one]
            if (mappedOne != null) {
                sb.append(mappedOne)
            } else {
                sb.append(one)
            }
            i++
        }
        return sb.toString()
    }

    private val kanaMap: Map<String, String> = mapOf(
        // Hiragana Digraphs
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",

        // Katakana Digraphs
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
        "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
        "ヴァ" to "va", "ヴィ" to "vi", "ヴ" to "vu", "ヴェ" to "ve", "ヴォ" to "vo",

        // Hiragana
        "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
        "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
        "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
        "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
        "な" to "na", "に" to "ni", "ぬ" to "nu", "ne" to "ne", "の" to "no", "ね" to "ne",
        "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
        "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
        "や" to "ya", "ゆ" to "yu", "よ" to "yo",
        "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
        "わ" to "wa", "を" to "wo", "ん" to "n",
        "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
        "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
        "だ" to "da", "ぢ" to "ji", "づ" to "zu", "で" to "de", "ど" to "do",
        "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
        "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po",

        // Katakana
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "wo", "ン" to "n",
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        "ー" to "-"
    )
}
