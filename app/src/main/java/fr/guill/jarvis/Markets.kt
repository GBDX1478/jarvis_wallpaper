package fr.guill.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un cours : indice, crypto ou devise. kind : 0 = indice, 1 = crypto (€), 2 = devise. */
data class Quote(val label: String, val spoken: String, val value: Double, val pct: Double?, val kind: Int, val decimals: Int) {

    fun valueText(): String {
        val nf = NumberFormat.getNumberInstance(Locale.FRANCE)
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        return nf.format(value) + if (kind == 1) " €" else ""
    }

    fun pctText(): String = pct?.let { String.format(Locale.FRANCE, "%+.2f %%", it) } ?: ""

    fun toJson(): JSONObject = JSONObject().apply {
        put("l", label); put("s", spoken); put("v", value); put("p", pct ?: Double.NaN); put("k", kind); put("d", decimals)
    }

    companion object {
        fun fromJson(o: JSONObject) = Quote(
            o.getString("l"), o.getString("s"), o.getDouble("v"),
            o.optDouble("p").takeIf { !it.isNaN() }, o.getInt("k"), o.getInt("d")
        )
    }
}

object Markets {

    private data class Idx(val label: String, val spoken: String, val cnbc: String, val yahoo: String)

    private val INDICES = listOf(
        Idx("CAC 40", "Le CAC 40", ".FCHI", "%5EFCHI"),
        Idx("S&P 500", "Le S&P 500", ".SPX", "%5EGSPC"),
        Idx("NASDAQ", "Le Nasdaq", ".IXIC", "%5EIXIC"),
    )

    fun fetchAll(): List<Quote> {
        val out = mutableListOf<Quote>()
        out += indices()
        out += crypto()
        out += forex()
        return out
    }

    // --- Indices : CNBC (une seule requête), Yahoo en secours -------------------------------
    private fun indices(): List<Quote> {
        val res = mutableMapOf<String, Quote>()
        runCatching {
            val syms = INDICES.joinToString("%7C") { it.cnbc }
            val url = "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol?symbols=$syms" +
                "&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json"
            val root = JSONObject(Net.get(url) ?: error("cnbc"))
            val q = root.getJSONObject("FormattedQuoteResult").get("FormattedQuote")
            val arr = if (q is JSONArray) q else JSONArray().put(q)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val idx = INDICES.firstOrNull { it.cnbc == o.optString("symbol") } ?: continue
                val v = o.optString("last").replace(",", "").toDoubleOrNull() ?: continue
                val p = o.optString("change_pct").replace("%", "").replace("+", "").trim().toDoubleOrNull()
                res[idx.label] = Quote(idx.label, idx.spoken, v, p, 0, 0)
            }
        }
        for (idx in INDICES) {
            if (res.containsKey(idx.label)) continue
            runCatching {
                val txt = Net.get("https://query1.finance.yahoo.com/v8/finance/chart/${idx.yahoo}?range=1d&interval=1d")
                    ?: error("yahoo")
                val meta = JSONObject(txt).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                val v = meta.getDouble("regularMarketPrice")
                val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
                val p = if (prev.isNaN() || prev == 0.0) null else (v - prev) / prev * 100
                res[idx.label] = Quote(idx.label, idx.spoken, v, p, 0, 0)
            }
        }
        return INDICES.mapNotNull { res[it.label] }
    }

    // --- Cryptos : CoinGecko ------------------------------------------------------------------
    private fun crypto(): List<Quote> = runCatching {
        val o = JSONObject(
            Net.get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=eur&include_24hr_change=true")
                ?: return emptyList()
        )
        listOfNotNull(
            o.optJSONObject("bitcoin")?.let { Quote("BTC", "Le Bitcoin", it.getDouble("eur"), it.optDouble("eur_24h_change").takeIf { d -> !d.isNaN() }, 1, 0) },
            o.optJSONObject("ethereum")?.let { Quote("ETH", "L'Ethereum", it.getDouble("eur"), it.optDouble("eur_24h_change").takeIf { d -> !d.isNaN() }, 1, 0) },
        )
    }.getOrDefault(emptyList())

    // --- Devises : Frankfurter (taux BCE, quotidiens) --------------------------------------------
    private fun forex(): List<Quote> = runCatching {
        val from = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 10L * 86_400_000))
        val txt = Net.get("https://api.frankfurter.dev/v1/$from..?base=EUR&symbols=USD,JPY")
            ?: Net.get("https://api.frankfurter.app/$from..?from=EUR&to=USD,JPY")
            ?: return emptyList()
        val rates = JSONObject(txt).getJSONObject("rates")
        val days = rates.keys().asSequence().toList().sorted()
        if (days.isEmpty()) return emptyList()
        val last = rates.getJSONObject(days.last())
        val prev = if (days.size >= 2) rates.getJSONObject(days[days.size - 2]) else null
        fun pct(a: Double, b: Double?) = b?.let { (a - it) / it * 100 }
        val usd = last.getDouble("USD")
        val jpy = last.getDouble("JPY")
        val usdJpy = jpy / usd
        val pUsd = prev?.getDouble("USD")
        val pJpy = prev?.getDouble("JPY")
        listOf(
            Quote("EUR/USD", "L'euro face au dollar", usd, pct(usd, pUsd), 2, 4),
            Quote("EUR/JPY", "L'euro face au yen", jpy, pct(jpy, pJpy), 2, 2),
            Quote("USD/JPY", "Le dollar face au yen", usdJpy, if (pUsd != null && pJpy != null) pct(usdJpy, pJpy / pUsd) else null, 2, 2),
        )
    }.getOrDefault(emptyList())

    fun toJson(list: List<Quote>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun fromJson(s: String?): List<Quote> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { Quote.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
