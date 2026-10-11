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
    const val STAR_SPORTS_M3U_URL = "https://raw.githubusercontent.com/rathee-ashish/zee-playlist/refs/heads/main/star_sports.m3u"
    private const val CACHE_FILE = "zee_playlist.m3u"
    private const val SPORTS_CACHE_FILE = "sports_playlist.m3u"

    private val zeeChannelsMap = java.util.concurrent.ConcurrentHashMap<String, ZeeChannel>()

    data class ZeeChannelMeta(
        val channelId: Int,
        val cleanName: String,
        val group: String,
        val language: String,
        val stbNumber: Int = 0
    )

    /**
     * Exact mapping of Zee TV, Star Sports, Sony Sports, and other sports channels
     * to their official JioTV channel numbers, categories, languages and STB numbers.
     */
    private val CHANNEL_METADATA = mapOf(
        // Zee Channels
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
        "zeezesthd" to ZeeChannelMeta(2757, "Zee Zest HD", "Lifestyle", "English"),
        "zeethirai" to ZeeChannelMeta(2256, "Zee Thirai", "Movies", "Tamil"),
        "zeethiraihd" to ZeeChannelMeta(3051, "Zee Thirai HD", "Movies", "Tamil"),

        // Star Sports Channels
        "starsportsselect1hd" to ZeeChannelMeta(460, "Star Sports Select 1 HD", "Sports", "English"),
        "starsportsselect1" to ZeeChannelMeta(1123, "Star Sports Select 1", "Sports", "English"),
        "starsportsselect2hd" to ZeeChannelMeta(461, "Star Sports Select 2 HD", "Sports", "English"),
        "starsportsselect2" to ZeeChannelMeta(1114, "Star Sports Select 2", "Sports", "English"),
        "starsports1hd" to ZeeChannelMeta(1106, "Star Sports 1 HD", "Sports", "English"),
        "starsports1" to ZeeChannelMeta(1142, "Star Sports 1", "Sports", "English"),
        "starsports1hindihd" to ZeeChannelMeta(1108, "Star Sports 1 Hindi HD", "Sports", "Hindi"),
        "starsports1hindi" to ZeeChannelMeta(362, "Star Sports 1 Hindi", "Sports", "Hindi"),
        "starsports2hd" to ZeeChannelMeta(1109, "Star Sports 2 HD", "Sports", "English"),
        "starsports2" to ZeeChannelMeta(1141, "Star Sports 2", "Sports", "English"),
        "starsports2hindihd" to ZeeChannelMeta(1984, "Star Sports 2 Hindi HD", "Sports", "Hindi"),
        "starsports2hindi" to ZeeChannelMeta(1985, "Star Sports 2 Hindi", "Sports", "Hindi"),
        "starsports3" to ZeeChannelMeta(1389, "Star Sports 3", "Sports", "Hindi"),
        "starsports1tamilhd" to ZeeChannelMeta(3277, "Star Sports 1 Tamil HD", "Sports", "Tamil"),
        "starsports1tamil" to ZeeChannelMeta(1124, "Star Sports 1 Tamil", "Sports", "Tamil"),
        "starsports1teluguhd" to ZeeChannelMeta(3278, "Star Sports 1 Telugu HD", "Sports", "Telugu"),
        "starsports1telugu" to ZeeChannelMeta(1651, "Star Sports 1 Telugu", "Sports", "Telugu"),
        "starsports1kannada" to ZeeChannelMeta(1650, "Star Sports 1 Kannada", "Sports", "Kannada"),
        "starsports2kannada" to ZeeChannelMeta(1122, "Star Sports 2 Kannada", "Sports", "Kannada"),
        "starsports2tamilhd" to ZeeChannelMeta(3273, "Star Sports 2 Tamil HD", "Sports", "Tamil"),
        "starsports2tamil" to ZeeChannelMeta(2853, "Star Sports 2 Tamil", "Sports", "Tamil"),
        "starsports2teluguhd" to ZeeChannelMeta(3274, "Star Sports 2 Telugu HD", "Sports", "Telugu"),
        "starsports2telugu" to ZeeChannelMeta(2852, "Star Sports 2 Telugu", "Sports", "Telugu"),

        // Sony Sports Channels
        "sonysportsten1hd" to ZeeChannelMeta(162, "Sony Sports Ten 1 HD", "Sports", "English"),
        "sonysportsten1" to ZeeChannelMeta(514, "Sony Sports Ten 1", "Sports", "English"),
        "sonysportsten2hd" to ZeeChannelMeta(891, "Sony Sports Ten 2 HD", "Sports", "English"),
        "sonysportsten2" to ZeeChannelMeta(523, "Sony Sports Ten 2", "Sports", "English"),
        "sonysportsten3hindihd" to ZeeChannelMeta(892, "Sony Sports Ten 3 Hindi HD", "Sports", "Hindi"),
        "sonysportsten3hindi" to ZeeChannelMeta(524, "Sony Sports Ten 3 Hindi", "Sports", "Hindi"),
        "sonysportsten4tamil" to ZeeChannelMeta(1774, "Sony Sports Ten 4 Tamil", "Sports", "Tamil"),
        "sonysportsten4telugu" to ZeeChannelMeta(1775, "Sony Sports Ten 4 Telugu", "Sports", "Telugu"),
        "sonysportsten4kannada" to ZeeChannelMeta(3585, "Sony Sports Ten 4 Kannada", "Sports", "Kannada"),
        "sonysportsten5hd" to ZeeChannelMeta(155, "Sony Sports Ten 5 HD", "Sports", "English"),
        "sonysportsten5" to ZeeChannelMeta(525, "Sony Sports Ten 5", "Sports", "English"),

        // Eurosport & DD Sports Channels
        "eurosport" to ZeeChannelMeta(1294, "Eurosport", "Sports", "English"),
        "eurosporthd" to ZeeChannelMeta(875, "Eurosport HD", "Sports", "English"),
        "ddsports" to ZeeChannelMeta(204, "DD Sports", "Sports", "Hindi")
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
https://jiotvpllive.cdn.jio.com/bpk-tv/Zee_24_Taas_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/Zee_24_Taas_BTS/WDVLive/*~hmac=504b87e48ad225e3cc0b25d3a589d4e1c0fac2448dfb9206e0767518b08c9062&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Zee Thirai HD" tvg-logo="https://img.media.jio.com/tvpimages/59/93/304103_1768465079003_l_medium.jpg" group-title="Movies", Zee Thirai HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=58d0716748ee52f4a55cf7baa6e072bb:8fc185a1015f026fec82236bb0e096f4,88aaf3c74a8258ce8c75c74d259e9d95:12c0cc0b355a86ff3e942123284b94fa,7ddfa2e971cd5ebf8d5337f404ed2878:49d966969a62e77d4ff1004772988b61,c87a82b43b1e5902829e408335628cd4:7b858623533c561989358530c6ad7af7,c2d762acbea256c0bd67b92d59f69757:46c59697457af3a687426a4691393774
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791720053~exp=1791741653~acl=/bpk-tv/ZeeThiraiHD_BTS/WDVLive/*~hmac=4e83653467809f61fdbe8afb0ebf42191e45a361f3d88d8ac0adc5cf1ff4385c
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791720053~exp=1791741653~acl=/bpk-tv/ZeeThiraiHD_BTS/WDVLive/*~hmac=4e83653467809f61fdbe8afb0ebf42191e45a361f3d88d8ac0adc5cf1ff4385c"}
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeThiraiHD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791720053~exp=1791741653~acl=/bpk-tv/ZeeThiraiHD_BTS/WDVLive/*~hmac=4e83653467809f61fdbe8afb0ebf42191e45a361f3d88d8ac0adc5cf1ff4385c&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro"""

    private const val EMBEDDED_SPORTS_M3U = """#EXTM3U

#EXTINF:-1 tvg-name="Sony Sports Ten 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/5/8/300256_1753198281835_l_medium.jpg" group-title="Sports", Sony Sports Ten 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=8ef8595765be890b424ee5d99ef6f953:adcff05f3aa10995f1efd4b0bb394d49,7a94b1419d77d2e184c7adcd10afc15a:1c4ce982d24be3d88dc0b1e2e4d74368,8779aa225d6050b3094944e00a2b0059:841054a0202ce08983f696e563d12941,3525c924e54154b39533d86b1c7a428a:8548b566fc6608a7d3bd601ee5f3d889,8619386d035251ad9340fc40879a52c6:9c5fb4a4f95089d9d1a3dc19b0d06e47,81ce3115633b29fe26c88d5ccec3cde2:84b9fb91c2bf59863d06fa5e0b757a4d
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=b404a3c24e95064323b9dcb53ea92cf8487d71c3e8e9bb9ce43eb499aa957041
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=b404a3c24e95064323b9dcb53ea92cf8487d71c3e8e9bb9ce43eb499aa957041"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=b404a3c24e95064323b9dcb53ea92cf8487d71c3e8e9bb9ce43eb499aa957041&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 5 HD" tvg-logo="https://img.media.jio.com/tvpimages/31/97/300258_1753198241206_l_medium.jpg" group-title="Sports", Sony Sports Ten 5 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=654c9e21b9dbe0ccfb6216779aadf143:2b2a8f6726f6520a0291d4c1218df45a,9691105db953d0bf43bd45a60daacdaa:fb21cfe47dff9693cc8b351f1aa6bbe1,42566c7287ce545d91234b03485cce8a:bd251578248e2f8df050eb81b04d056a,2b66229b90b9d2b66a0f91f62fc50bf4:dad09f9b5867be2a179d58db854f4910,8cb7b40cefe053ea91895e3194bb9912:467cf6d740b7212fcfc648644fb79943,5244667ee96e6039df792d7d7c598732:9be10de1281728dbada5b3ac36a610b8
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=a880c88c085a3b727c23b847407cb3f912feea2d65bc3780d074ca1aee5e4bdf
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=a880c88c085a3b727c23b847407cb3f912feea2d65bc3780d074ca1aee5e4bdf"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=a880c88c085a3b727c23b847407cb3f912feea2d65bc3780d074ca1aee5e4bdf&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="DD Sports" tvg-logo="https://img.media.jio.com/imagespublic/17/8/DDSports_1579505057008_tv.jpg" group-title="DD Channels", DD Sports
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=00f634f3ef7a5a8282b41a8dbe11a802:4f45c1e279595e264f1cb1578641cb7f,1bbe2053d860562ca6926fa6d3d9c4fd:5013ffb04450eb66bd33ded2fa893e8b,ef21e692f3af590b8d31064eb07f7e6c:e5a15827a8f8e47135de54f4b224ff47,dab8eeac092656bf829ba309c002c9d1:e9ac75dffbaf27a1d31d19d9878feed4,477d3698ac4555f7a3c30b3840547660:78c2ebd34df8ffdf45c30f0a684ac776
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=a74a07ad1151d350847c9c4025c7048f87698c7ca29f771a675a83da810cf227
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=a74a07ad1151d350847c9c4025c7048f87698c7ca29f771a675a83da810cf227"}
https://jiotvpllive.cdn.jio.com/bpk-tv/DD_Sports_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=a74a07ad1151d350847c9c4025c7048f87698c7ca29f771a675a83da810cf227&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Eurosport HD" tvg-logo="https://img.media.jio.com/tvpimages/16/0/301591_1764577876137_l_medium.jpg" group-title="Sports", Eurosport HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=546fc0ccb5f55930a4841bc103973655:8e42b4746f4faecad90cb4575d838480,cca1f4e1f2b35baf9c0c4ee0458bd528:de6ca32d38cf5f214c416b7dbb0bdd06,c6210817c30950769549bdb49bf6cd6e:effc83e62efc81dd4408f4d5f771fd9d,4512a52315f9581990d74250b3c997d9:95bcacafeb40ec0eb5a93097e9052483,7fb2893008d556e2be9f8895051f426d:9196d9ed0c4b6106407b23ad798cc6f6
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=1bcaac5aa69eb687c884280f5a6b9e0f6a97ca39436620e3316a72159c74db0a
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=1bcaac5aa69eb687c884280f5a6b9e0f6a97ca39436620e3316a72159c74db0a"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Dsports_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=1bcaac5aa69eb687c884280f5a6b9e0f6a97ca39436620e3316a72159c74db0a&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/94/91/300273_1753198175927_l_medium.jpg" group-title="Sports", Sony Sports Ten 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=91fce77f2f8056f084ed961c7ebe86ed:8214bd5eb6c21444e8ea644824affd79,7eb23fdb3765a427e5c0b0a1dcd7c7a6:fe44ecddba1ab9df11c5522d85c39127,060242b563d188a97ac529066c4dcdc3:d4a4920bb65a879b937a6cf9eb4c1592,aa0992cc96a0815c05619dd5ac448b15:24a1c23da2922264f06b2be0f24a3894,996b070e24d7d4670d4e39acdc426edd:429fd3f762e31db48cc8132ba0be525a,9eb13a589c9454e2be1f123b98ebbd09:dd503e53bc323a51d67c349f3be1993b
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=5354f25601c8c67c29a61f091d155a456a0ef669045e26c2c313176c70a47335
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=5354f25601c8c67c29a61f091d155a456a0ef669045e26c2c313176c70a47335"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=5354f25601c8c67c29a61f091d155a456a0ef669045e26c2c313176c70a47335&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 3 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/1/58/300255_1758886957880_l_medium.jpg" group-title="Sports", Sony Sports Ten 3 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=9206064ad8c9576d290ff2a7aae70b92:840e26bca9696f6afc2dd21b54072bb9,b36a06969623c2079f1b2ab5a9d1c7c5:f2ac8463bf3ce3090632352c26a8057c,dc7a410ba231bb588bdc474288669d19:b1dd02e33b6b83e04df4b72eaabd16f6,08638117638b55649c6befa7f5cb34f6:58ddb6bbdf8d8b1e2aac2b5312e99086,111e6908b965566e97139efddf7ef459:c87903abbc052e1f21e51ebd928d9cb1,17c4a71ccf934a616c91dd002dce09f4:b6af28ce54a2a3e26856cf2b06cccdb1
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=5264bdfa6b939362ddb462eafc5ccfa7bf5ef6466c88c9bbf2fb0d824e4cd23f
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=5264bdfa6b939362ddb462eafc5ccfa7bf5ef6466c88c9bbf2fb0d824e4cd23f"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721220~exp=1791742820~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=5264bdfa6b939362ddb462eafc5ccfa7bf5ef6466c88c9bbf2fb0d824e4cd23f&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Eurosport" tvg-logo="https://img.media.jio.com/tvpimages/31/7/301589_1764577915153_l_medium.jpg" group-title="Sports", Eurosport
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=389e35e8b5db5726bc56f83a9365c6ab:0a6f766f37e7c3622a4860b3c15bbc9b,6823d5fbee1857b9b5dac4e935efb6c3:72e801119a9684d336727fd4d6c1ba22,7f52f53d78805b2bb75b0f860095eb55:52f2472cff2a62100bda7d59b9db736a,7a610c65ec395eb5bd5014e6425575a9:8baa043ee2857121deff3f82874e5209,ea93cd3229e5595e9e115fce0dc576b7:cb7d0cd8b134c1b15ad9eb4a6261d9c0
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=e24df422b80a98b6e560a165262603df39fabb883a1ccf95d774367bd1371f5e
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=e24df422b80a98b6e560a165262603df39fabb883a1ccf95d774367bd1371f5e"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Dsports_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721221~exp=1791742821~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=e24df422b80a98b6e560a165262603df39fabb883a1ccf95d774367bd1371f5e&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/69/98/301739_1758886924465_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b14cdf86a8e8561bb68f98bf608d14f1:fe95ef259d5202e5471b8804c5323b8a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721222~exp=1791742822~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=3cefca9f141c90b5d2b05a9dc762fde42971dd76891742db60bae359b7fa2eee
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721222~exp=1791742822~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=3cefca9f141c90b5d2b05a9dc762fde42971dd76891742db60bae359b7fa2eee"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721222~exp=1791742822~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=3cefca9f141c90b5d2b05a9dc762fde42971dd76891742db60bae359b7fa2eee&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/21/10/304240_1787558465564_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=fa9ae315a29652db96c335e993b8c4bf:8ac0bc132a1c277052d8b023392e1685
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=3202e9806de0fa0fef1b27602a5bb1577eb9aa466fb1ece3d3ba3a08fd935f8b
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=3202e9806de0fa0fef1b27602a5bb1577eb9aa466fb1ece3d3ba3a08fd935f8b"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=3202e9806de0fa0fef1b27602a5bb1577eb9aa466fb1ece3d3ba3a08fd935f8b&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/88/41/304241_1787558888185_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=5de95acd59651fb70a273d75ec2258f644a5b7725e5a2d04adb2b290272dc569
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=5de95acd59651fb70a273d75ec2258f644a5b7725e5a2d04adb2b290272dc569"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonyTen4Kan_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791721227~exp=1791742827~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=5de95acd59651fb70a273d75ec2258f644a5b7725e5a2d04adb2b290272dc569&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-id="460" tvg-name="Star Sports Select 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/96/65/302113_1749664252703_l_medium.jpg" group-title="Sports",Star Sports Select 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=c2c048e439d65316beeda6ef64d5d0f8:e2fdc132cbdee65501b845ce414fce39
#EXTHTTP:{"cookie": "__hdnea__=st=1791232158~exp=1791253758~acl=/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/*~hmac=9ed699313fc13a08a1ed3f0bc594db0091461bcd48eeb773ed7be1cb0ea36bb7"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1123" tvg-name="Star Sports Select 1" tvg-logo="https://img.media.jio.com/tvpimages/13/82/301994_1749664069587_l_medium.jpg" group-title="Sports",Star Sports Select 1
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=ea7985dacbb95f1c9d62e5a1675142e5:39d5910ca04841b5f32bf24623cdae58
#EXTHTTP:{"cookie": "__hdnea__=st=1791232169~exp=1791253769~acl=/bpk-tv/Star_Sports_Select_1_BTS/WDVLive/*~hmac=9a5bef059a2fa0ce953489d049f9b291bc916345b6c068ee493f250c8e05397e"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_Select_1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="461" tvg-name="Star Sports Select 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/98/79/302114_1749665414817_l_medium.jpg" group-title="Sports",Star Sports Select 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=6017d0514d425fa38f6e80ba6dcc852f:0018944b00adf2078f62386f30c68b74
#EXTHTTP:{"cookie": "__hdnea__=st=1791232158~exp=1791253758~acl=/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/*~hmac=112e6287d6dd3e8b6b3fb52201848d07583ecef77909d7ab285613674de37fd3"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1114" tvg-name="Star Sports Select 2" tvg-logo="https://img.media.jio.com/tvpimages/98/48/301989_1749664475828_l_medium.jpg" group-title="Sports",Star Sports Select 2
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=ec7d009d07aa5cbc81a441880530dfa5:8147983ad7402ebfc222d28b667a196f
#EXTHTTP:{"cookie": "__hdnea__=st=1791232166~exp=1791253766~acl=/bpk-tv/Star_Sports_Select_2_BTS/WDVLive/*~hmac=79e78d58823b9028229c429c20ab40074321863195295b25395bc49f7090e6ae"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_Select_2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1106" tvg-name="Star Sports 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/76/32/301981_1749665664018_l_medium.jpg" group-title="Sports",Star Sports 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=965dc2ddb1d85138ad787999a7f30ca5:859695076e67fe961836b564db6d689c
#EXTHTTP:{"cookie": "__hdnea__=st=1791232166~exp=1791253766~acl=/bpk-tv/Star_Sports_HD1_BTS/WDVLive/*~hmac=97f1471f0a4f73ce2b3cd012d16d452773eec4f21a28a7d1362ae574d18c5e23"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_HD1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1142" tvg-name="Star Sports 1" tvg-logo="https://img.media.jio.com/tvpimages/56/92/302115_1749665579801_l_medium.jpg" group-title="Sports",Star Sports 1
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=df20894ec30954d3b28097c138f4cfda:ff9b6292b1cddbd11a046029cb314634
#EXTHTTP:{"cookie": "__hdnea__=st=1791232171~exp=1791253771~acl=/bpk-tv/Star_Sports_1_BTS/WDVLive/*~hmac=cbc11e9370492c40002ba337f4103348c584ccd1bb651a9e4a2c7f75d492df0b"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1108" tvg-name="Star Sports 1 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/5/6/301982_1749665314605_l_medium.jpg" group-title="Sports",Star Sports 1 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=400131994b445d8c8817202248760fda:2d56cb6f07a75b9aff165d534ae2bfc4
#EXTHTTP:{"cookie": "__hdnea__=st=1791231592~exp=1791253192~acl=/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/*~hmac=e44c715535c4edfda9361db7f2cc447c5884a8c759c9883810039e728344daae"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="362" tvg-name="Star Sports 1 Hindi" tvg-logo="https://img.media.jio.com/tvpimages/13/81/302112_1749665154709_l_medium.jpg" group-title="Sports",Star Sports 1 Hindi
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=8ea9b607549252068e260a5f4d7dd321:350c0aaffedf51cc1502e9543c7c885e
#EXTHTTP:{"cookie": "__hdnea__=st=1791232158~exp=1791253758~acl=/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/*~hmac=71aef1cce88edc07be7d6c989fc70e5dd237a03856e88be9069413fbc23b0ff0"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1109" tvg-name="Star Sports 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/41/51/301983_1749666169105_l_medium.jpg" group-title="Sports",Star Sports 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=9457eb90129456fa8ea95e10ba4ac51e:e620a970cea474c491ac78ae71a4d764
#EXTHTTP:{"cookie": "__hdnea__=st=1791232165~exp=1791253765~acl=/bpk-tv/Star_Sports_HD2_BTS/WDVLive/*~hmac=1447321a8cf707d93c4e9c200d9c5627539001cf93005999ad55a1fb991f579a"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_HD2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1141" tvg-name="Star Sports 2" tvg-logo="https://img.media.jio.com/tvpimages/11/30/302116_1749666029079_l_medium.jpg" group-title="Sports",Star Sports 2
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=fc3aafe80845584199a637249fad2ffa:ca4474972fdb4bf8ecb164482853c692
#EXTHTTP:{"cookie": "__hdnea__=st=1791232169~exp=1791253769~acl=/bpk-tv/Star_Sports_2_BTS/WDVLive/*~hmac=7bc90aa07510b5e407dc719523b779e45cf3eaf872f9439f77466ecf3955888b"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1984" tvg-name="Star Sports 2 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/56/36/301229_1742214332015_l_medium.jpg" group-title="Sports",Star Sports 2 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=bd1ea6a1c1fb5dcdb709434e0826bbc0:f57f22cba32739ce7beb1fab3f3d060b
#EXTHTTP:{"cookie": "__hdnea__=st=1791232176~exp=1791253776~acl=/bpk-tv/StarSportsHD2Hin_BTS/WDVLive/*~hmac=47c26d5f846065f5476fb8b199fa2f03d10341cb0cb074972dd3f176782e1205"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/StarSportsHD2Hin_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1985" tvg-name="Star Sports 2 Hindi" tvg-logo="https://img.media.jio.com/tvpimages/57/17/302124_1749130246443_l_medium.jpg" group-title="Sports",Star Sports 2 Hindi
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a3ed8dc0e0cb5d238da80ee0a85bd2e1:67a96992e6406606f3480ab58d7f540c
#EXTHTTP:{"cookie": "__hdnea__=st=1791232177~exp=1791253777~acl=/bpk-tv/StarSports2Hin_BTS/WDVLive/*~hmac=1b0912124e8ce25661126108dda1decae17fb1846a910c1304d34abfb1b58b15"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Sports18_1_SD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1389" tvg-name="Star Sports 3" tvg-logo="https://img.media.jio.com/tvpimages/78/2/302117_1749666092192_l_medium.jpg" group-title="Sports",Star Sports 3
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e4e8647eaf0659ac85f61765a5abcdc1:d749c9b27505beb0e1a8b6c30ca65de6
#EXTHTTP:{"cookie": "__hdnea__=st=1791232172~exp=1791253772~acl=/bpk-tv/Star_Sports_3_BTS/WDVLive/*~hmac=df87e46d9cb280accd9145be1638b6200e62a975a6cff4d3628bdd3b510add02"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_3_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="3277" tvg-name="Star Sports 1 Tamil HD" tvg-logo="https://img.media.jio.com/tvpimages/19/19/302122_1749662923298_l_medium.jpg" group-title="Sports",Star Sports 1 Tamil HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=2272c0bc6b2e5cb090b1f55996e0026c:943923934de1d5536ab01c67fca8b337
#EXTHTTP:{"cookie": "__hdnea__=st=1791233932~exp=1791255532~acl=/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/*~hmac=88591c026954bf8afc373fd855f9becd4668dba1e079de3ff163ce30e78be88c"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1124" tvg-name="Star Sports 1 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/69/73/301995_1749664384503_l_medium.jpg" group-title="Sports",Star Sports 1 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=d0d750ebd52b5756aa7fb833f61ceece:4ae62a20c030c918aad24193c1dda229
#EXTHTTP:{"cookie": "__hdnea__=st=1791234106~exp=1791255706~acl=/bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/*~hmac=0a682b6510b7e1c9114983d81ccdf3aba30a5bbd6dafe41bac3db6019d1216bc"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="3278" tvg-name="Star Sports 1 Telugu HD" tvg-logo="https://img.media.jio.com/tvpimages/19/57/302123_1749663029798_l_medium.jpg" group-title="Sports",Star Sports 1 Telugu HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=49e84b9edfd85379b90c81b7d2d3d2bf:19c82d3ff094fa06af95820a1572ecb5
#EXTHTTP:{"cookie": "__hdnea__=st=1791234206~exp=1791255806~acl=/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/*~hmac=5e59d0da58b6a318eec352288ed1aaf0038ecd1cdc9f8266d1412bb1df8ed1d7"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1651" tvg-name="Star Sports 1 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/26/67/302119_1749662981095_l_medium.jpg" group-title="Sports",Star Sports 1 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=200a6dd89c6b5a48b9991d8ab57fc7d2:1f62834669a97e92ea1f3263274853fc
#EXTHTTP:{"cookie": "__hdnea__=st=1791232173~exp=1791253773~acl=/bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/*~hmac=e0a6e43c53edbd592db70040b20f2647cff85c4389b1d634f7b731ed0aa9538e"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1650" tvg-name="Star Sports 1 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/13/91/302118_1749663894692_l_medium.jpg" group-title="Sports",Star Sports 1 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=9a6d058fa40b5460a946178900eb44cb:791d7444110cec32d731ca0c33ac6c15
#EXTHTTP:{"cookie": "__hdnea__=st=1791232174~exp=1791253774~acl=/bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/*~hmac=abd8fc961079bd7daf8de4906d30f2850b95cc0d500a9e3d6691ed97117f3e09"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="1122" tvg-name="Star Sports 2 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/11/37/301993_1749664589518_l_medium.jpg" group-title="Sports",Star Sports 2 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=7af2add2968a56309519ac2680c18f25:f992a14e0def17ccdfe16f58c4ce56c5
#EXTHTTP:{"cookie": "__hdnea__=st=1791232168~exp=1791253768~acl=/bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/*~hmac=14485acefe970bef01bb56b17a1014bb85230ca10eabf5fddb0917ef45143371"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="3273" tvg-name="Star Sports 2 Tamil HD" tvg-logo="https://img.media.jio.com/tvpimages/50/43/302120_1749664850896_l_medium.jpg" group-title="Sports",Star Sports 2 Tamil HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=dc44fe6251825774b90d58dcfa7d94e0:63e3ae853f483a86752b5b9e6d61eb20
#EXTHTTP:{"cookie": "__hdnea__=st=1791232179~exp=1791253779~acl=/bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/*~hmac=e91f31948862f60353b5becefc7d5c42e282def98665e92dea7bccd2ce00f378"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="2853" tvg-name="Star Sports 2 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/74/92/301561_1749664725619_l_medium.jpg" group-title="Sports",Star Sports 2 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=3979aae1ca51561c9e8074cd618b3864:ae12e0e147d3e76cfa62bfb99bc07109
#EXTHTTP:{"cookie": "__hdnea__=st=1791232179~exp=1791253779~acl=/bpk-tv/StarSports2Tamil_BTS/WDVLive/*~hmac=cc18a3226757ad8370e91cefb505d7947115d123bc9b9915c4f96f7569789109"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Sports18_3_MOB/WDVCatchup/index.mpd

#EXTINF:-1 tvg-id="3274" tvg-name="Star Sports 2 Telugu HD" tvg-logo="https://img.media.jio.com/tvpimages/84/67/302121_1749664974599_l_medium.jpg" group-title="Sports",Star Sports 2 Telugu HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=c8a2fb190fc957c1b8333af9a7a39d31:be7497b59d4ce9e33d29bfd1d38bad1d
#EXTHTTP:{"cookie": "__hdnea__=st=1791232182~exp=1791253782~acl=/bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/*~hmac=1b346b4a5772cdf24eeb1041c41a24788ba6f90316a4815bbacc628f68bc2b9b"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-id="2852" tvg-name="Star Sports 2 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/41/94/301562_1749665043898_l_medium.jpg" group-title="Sports",Star Sports 2 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e38f46718d9d5c40af83095f176ba41e:8836ee0f7377d71c156a139361a45be5
#EXTHTTP:{"cookie": "__hdnea__=st=1791232179~exp=1791253779~acl=/bpk-tv/StarSports2Telugu_BTS/WDVLive/*~hmac=466b70713a5677005ae0a22a5fe545b2fc4144837f46c079c0e4a62f6497ce29"}
#EXTVLCOPT:http-user-agent=Virat Paglu
https://jiotvpllive.cdn.jio.com//bpk-tv/Sports18_2_MOB/WDVCatchup/index.mpd"""

    suspend fun loadZeeChannels(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        val zeeContent = fetchM3uContent(context, ZEE_M3U_URL, CACHE_FILE, EMBEDDED_M3U)
        val sportsContent = fetchM3uContent(context, STAR_SPORTS_M3U_URL, SPORTS_CACHE_FILE, EMBEDDED_SPORTS_M3U)

        val combinedChannels = mutableListOf<ZeeChannel>()
        combinedChannels.addAll(parseM3u(zeeContent))
        combinedChannels.addAll(parseM3u(sportsContent))

        // Also merge any missing embedded channels from fallback M3Us so they are guaranteed present
        val embeddedZee = parseM3u(EMBEDDED_M3U)
        val embeddedSports = parseM3u(EMBEDDED_SPORTS_M3U)
        val existingNumbers = combinedChannels.map { it.channelNumber }.toMutableSet()
        for (ec in (embeddedZee + embeddedSports)) {
            if (ec.channelNumber !in existingNumbers) {
                combinedChannels.add(ec)
                existingNumbers.add(ec.channelNumber)
            }
        }

        val channels = mutableListOf<Channel>()
        val newMap = mutableMapOf<String, ZeeChannel>()
        combinedChannels.forEach { zc ->
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

    private fun fetchM3uContent(
        context: Context,
        urlStr: String,
        cacheFileName: String,
        fallbackM3u: String
    ): String {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.contains("#EXTM3U")) {
                    try {
                        File(context.filesDir, cacheFileName).writeText(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write M3U cache ($cacheFileName): ${e.message}")
                    }
                    return text
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote M3U ($urlStr): ${e.message}")
        }

        // Try reading cached file
        try {
            val file = File(context.filesDir, cacheFileName)
            if (file.exists()) {
                val text = file.readText()
                if (text.contains("#EXTM3U")) return text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached M3U ($cacheFileName): ${e.message}")
        }

        return fallbackM3u
    }

    fun parseM3u(m3uContent: String): List<ZeeChannel> {
        val list = mutableListOf<ZeeChannel>()
        var currentId = ""
        var currentName = ""
        var currentLogo = ""
        var currentGroupTitle = ""
        var currentLangTag = ""
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
                currentId = parseTagValue(trimmed, "tvg-id")
                currentName = parseTagValue(trimmed, "tvg-name").ifEmpty {
                    trimmed.substringAfterLast(",").trim()
                }
                currentLogo = parseTagValue(trimmed, "tvg-logo")
                currentGroupTitle = parseTagValue(trimmed, "group-title")
                currentLangTag = parseTagValue(trimmed, "tvg-language")
            } else if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                currentLicenseKey = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#KODIPROP:inputstream.adaptive.manifest_type=")) {
                currentManifestType = trimmed.substringAfter("=")
            } else if (trimmed.startsWith("#EXTVLCOPT:http-user-agent=")) {
                currentUserAgent = trimmed.substringAfter("=").filter { it != '"' }.trim()
            } else if (trimmed.startsWith("#EXTVLCOPT:http-referrer=")) {
                currentReferer = trimmed.substringAfter("=").trim()
            } else if (trimmed.startsWith("#EXTVLCOPT:http-extra-headers=Origin:")) {
                currentOrigin = trimmed.substringAfter("Origin:").trim()
            } else if (trimmed.startsWith("#EXTVLCOPT:http-cookie=")) {
                currentCookie = trimmed.substringAfter("=").trim()
            } else if (trimmed.startsWith("#EXTHTTP:")) {
                val jsonStr = trimmed.substringAfter("#EXTHTTP:")
                val cookieVal = parseJsonKey(jsonStr, "cookie").ifEmpty { parseJsonKey(jsonStr, "Cookie") }
                if (cookieVal.isNotEmpty()) currentCookie = cookieVal
                val refererVal = parseJsonKey(jsonStr, "Referer")
                if (refererVal.isNotEmpty()) currentReferer = refererVal
                val originVal = parseJsonKey(jsonStr, "Origin")
                if (originVal.isNotEmpty()) currentOrigin = originVal
                val uaVal = parseJsonKey(jsonStr, "User-Agent")
                if (uaVal.isNotEmpty()) currentUserAgent = uaVal
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                var cleanUrl = trimmed
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
                cleanUrl = cleanUrl.replace("://jiotvpllive.cdn.jio.com//bpk-tv/", "://jiotvpllive.cdn.jio.com/bpk-tv/")

                // If stream url doesn't have hdnea query param but cookie is present, attach cookie query with param name
                if (!cleanUrl.contains("__hdnea__") && currentCookie.contains("__hdnea__=")) {
                    val token = currentCookie.substringAfter("__hdnea__=")
                    cleanUrl += if (cleanUrl.contains("?")) "&__hdnea__=$token" else "?__hdnea__=$token"
                }

                val key = currentName.lowercase().replace(Regex("[^a-z0-9]"), "")
                val meta = CHANNEL_METADATA[key]
                val channelIdNum = meta?.channelId
                    ?: currentId.toIntOrNull()
                    ?: (9000 + index)
                val finalName = meta?.cleanName ?: currentName.ifEmpty { "Channel $index" }
                val finalGroup = meta?.group ?: currentGroupTitle.ifEmpty { "Sports" }
                val finalLang = meta?.language ?: currentLangTag.ifEmpty { "Hindi" }
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
                currentId = ""
                currentName = ""
                currentLogo = ""
                currentGroupTitle = ""
                currentLangTag = ""
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

    private fun parseJsonKey(jsonStr: String, key: String): String {
        val pattern = "\"$key\""
        val keyIdx = jsonStr.indexOf(pattern)
        if (keyIdx == -1) return ""
        val colonIdx = jsonStr.indexOf(":", keyIdx + pattern.length)
        if (colonIdx == -1) return ""
        val firstQuote = jsonStr.indexOf("\"", colonIdx + 1)
        if (firstQuote == -1) return ""
        val secondQuote = jsonStr.indexOf("\"", firstQuote + 1)
        return if (secondQuote != -1) jsonStr.substring(firstQuote + 1, secondQuote) else ""
    }

    fun clearKeyToJwkJson(clearKeyStr: String): String {
        val pairs = clearKeyStr.split(",")
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val keyEntries = mutableListOf<String>()
        for (pair in pairs) {
            val parts = pair.trim().split(":")
            if (parts.size == 2) {
                val keyIdBytes = hexToBytes(parts[0])
                val keyBytes = hexToBytes(parts[1])
                val kidB64 = encoder.encodeToString(keyIdBytes)
                val kB64 = encoder.encodeToString(keyBytes)
                keyEntries.add("""{"kty":"oct","kid":"$kidB64","k":"$kB64"}""")
            }
        }
        return """{"keys":[${keyEntries.joinToString(",")}],"type":"temporary"}"""
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
