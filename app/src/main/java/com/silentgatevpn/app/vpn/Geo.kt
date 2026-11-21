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

    /** Pseudo country used by the location picker for nodes whose country is not known yet ("Global / Other"). */
    const val OTHER = "XX"

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


    /** City / region words (lower case). Short keys are matched as whole words only, long ones also without spaces. */
    private val BY_CITY: List<Pair<String, String>> = listOf(
        // Netherlands
        "amsterdam" to "NL", "rotterdam" to "NL", "haarlem" to "NL", "eygelshoven" to "NL", "nikhef" to "NL",
        // Germany
        "frankfurt" to "DE", "berlin" to "DE", "munich" to "DE", "nuremberg" to "DE", "falkenstein" to "DE",
        "dusseldorf" to "DE", "hamburg" to "DE",
        // UK / Ireland / France / Spain / Italy
        "london" to "GB", "manchester" to "GB", "slough" to "GB", "dublin" to "IE", "paris" to "FR",
        "marseille" to "FR", "gravelines" to "FR", "roubaix" to "FR", "strasbourg" to "FR", "madrid" to "ES",
        "barcelona" to "ES", "milan" to "IT", "rome" to "IT",
        // Switzerland / Austria / Nordics
        "zurich" to "CH", "geneva" to "CH", "vienna" to "AT", "stockholm" to "SE", "gothenburg" to "SE",
        "helsinki" to "FI", "oslo" to "NO", "copenhagen" to "DK", "reykjavik" to "IS",
        // Eastern Europe
        "warsaw" to "PL", "prague" to "CZ", "bucharest" to "RO", "budapest" to "HU", "sofia" to "BG",
        "riga" to "LV", "vilnius" to "LT", "tallinn" to "EE", "kyiv" to "UA", "kiev" to "UA", "moscow" to "RU",
        "istanbul" to "TR", "athens" to "GR", "lisbon" to "PT", "brussels" to "BE", "belgrade" to "RS",
        // Canada
        "toronto" to "CA", "montreal" to "CA", "vancouver" to "CA", "ottawa" to "CA", "beauharnois" to "CA",
        // USA
        "newyork" to "US", "new york" to "US", "losangeles" to "US", "los angeles" to "US", "sanjose" to "US",
        "san jose" to "US", "santaclara" to "US", "sanfrancisco" to "US", "san francisco" to "US", "seattle" to "US",
        "chicago" to "US", "dallas" to "US", "miami" to "US", "ashburn" to "US", "atlanta" to "US", "phoenix" to "US",
        "denver" to "US", "portland" to "US", "boston" to "US", "houston" to "US", "virginia" to "US",
        "california" to "US", "oregon" to "US", "nyc" to "US",
        // Asia / Pacific / Middle East
        "tokyo" to "JP", "osaka" to "JP", "seoul" to "KR", "taipei" to "TW", "hongkong" to "HK", "hong kong" to "HK",
        "bangkok" to "TH", "jakarta" to "ID", "kualalumpur" to "MY", "kuala lumpur" to "MY", "hanoi" to "VN",
        "mumbai" to "IN", "delhi" to "IN", "bangalore" to "IN", "chennai" to "IN", "dubai" to "AE", "telaviv" to "IL",
        "tel aviv" to "IL", "sydney" to "AU", "melbourne" to "AU", "auckland" to "NZ", "saopaulo" to "BR",
        "sao paulo" to "BR", "johannesburg" to "ZA", "mexicocity" to "MX"
    )

    /** IATA airport / datacenter codes, matched case-sensitively as upper-case whole tokens (e.g. "NL-AMS-02"). */
    private val BY_AIRPORT: Map<String, String> = mapOf(
        "AMS" to "NL", "RTM" to "NL", "FRA" to "DE", "TXL" to "DE", "BER" to "DE", "MUC" to "DE", "DUS" to "DE",
        "LHR" to "GB", "LGW" to "GB", "MAN" to "GB", "DUB" to "IE", "CDG" to "FR", "MRS" to "FR", "MAD" to "ES",
        "BCN" to "ES", "MXP" to "IT", "FCO" to "IT", "ZRH" to "CH", "GVA" to "CH", "VIE" to "AT", "ARN" to "SE",
        "HEL" to "FI", "OSL" to "NO", "CPH" to "DK", "KEF" to "IS", "WAW" to "PL", "PRG" to "CZ", "OTP" to "RO",
        "BUD" to "HU", "SOF" to "BG", "RIX" to "LV", "VNO" to "LT", "TLL" to "EE", "KBP" to "UA", "SVO" to "RU",
        "IST" to "TR", "ATH" to "GR", "LIS" to "PT", "BRU" to "BE", "LUX" to "LU",
        "YYZ" to "CA", "YUL" to "CA", "YVR" to "CA",
        "LAX" to "US", "SJC" to "US", "SFO" to "US", "SEA" to "US", "JFK" to "US", "EWR" to "US", "ORD" to "US",
        "DFW" to "US", "IAD" to "US", "ATL" to "US", "MIA" to "US", "PDX" to "US", "DEN" to "US", "PHX" to "US",
        "NRT" to "JP", "HND" to "JP", "KIX" to "JP", "ICN" to "KR", "TPE" to "TW", "HKG" to "HK", "SIN" to "SG",
        "BKK" to "TH", "KUL" to "MY", "CGK" to "ID", "SGN" to "VN", "BOM" to "IN", "DXB" to "AE", "TLV" to "IL",
        "SYD" to "AU", "MEL" to "AU", "AKL" to "NZ", "GRU" to "BR", "JNB" to "ZA"
    )

    private val AIRPORT_TOKEN = Regex("(?<![A-Za-z])([A-Z]{3})(?![A-Za-z])")

    /** Labels such as "nl1", "de02" at the start of a host name; the digits make a two-letter label safe to trust. */
    private val HOST_CC_LABEL = Regex("^([a-z]{2})\\d{1,3}$")
    private val AMBIGUOUS_CC = setOf("MX")   // "mx1.mail.example.com" is a mail host, not Mexico

    private fun isWordAt(low: String, key: String, from: Int): Boolean {
        val end = from + key.length
        val before = from == 0 || !low[from - 1].isLetter()
        val after = end >= low.length || !low[end].isLetter()
        return before && after
    }

    private fun hasWord(low: String, key: String): Boolean {
        var i = low.indexOf(key)
        while (i >= 0) {
            if (isWordAt(low, key, i)) return true
            i = low.indexOf(key, i + 1)
        }
        return false
    }

    private fun cityMatch(text: String): String {
        val low = text.lowercase()
        for ((k, cc) in BY_CITY) if (hasWord(low, k)) return cc
        val compact = low.filter { it.isLetter() }
        for ((k, cc) in BY_CITY) if (k.length >= 7 && compact.contains(k.replace(" ", ""))) return cc
        for (m in AIRPORT_TOKEN.findAll(text)) BY_AIRPORT[m.groupValues[1]]?.let { return it }
        return ""
    }

    /** Country from a server host name such as "nl1.example.com", "sg-edge.host.net" or "ams02.provider.com". */
    fun ccFromHost(host: String): String {
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' || it == ':' }) return ""   // IP literal
        val labels = host.lowercase().split('.', '-', '_').filter { it.isNotEmpty() }.take(3)
        for (l in labels) {
            HOST_CC_LABEL.find(l)?.let {
                val cc = it.groupValues[1].uppercase()
                if (cc in NAMES && cc !in AMBIGUOUS_CC) return cc
            }
            val plain = l.trimEnd { ch -> ch.isDigit() }
            if (plain.length >= 3) {
                BY_AIRPORT[plain.uppercase()]?.let { return it }
                BY_CITY.firstOrNull { it.first == plain }?.let { return it.second }
            }
        }
        return ""
    }

    private val CODE_TOKEN = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    fun name(cc: String): String =
        if (cc.equals(OTHER, true)) "Global / Other" else NAMES[cc.uppercase()] ?: cc.uppercase()

    fun flag(cc: String): String {
        val c = cc.uppercase()
        if (c == OTHER || c.length != 2 || c.any { it !in 'A'..'Z' }) return "\uD83C\uDF10"
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

    /** Best-effort country guess from a node name or tag such as "🇳🇱 NL-Amsterdam 01" or "Singapore | fast". */
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
        BY_NAME.firstOrNull { if (it.first.length <= 4) hasWord(low, it.first) else low.contains(it.first) }?.let { return it.second }
        // 3. city, airport and datacenter tags (Amsterdam / AMS -> NL, Zurich / ZRH -> CH, SanJose -> US ...)
        cityMatch(text).takeIf { it.isNotEmpty() }?.let { return it }
        // 4. upper-case two letter token
        for (m in CODE_TOKEN.findAll(text)) {
            val cc = m.groupValues[1]
            if (cc in NAMES && cc != "IN" && cc != "IS" && cc != "ID") return cc
        }
        return ""
    }
}
