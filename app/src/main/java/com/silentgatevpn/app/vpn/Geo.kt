package com.silentgatevpn.app.vpn

/** Country helpers and the "never exit here" rules (home country + age-verification regions). */
object Geo {

    // Regions where online-safety / age-verification rules are commonly enforced. Laws change; keep this list editable.
    val RESTRICTED_COUNTRIES = setOf("GB", "AU", "FR", "DE", "IT", "ES", "IE")
    val RESTRICTED_US_STATES = setOf(
        "AL", "AZ", "AR", "FL", "GA", "ID", "IN", "KS", "KY", "LA", "MO", "MS", "MT",
        "NE", "NC", "ND", "OH", "OK", "SC", "SD", "TN", "TX", "UT", "VA", "WY"
    )

    /** Exit regions we prefer, best first. Order only matters for ranking; verification decides the rest. */
    val SAFE_PRIORITY = listOf("NL", "CH", "SG", "CA", "JP", "RO", "FI", "SE", "NO", "AT", "CZ", "PL", "LU", "IS", "HK", "US")

    /**
     * Countries offered in the location picker: the safe-exit list without the US, because many US states enforce age
     * verification and an exit there would randomly be rejected after connecting.
     */
    val PICKER_COUNTRIES: List<String> = SAFE_PRIORITY.filter { it != "US" }

    private val NAMES = mapOf(
        "NL" to "Netherlands", "CH" to "Switzerland", "SG" to "Singapore", "CA" to "Canada", "JP" to "Japan",
        "RO" to "Romania", "FI" to "Finland", "SE" to "Sweden", "NO" to "Norway", "AT" to "Austria",
        "CZ" to "Czechia", "PL" to "Poland", "LU" to "Luxembourg", "IS" to "Iceland", "HK" to "Hong Kong",
        "US" to "United States", "DE" to "Germany", "FR" to "France", "GB" to "United Kingdom", "IT" to "Italy",
        "ES" to "Spain", "IE" to "Ireland", "AU" to "Australia", "PK" to "Pakistan", "IN" to "India",
        "TR" to "Turkey", "RU" to "Russia", "UA" to "Ukraine", "BG" to "Bulgaria", "LV" to "Latvia",
        "LT" to "Lithuania", "EE" to "Estonia", "KR" to "South Korea", "TW" to "Taiwan", "AE" to "UAE",
        "BR" to "Brazil", "MX" to "Mexico", "ZA" to "South Africa", "ID" to "Indonesia", "MY" to "Malaysia",
        "TH" to "Thailand", "VN" to "Vietnam", "IL" to "Israel", "IR" to "Iran", "CN" to "China",
        "AR" to "Argentina", "PT" to "Portugal", "BE" to "Belgium", "DK" to "Denmark", "HU" to "Hungary",
        "GR" to "Greece", "NZ" to "New Zealand", "KZ" to "Kazakhstan", "MD" to "Moldova", "RS" to "Serbia"
    )

    private val BY_NAME: List<Pair<String, String>> = listOf(
        "united states" to "US", "usa" to "US", "america" to "US", "united kingdom" to "GB", "england" to "GB",
        "netherlands" to "NL", "holland" to "NL", "singapore" to "SG", "switzerland" to "CH", "canada" to "CA",
        "japan" to "JP", "romania" to "RO", "finland" to "FI", "sweden" to "SE", "norway" to "NO", "austria" to "AT",
        "czech" to "CZ", "poland" to "PL", "luxembourg" to "LU", "iceland" to "IS", "hong kong" to "HK",
        "germany" to "DE", "france" to "FR", "italy" to "IT", "spain" to "ES", "ireland" to "IE",
        "australia" to "AU", "pakistan" to "PK", "india" to "IN", "turkey" to "TR", "russia" to "RU",
        "ukraine" to "UA", "bulgaria" to "BG", "latvia" to "LV", "lithuania" to "LT", "estonia" to "EE",
        "korea" to "KR", "taiwan" to "TW", "emirates" to "AE", "brazil" to "BR", "mexico" to "MX",
        "south africa" to "ZA", "indonesia" to "ID", "malaysia" to "MY", "thailand" to "TH", "vietnam" to "VN",
        "israel" to "IL", "iran" to "IR", "china" to "CN", "argentina" to "AR", "portugal" to "PT",
        "belgium" to "BE", "denmark" to "DK", "hungary" to "HU", "greece" to "GR", "new zealand" to "NZ",
        "kazakhstan" to "KZ", "moldova" to "MD", "serbia" to "RS"
    )

    private val CODE_TOKEN = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    fun name(cc: String): String = NAMES[cc.uppercase()] ?: cc.uppercase()

    fun flag(cc: String): String {
        val c = cc.uppercase()
        if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "\uD83C\uDF10"
        val base = 0x1F1E6
        return String(Character.toChars(base + (c[0] - 'A'))) + String(Character.toChars(base + (c[1] - 'A')))
    }

    fun isRestricted(countryCode: String, regionCode: String): Boolean =
        countryCode.uppercase() in RESTRICTED_COUNTRIES ||
            (countryCode.equals("US", true) && regionCode.uppercase() in RESTRICTED_US_STATES)

    /**
     * Returns a human readable reason when an exit location must not be used, or null when it is fine.
     * For the US the state code is needed; pass "" when unknown (the caller then fetches it before deciding).
     */
    fun excludedReason(cc: String, rc: String, homeCc: String, avoidRestricted: Boolean, allowHome: Boolean): String? {
        val c = cc.uppercase()
        if (c.isEmpty()) return null
        if (!allowHome && homeCc.isNotEmpty() && c == homeCc.uppercase()) return "exit is in your home country (${name(c)})"
        if (avoidRestricted && isRestricted(c, rc)) {
            return "exit ${if (c == "US" && rc.isNotEmpty()) "US-$rc" else name(c)} requires age verification"
        }
        return null
    }

    /** Best-effort country guess from a node name such as "🇳🇱 NL-Amsterdam 01" or "Singapore | fast". */
    fun ccFromText(text: String): String {
        if (text.isEmpty()) return ""
        // 1. flag emoji (two regional indicator symbols)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < text.length) {
                val cp2 = text.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val cc = "" + ('A' + (cp - 0x1F1E6)) + ('A' + (cp2 - 0x1F1E6))
                    if (cc in NAMES) return cc
                }
            }
            i += n
        }
        // 2. country name
        val low = text.lowercase()
        BY_NAME.firstOrNull { low.contains(it.first) }?.let { return it.second }
        // 3. upper-case two letter token
        for (m in CODE_TOKEN.findAll(text)) {
            val cc = m.groupValues[1]
            if (cc in NAMES && cc != "IN" && cc != "IS" && cc != "ID") return cc
        }
        return ""
    }
}
