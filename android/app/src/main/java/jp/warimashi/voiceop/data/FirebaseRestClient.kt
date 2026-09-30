package jp.warimashi.voiceop.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Firebase Realtime Database の REST API クライアント。
 *
 * 既存PWAは認証なしの Web SDK で読み書きしているため、同じルールのもとで
 * REST API（`<path>.json`）でも書き込める。google-services.json や Firebase SDK を
 * 組み込まずに済むので、既存の Firebase プロジェクトに Android アプリを登録する必要がない。
 */
class FirebaseRestClient(private val dbUrl: String) {

    class HttpException(val code: Int, message: String) : IOException(message)

    /** `push()` 相当。成功すると生成されたキーを返す。 */
    suspend fun push(path: String, value: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val body = JSONObject(value).toString()
        val res = request("POST", path, body)
        JSONObject(res).optString("name")
    }

    /** `path` 直下の文字列値の一覧（warashi_customers は {キー: 顧客名} 形式）。 */
    suspend fun fetchStringValues(path: String): List<String> = withContext(Dispatchers.IO) {
        val res = request("GET", path, null).trim()
        if (res.isEmpty() || res == "null") return@withContext emptyList()
        val obj = JSONObject(res)
        obj.keys().asSequence().mapNotNull { obj.opt(it) as? String }.toList()
    }

    /**
     * `path` 直下の新しい順に最大 [limit] 件（push キーの順 = 登録順）。{キー: オブジェクト} 以外の値は除く。
     * `$key` の並び替えはインデックス設定なしで使える。
     */
    suspend fun fetchLatestObjects(path: String, limit: Int): List<JSONObject> = withContext(Dispatchers.IO) {
        val query = "orderBy=" + URLEncoder.encode("\"\$key\"", "UTF-8") + "&limitToLast=$limit"
        val res = request("GET", path, null, query).trim()
        if (res.isEmpty() || res == "null") return@withContext emptyList()
        val obj = JSONObject(res)
        obj.keys().asSequence().mapNotNull { obj.opt(it) as? JSONObject }.toList()
    }

    private fun request(method: String, path: String, body: String?, query: String? = null): String {
        val url = "${dbUrl.trimEnd('/')}/$path.json" + if (query != null) "?$query" else ""
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpException(code, "HTTP $code $err")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** 既存PWAの sendToGAS と同じパラメータでスプレッドシートへ記録する（失敗しても無視）。 */
class GasClient(private val webhookUrl: String) {
    suspend fun record(entry: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext
        val keys = listOf("timestamp", "plate", "item", "vehicle", "surcharge", "reasons", "customer", "memo")
        val query = keys.joinToString("&") { k ->
            k + "=" + URLEncoder.encode(entry[k]?.toString().orEmpty(), "UTF-8").replace("+", "%20")
        }
        runCatching {
            val conn = URL("$webhookUrl?$query").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
        Unit
    }
}
