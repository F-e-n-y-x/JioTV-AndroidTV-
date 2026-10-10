package com.fenyx.jtv.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ZeeChannel(
    val id: String,
    val name: String,
    val logoUrl: String,
    val group: String,
    val channelNumber: Int,
    val streamUrl: String,
    val cookie: String = "",
    val userAgent: String = "@shoebbro",
    val referer: String = "https://www.jiotv.com/",
    val origin: String = "https://www.jiotv.com",
    val licenseKey: String = "",
    val isMpd: Boolean = true,
    val language: String = "Hindi",
    val stbNumber: Int = 0
)

object ZeeRepository {
    private const val TAG = "ZeeRepository"
    const val ZEE_M3U_URL = "https://raw.githubusercontent.com/rathee-ashish/zee-playlist/main/zee.m3u"
    private const val CACHE_FILE = "zee_playlist.m3u"

    private val zeeChannelsMap = java.util.concurrent.ConcurrentHashMap<String, ZeeChannel>()

    data class ZeeChannelMeta(
        val channelId: Int,
        val cleanName: String,
        val group: String,
        val language: String,
        val stbNumber: Int = 0
    )

    /**
     * Exact mapping of all Zee TV channels to their official JioTV channel numbers,
     * categories, languages and STB numbers from the JioTV channel database.
     */
    private val CHANNEL_METADATA = mapOf(
        "zeetvhd" to ZeeChannelMeta(167, "Zee TV HD", "Entertainment", "Hindi"),
        "zeecinemahd" to ZeeChannelMeta(165, "Zee Cinema HD", "Movies", "Hindi"),
        "zeetalkies" to ZeeChannelMeta(153, "Zee Talkies", "Movies", "Marathi"),
        "andpictureshd" to ZeeChannelMeta(185, "And Pictures HD", "Movies", "Hindi"),
        "andpictures" to ZeeChannelMeta(1839, "And Pictures", "Movies", "Hindi"),
        "zeeyuva" to ZeeChannelMeta(414, "Zee Yuva", "Entertainment", "Marathi"),
        "zee24taas" to ZeeChannelMeta(442, "Zee 24 Taas", "News", "Marathi", 9005),
        "zeemarathi" to ZeeChannelMeta(445, "Zee Marathi", "Entertainment", "Marathi"),
        "andtvhd" to ZeeChannelMeta(472, "And TV HD", "Entertainment", "Hindi"),
        "zeecinema" to ZeeChannelMeta(484, "Zee Cinema", "Movies", "Hindi"),
        "zeeaction" to ZeeChannelMeta(488, "Zee Action", "Movies", "Hindi"),
        "zeebollywood" to ZeeChannelMeta(487, "Zee Bollywood", "Movies", "Hindi"),
        "zeenews" to ZeeChannelMeta(504, "Zee News", "News", "Hindi", 1007),
        "zeeuttarpradeshuttarakhand" to ZeeChannelMeta(572, "Zee UP UK", "News", "Hindi", 1020),
        "zeeupuk" to ZeeChannelMeta(572, "Zee UP UK", "News", "Hindi", 1020),
        "zing" to ZeeChannelMeta(585, "Zing", "Music", "Hindi"),
        "zeebharat" to ZeeChannelMeta(652, "Zee Bharat", "News", "Hindi", 1022),
        "zeepunjabharyanahimachalpradesh" to ZeeChannelMeta(654, "Zee Punjab Haryana HP", "News", "Punjabi", 9029),
        "zeepunjabharyanahp" to ZeeChannelMeta(654, "Zee Punjab Haryana HP", "News", "Punjabi", 9029),
        "zeemadhyapradeshchattisgarh" to ZeeChannelMeta(658, "Zee MP Chattisgarh", "News", "Hindi", 1017),
        "zeempchattisgarh" to ZeeChannelMeta(658, "Zee MP Chattisgarh", "News", "Hindi", 1017),
        "zeebusiness" to ZeeChannelMeta(657, "Zee Business", "Business News", "Hindi", 1011),
        "zeebiharjharkhand" to ZeeChannelMeta(661, "Zee Bihar Jharkhand", "News", "Bhojpuri", 9015),
        "zeerajasthannews" to ZeeChannelMeta(659, "Zee Rajasthan", "News", "Hindi", 1019),
        "zeerajasthan" to ZeeChannelMeta(659, "Zee Rajasthan", "News", "Hindi", 1019),
        "zeedelhincrharyana" to ZeeChannelMeta(724, "Zee Delhi NCR Haryana", "News", "Hindi", 1051),
        "zeetalkieshd" to ZeeChannelMeta(1358, "Zee Talkies HD", "Movies", "Marathi"),
        "zeemarathihd" to ZeeChannelMeta(1360, "Zee Marathi HD", "Entertainment", "Marathi"),
        "zeetv" to ZeeChannelMeta(1351, "Zee TV", "Entertainment", "Hindi"),
        "zeeclassic" to ZeeChannelMeta(1691, "Zee Classic", "Movies", "Hindi"),
        "zeebiskope" to ZeeChannelMeta(878, "Zee Biskope", "Movies", "Bhojpuri"),
        "zeepowerhd" to ZeeChannelMeta(1746, "Zee Power HD", "Movies", "Kannada"),
        "zeepunjabi" to ZeeChannelMeta(1751, "Zee Punjabi", "Entertainment", "Punjabi"),
        "zeezesthd" to ZeeChannelMeta(2757, "Zee Zest HD", "Lifestyle", "English")
    )

    private const val EMBEDDED_M3U = """#EXTM3U

#EXTINF:-1 tvg-name="Zee TV HD" tvg-logo="https://img.media.jio.com/tvpimages/66/3/300378_1753869902174_l_medium.jpg" group-title="Entertainment", Zee TV HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=7b3d2290787255ac9cdaf43ae31eacce:d0edfa8ebd655926c5e2932d8c59b3d1,24a0c2ce46925d59a5d077532901d775:68c268bda3c33d677db5b77ae8104d09,ef68d6487db25f0fbb08dfd653512fde:c03242e3834dadbb79ccc1cad9afa94d,a03b708ddf985f6eb6513ac0293246a9:e2b653d57ea9684d8e5b2a035105f0af,b553a92b758259b88f48ad521f41cb54:5cb71d334e2f8600addd230370af0e8f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTVHD_BTS/WDVLive/*~hmac=461137246d43be9e977808ff511fb441bfe9aa302be22d9e886080108edc90bf
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTVHD_BTS/WDVLive/*~hmac=461137246d43be9e977808ff511fb441bfe9aa302be22d9e886080108edc90bf"}
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeTVHD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTVHD_BTS/WDVLive/*~hmac=461137246d43be9e977808ff511fb441bfe9aa302be22d9e886080108edc90bf&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Zee Cinema HD" tvg-logo="https://img.media.jio.com/tvpimages/76/6/300330_1768289569650_l_medium.jpg" group-title="Movies", Zee Cinema HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=27eb79e6c8ef594ea8508011b606bb02:1dd808f8b8e367dd9205128bc0865dee,2f73fd6fc33055f0931e6b52989a968b:67821d540d498438c3c84e13b8971f77,eaf2c59f5bad5ce293838b116585174c:a85d1ce160047f14bfb2b93a1ef23859,70df41ef3a3654d7b1cb13fbb2430480:dd83f24ebf2fb5ef5aafb075b9451d44,ae9a750f39ee523c88f71c47872029ce:ce973e45f923884cce42020da0fb6398
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeCinemaHD_BTS/WDVLive/*~hmac=7641963a5cb354e76e636474ac69d315c6227f2e63b81d1be3d8e16d40532ab6
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeCinemaHD_BTS/WDVLive/*~hmac=7641963a5cb354e76e636474ac69d315c6227f2e63b81d1be3d8e16d40532ab6"}
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeCinemaHD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeCinemaHD_BTS/WDVLive/*~hmac=7641963a5cb354e76e636474ac69d315c6227f2e63b81d1be3d8e16d40532ab6&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Zee Talkies" tvg-logo="https://img.media.jio.com/tvpimages/1/19/300351_1765975816671_l_medium.jpg" group-title="Movies", Zee Talkies
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b495c6bba74d52039b0d310ef2bc9cc2:8672293f4472f01fdf74660e2e0fd602,d017974d08965ef19ddb80da7bbd9678:14cc669c720dca403ec5566fc97dbff8,ca5d6e1f882750ec8ec4ae5072acd431:d41408529436e60492dde72f0ef493e5,15fd29096c4f52a4b546cc2558a6c1d8:a8f9efa6a718abc90da7eb6d039ab688,dca1d44892b452a7a1121842007ef3b4:c0709e63f9ed2eb851f7a325c7e65b3e
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTalkies_BTS/WDVLive/*~hmac=225888b9717cd2bae6b9af04f4b76064a712cbebc614cedd5b0995aa844c6e49
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTalkies_BTS/WDVLive/*~hmac=225888b9717cd2bae6b9af04f4b76064a712cbebc614cedd5b0995aa844c6e49"}
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeTalkies_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTalkies_BTS/WDVLive/*~hmac=225888b9717cd2bae6b9af04f4b76064a712cbebc614cedd5b0995aa844c6e49&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="And Pictures HD" tvg-logo="https://img.media.jio.com/tvpimages/57/85/300328_1768298228357_l_medium.jpg" group-title="Movies",And Pictures HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=44fe94d36c805cb19068afa8afefa67d:807af52ba671f10c1127cf7901b16b0a,f46e0d24d4e15eb99dd8739315be218b:6fe0210a22a92a893c55253163151245,e043d52515415ade82356a6546fb99c6:68062e7cb45e88955d64f87f110f0047,22b6b02b053d5271a874fc37b8b62f5c:b74cff91d1091c9968c61ea4c8cb8a3b,b4c6014d89505062bc35dee0ed519bed:2eac17b4204a2fa7a0f67ce63288fafe
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPicturesHD_BTS/WDVLive/*~hmac=4fa42f79a92d19fb2210d2ebca7878bc7b461a9b8cb0163e2b6812aae8dc88f3
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPicturesHD_BTS/WDVLive/*~hmac=4fa42f79a92d19fb2210d2ebca7878bc7b461a9b8cb0163e2b6812aae8dc88f3"}
https://jiotvpllive.cdn.jio.com/bpk-tv/AndPicturesHD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPicturesHD_BTS/WDVLive/*~hmac=4fa42f79a92d19fb2210d2ebca7878bc7b461a9b8cb0163e2b6812aae8dc88f3&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="And Pictures" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/AndPictures.png" group-title="Movies",And Pictures
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=c6ee62a2b6485eefa951aa40fd36c9a5:56222bbfc47fba1b39ae7245103f32df,01ade7b313d75a3c894d89e4d88788c8:e4f4594eb6d2028a4808f939f7d2803d,8fb310f59da250229c68db35c6b7071b:fee30274aaafa7c1c0ab9b762db2b967,32a67aba16855c59954285d162a2351d:b943182ec142edfd95579436460d764b,c81848c0757c5b468f46e19acabd9e8d:31ffc84e75ecd120a161d4b96b997075
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPictures_BTS/WDVLive/*~hmac=0fa2335ac8aa900a5337f410afa67d7dca0efb3ffd55ed6120b7144bf69eb0c0
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPictures_BTS/WDVLive/*~hmac=0fa2335ac8aa900a5337f410afa67d7dca0efb3ffd55ed6120b7144bf69eb0c0"}
https://jiotvpllive.cdn.jio.com/bpk-tv/AndPictures_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPictures_BTS/WDVLive/*~hmac=0fa2335ac8aa900a5337f410afa67d7dca0efb3ffd55ed6120b7144bf69eb0c0&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Zee Yuva" tvg-logo="https://img.media.jio.com/tvpimages/98/27/300397_1768297472629_l_medium.jpg" group-title="Entertainment", Zee Yuva
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=435d7c3cd9535c87ad612c8fe53c0cd5:1fc01b04272f1ce5b34c000a76e3d55f,f3e22d881aa15d5abac70a5be03a7871:e726ddbd2a68d69786c9152a980e2c6e,5f07d81924f55764bc0b41dc25c8d9b3:48a98029a679e11b7298851f22a68df5,7618430c78b65af78ae375abee12bc9a:62a46a590863081ab0cebbb7e3cc3b2c,a876f23f16825ffbb0b676266e7564f1:16a8c30908b228a05123cf2ec6f97485
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/ZeeYuva_BTS/WDVLive/*~hmac=e8bc44f6ea01220f4c818e12f2481d8eedf143369d56dc6888c11cef75c818a4
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/ZeeYuva_BTS/WDVLive/*~hmac=e8bc44f6ea01220f4c818e12f2481d8eedf143369d56dc6888c11cef75c818a4"}
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeYuva_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/ZeeYuva_BTS/WDVLive/*~hmac=e8bc44f6ea01220f4c818e12f2481d8eedf143369d56dc6888c11cef75c818a4&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Zee 24 Taas" tvg-logo="https://img.media.jio.com/tvpimages/9/84/301342_1753198923876_l_medium.jpg" group-title="News", Zee 24 Taas
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e4a7458e3e02550082e97464f0d17937:cf3a9e59be0272d4dd2d63b7d75417ed,1ade9ab20c735684acfa38dacdfcb8c5:0650c82cfb1d4b56a90ae1f095e21159,ce7c15405b49552096c7e07a0d84cb98:ee9a3b8192d3dc42aff1c04a0e5fe12a,c038f68733de51ee8b505ae27b1a9c35:c6c46c4633eb8d281955b871a77aada1,ad96961254c85ef98521840d25f3386d:2f697b8ca2d2b48c34294ee843c0d5ca
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/Zee_24_Taas_BTS/WDVLive/*~hmac=504b87e48ad225e3cc0b25d3a589d4e1c0fac2448dfb9206e0767518b08c9062
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/Zee_24_Taas_BTS/WDVLive/*~hmac=504b87e48ad225e3cc0b25d3a589d4e1c0fac2448dfb9206e0767518b08c9062"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Zee_24_Taas_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/Zee_24_Taas_BTS/WDVLive/*~hmac=504b87e48ad225e3cc0b25d3a589d4e1c0fac2448dfb9206e0767518b08c9062&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro"""

    suspend fun loadZeeChannels(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        val content = fetchM3uContent(context)
        val zeeChannels = parseM3u(content)
        val channels = mutableListOf<Channel>()
        val newMap = mutableMapOf<String, ZeeChannel>()
        zeeChannels.forEach { zc ->
            newMap[zc.id] = zc
            newMap[zc.channelNumber.toString()] = zc
            newMap["zee_${zc.channelNumber}"] = zc
            channels.add(
                Channel(
                    id = zc.id,
                    name = zc.name,
                    logoUrl = zc.logoUrl,
                    group = zc.group,
                    streamUrl = zc.streamUrl,
                    isDrm = true,
                    channelNumber = zc.channelNumber,
                    licenseUrl = if (zc.licenseKey.isNotEmpty()) "jwk:" + clearKeyToJwkJson(zc.licenseKey) else null,
                    language = zc.language,
                    stbNumber = zc.stbNumber
                )
            )
        }
        zeeChannelsMap.putAll(newMap)
        channels
    }

    fun getZeeChannel(channelId: String): ZeeChannel? {
        return zeeChannelsMap[channelId]
    }

    fun isZeeChannel(channelId: String): Boolean {
        return zeeChannelsMap.containsKey(channelId) || channelId.startsWith("zee_")
    }

    private fun fetchM3uContent(context: Context): String {
        try {
            val url = URL(ZEE_M3U_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.contains("#EXTM3U") && text.contains("Zee")) {
                    try {
                        File(context.filesDir, CACHE_FILE).writeText(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write M3U cache: ${e.message}")
                    }
                    return text
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote M3U: ${e.message}")
        }

        // Try reading cached file
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val text = file.readText()
                if (text.contains("#EXTM3U")) return text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached M3U: ${e.message}")
        }

        return EMBEDDED_M3U
    }

    fun parseM3u(m3uContent: String): List<ZeeChannel> {
        val list = mutableListOf<ZeeChannel>()
        var currentName = ""
        var currentLogo = ""
        var currentGroupTitle = ""
        var currentLicenseKey = ""
        var currentUserAgent = "@shoebbro"
        var currentReferer = "https://www.jiotv.com/"
        var currentOrigin = "https://www.jiotv.com"
        var currentCookie = ""
        var currentManifestType = "mpd"

        val lines = m3uContent.lines()
        var index = 1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                currentName = parseTagValue(trimmed, "tvg-name").ifEmpty {
                    trimmed.substringAfterLast(",").trim()
                }
                currentLogo = parseTagValue(trimmed, "tvg-logo")
                currentGroupTitle = parseTagValue(trimmed, "group-title")
            } else if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                currentLicenseKey = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.manifest_type=")) {
                currentManifestType = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#EXTVLCOPT:http-user-agent=")) {
                currentUserAgent = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#EXTVLCOPT:http-referrer=")) {
                currentReferer = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#EXTVLCOPT:http-extra-headers=Origin:")) {
                currentOrigin = trimmed.substringAfter("Origin:").trim()
            } else if (trimmed.startsWith("#EXTVLCOPT:http-cookie=")) {
                currentCookie = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#EXTHTTP:")) {
                val jsonStr = trimmed.substringAfter("#EXTHTTP:")
                runCatching {
                    val obj = JSONObject(jsonStr)
                    if (obj.has("Cookie")) currentCookie = obj.getString("Cookie")
                    if (obj.has("Referer")) currentReferer = obj.getString("Referer")
                    if (obj.has("Origin")) currentOrigin = obj.getString("Origin")
                    if (obj.has("User-Agent")) currentUserAgent = obj.getString("User-Agent")
                }
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val rawUrl = trimmed
                var cleanUrl = rawUrl
                if (cleanUrl.contains("|")) {
                    val parts = cleanUrl.split("|")
                    cleanUrl = parts[0]
                    val paramsStr = parts.getOrNull(1).orEmpty()
                    paramsStr.split("&").forEach { p ->
                        if (p.startsWith("cookie=")) currentCookie = p.substringAfter("cookie=")
                        if (p.startsWith("referer=")) currentReferer = p.substringAfter("referer=")
                        if (p.startsWith("origin=")) currentOrigin = p.substringAfter("origin=")
                        if (p.startsWith("user-agent=")) currentUserAgent = p.substringAfter("user-agent=")
                    }
                }
                if (cleanUrl.endsWith("?")) cleanUrl = cleanUrl.dropLast(1)
                
                // If stream url doesn't have hdnea query param but cookie is present, attach cookie query with param name
                if (!cleanUrl.contains("__hdnea__") && currentCookie.contains("__hdnea__=")) {
                    val token = currentCookie.substringAfter("__hdnea__=")
                    cleanUrl += if (cleanUrl.contains("?")) "&__hdnea__=$token" else "?__hdnea__=$token"
                }

                val key = currentName.lowercase().replace(Regex("[^a-z0-9]"), "")
                val meta = CHANNEL_METADATA[key]
                val channelIdNum = meta?.channelId ?: (9000 + index)
                val finalName = meta?.cleanName ?: currentName.ifEmpty { "Zee Channel $index" }
                val finalGroup = meta?.group ?: currentGroupTitle.ifEmpty { "Entertainment" }
                val finalLang = meta?.language ?: "Hindi"
                val finalStb = meta?.stbNumber ?: 0

                list.add(
                    ZeeChannel(
                        id = channelIdNum.toString(),
                        name = finalName,
                        logoUrl = currentLogo,
                        group = finalGroup,
                        channelNumber = channelIdNum,
                        streamUrl = cleanUrl,
                        cookie = currentCookie,
                        userAgent = currentUserAgent,
                        referer = currentReferer,
                        origin = currentOrigin,
                        licenseKey = currentLicenseKey,
                        isMpd = currentManifestType.equals("mpd", ignoreCase = true) || cleanUrl.contains(".mpd"),
                        language = finalLang,
                        stbNumber = finalStb
                    )
                )

                // Reset for next channel
                currentName = ""
                currentLogo = ""
                currentGroupTitle = ""
                currentLicenseKey = ""
                currentUserAgent = "@shoebbro"
                currentReferer = "https://www.jiotv.com/"
                currentOrigin = "https://www.jiotv.com"
                currentCookie = ""
                currentManifestType = "mpd"
                index++
            }
        }
        return list
    }

    private fun parseTagValue(line: String, tagName: String): String {
        val pattern = "$tagName=\""
        val idx = line.indexOf(pattern)
        if (idx == -1) return ""
        val start = idx + pattern.length
        val end = line.indexOf("\"", start)
        return if (end != -1) line.substring(start, end) else ""
    }

    fun clearKeyToJwkJson(clearKeyStr: String): String {
        val pairs = clearKeyStr.split(",")
        val keysArray = JSONArray()
        for (pair in pairs) {
            val parts = pair.trim().split(":")
            if (parts.size == 2) {
                val keyIdBytes = hexToBytes(parts[0])
                val keyBytes = hexToBytes(parts[1])
                val kidB64 = Base64.encodeToString(keyIdBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                val kB64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                val obj = JSONObject()
                obj.put("kty", "oct")
                obj.put("kid", kidB64)
                obj.put("k", kB64)
                keysArray.put(obj)
            }
        }
        val json = JSONObject()
        json.put("keys", keysArray)
        json.put("type", "temporary")
        return json.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
