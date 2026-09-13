package com.piprapay.liteforwarder

// Mirrors the server's netlify/functions/_lib/parse.js patterns so the
// app can show a friendly preview locally (provider/amount/trx id)
// before it even reaches the network. The SERVER is still the source
// of truth for parsing — this is just for the in-app log / preview.
object SmsParser {

    data class Parsed(val provider: String, val amount: String?, val trxId: String?, val sender: String?)

    private val BKASH_RECEIVED = Regex(
        "received Tk\\s?([\\d,.]+) from (\\d+)\\.(?:\\s*Ref[:\\-]?\\s*(\\S+))? Fee Tk\\s?([\\d,.]+)\\. Balance Tk\\s?([\\d,.]+)\\. TrxID ([A-Z0-9]+)",
        RegexOption.IGNORE_CASE
    )
    private val BKASH_CASHIN = Regex(
        "Cash In Tk\\s?([\\d,.]+) from (\\d+) successful\\. Fee Tk\\s?([\\d,.]+)\\. Balance Tk\\s?([\\d,.]+)\\. TrxID ([A-Z0-9]+)",
        RegexOption.IGNORE_CASE
    )
    private val NAGAD_RECEIVED = Regex(
        "Money Received\\.?\\s*Amount:\\s?Tk\\s?([\\d,.]+)\\s*Sender:\\s?(\\d+)(?:\\s*Ref[:\\-]?\\s*(\\S+))?\\s*TxnID:\\s?([A-Z0-9]+)\\s*Balance:\\s?Tk\\s?([\\d,.]+)",
        RegexOption.IGNORE_CASE
    )

    fun isLikelyTransaction(sender: String, message: String): Boolean {
        val hay = (sender + " " + message).lowercase()
        return hay.contains("bkash") || hay.contains("nagad")
    }

    fun parse(sender: String, message: String): Parsed? {
        val clean = message.replace(Regex("\\s+"), " ").trim()
        val hay = (sender + " " + clean).lowercase()

        BKASH_RECEIVED.find(clean)?.let {
            return Parsed("bkash", it.groupValues[1], it.groupValues[6].uppercase(), it.groupValues[2])
        }
        BKASH_CASHIN.find(clean)?.let {
            return Parsed("bkash", it.groupValues[1], it.groupValues[5].uppercase(), it.groupValues[2])
        }
        NAGAD_RECEIVED.find(clean)?.let {
            return Parsed("nagad", it.groupValues[1], it.groupValues[4].uppercase(), it.groupValues[2])
        }
        if (hay.contains("bkash")) return Parsed("bkash", null, null, null)
        if (hay.contains("nagad")) return Parsed("nagad", null, null, null)
        return null
    }
}
