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
    const val STAR_SPORTS_M3U_URL = "https://premiumplugx.top/jiostb/mjelo.php?view=raw"
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
        "starsportskhel" to ZeeChannelMeta(1998, "Star Sports Khel", "Sports", "Hindi"),
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
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=4bc1865385be2095f3c32636245411c3d9d7db835e204b5bd5f410743163aec6
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=4bc1865385be2095f3c32636245411c3d9d7db835e204b5bd5f410743163aec6"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/*~hmac=4bc1865385be2095f3c32636245411c3d9d7db835e204b5bd5f410743163aec6&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 5 HD" tvg-logo="https://img.media.jio.com/tvpimages/31/97/300258_1753198241206_l_medium.jpg" group-title="Sports", Sony Sports Ten 5 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=654c9e21b9dbe0ccfb6216779aadf143:2b2a8f6726f6520a0291d4c1218df45a,9691105db953d0bf43bd45a60daacdaa:fb21cfe47dff9693cc8b351f1aa6bbe1,42566c7287ce545d91234b03485cce8a:bd251578248e2f8df050eb81b04d056a,2b66229b90b9d2b66a0f91f62fc50bf4:dad09f9b5867be2a179d58db854f4910,8cb7b40cefe053ea91895e3194bb9912:467cf6d740b7212fcfc648644fb79943,5244667ee96e6039df792d7d7c598732:9be10de1281728dbada5b3ac36a610b8
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=7cbe03b7fac47cb0c09edbe85913f3dbac7f524c55957a290343f2d2ef426412
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=7cbe03b7fac47cb0c09edbe85913f3dbac7f524c55957a290343f2d2ef426412"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen5HDSTB_BTS/WDVLive/*~hmac=7cbe03b7fac47cb0c09edbe85913f3dbac7f524c55957a290343f2d2ef426412&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="DD Sports" tvg-logo="https://img.media.jio.com/imagespublic/17/8/DDSports_1579505057008_tv.jpg" group-title="DD Channels", DD Sports
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=00f634f3ef7a5a8282b41a8dbe11a802:4f45c1e279595e264f1cb1578641cb7f,1bbe2053d860562ca6926fa6d3d9c4fd:5013ffb04450eb66bd33ded2fa893e8b,ef21e692f3af590b8d31064eb07f7e6c:e5a15827a8f8e47135de54f4b224ff47,dab8eeac092656bf829ba309c002c9d1:e9ac75dffbaf27a1d31d19d9878feed4,477d3698ac4555f7a3c30b3840547660:78c2ebd34df8ffdf45c30f0a684ac776
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738619~exp=1791760219~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=8523bcfb36a7cd9548a81dbc1a618f31227ba260f2b1f52a23dfebbfd159301d
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738619~exp=1791760219~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=8523bcfb36a7cd9548a81dbc1a618f31227ba260f2b1f52a23dfebbfd159301d"}
https://jiotvpllive.cdn.jio.com/bpk-tv/DD_Sports_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738619~exp=1791760219~acl=/bpk-tv/DD_Sports_BTS/WDVLive/*~hmac=8523bcfb36a7cd9548a81dbc1a618f31227ba260f2b1f52a23dfebbfd159301d&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Hindi" tvg-logo="https://img.media.jio.com/tvpimages/13/81/302112_1753186300125_l_medium.jpg" group-title="Sports", Star Sports 1 Hindi
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=30039b6e2c545c01aa84e649875c4977:7aa610c68000fe9ed97851d40a52abff,c2f0a204c6c353428954740d05d73c3d:6543b1984330109886d2864d06096590,a9cb8b39035a5393a4450f56c35b15a2:96f3585aab24109d64b74e9f0c6a5ed3,6a28a05d0f9a5e09987e4267eab5a947:38d9de645400fe2df985177aafb6a248,187e53dccc7c50f5b43306635bc93adb:72f9a4a4ff898de730991ae465d06388
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/*~hmac=e19d995d82db073eed325dcf564941459053b26282c96d4e11714918aa76501a
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/*~hmac=e19d995d82db073eed325dcf564941459053b26282c96d4e11714918aa76501a"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/*~hmac=e19d995d82db073eed325dcf564941459053b26282c96d4e11714918aa76501a&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports Select 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/98/79/302114_1763719749756_l_medium.jpg" group-title="Sports", Star Sports Select 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=6017d0514d425fa38f6e80ba6dcc852f:0018944b00adf2078f62386f30c68b74,635c740da1cc6d220e356a9b32388a34:0a6c7162aa9174ca4f48914924313465,26a953325a18595be75d2a9655a12afa:44d33860be354f77554aa422fd6d44dd,3db73fcd1d50da37cf7519f663d6bc07:80ba58c928ced9f69d0546529a9a1672,573b359b03bcbde109a9b239cd4297cf:6491a65fe527e0919553e1d1abd75e7a,25fbb2a932b25e9aacae947b2f873d3c:ac6a6a416b9d26be6819e80c2fe5d39f,cb4b21e0cad15e00ba3819fb91660fec:fe6480d2894746ba5799a3c2847e5589,2c443dffefb256a5b7a1364cc4af3529:1d78f35200eb5977a95de669788cf128,928d9058c0a8550fb6ba315124a76cd5:46811be73195049a23b731bd9344cad4,e5c47cf992a75f98b4e1a4d262de61e8:441f225881f816c6bedece0e86ef80e7
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/*~hmac=224ab3c06d81b2153a262d7eb7af7b5dbff10a9ae47582147f99ead21ef864ef
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/*~hmac=224ab3c06d81b2153a262d7eb7af7b5dbff10a9ae47582147f99ead21ef864ef"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/*~hmac=224ab3c06d81b2153a262d7eb7af7b5dbff10a9ae47582147f99ead21ef864ef&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports Select 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/96/65/302113_1753203780129_l_medium.jpg" group-title="Sports", Star Sports Select 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=8fc8499852ea5182b2a9307bd921cea1:c0ff2fbd59e26d6633f98af470c4d7b6,272c9b98274250f1b0bae9e19612275a:0fbbb12efcf16e2817b3c881847b2abf,fe38155e1cee53778db627b35bd2d20b:13d7c44c0e3fee8d9186a0491207629c,337fe34bbf2152c1bfe5ea8787072cd4:4efacd3a5af887dea393f0b957d266b6,cc04426ac28359a7b620934ddbbecb7c:0074c460557b1f21a60fa0aada550e3a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/*~hmac=a51dca73f473287a3c77f0263bc152d9f64f6881b52d2af62efe573057f85483
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/*~hmac=a51dca73f473287a3c77f0263bc152d9f64f6881b52d2af62efe573057f85483"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/*~hmac=a51dca73f473287a3c77f0263bc152d9f64f6881b52d2af62efe573057f85483&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Eurosport HD" tvg-logo="https://img.media.jio.com/tvpimages/16/0/301591_1764577876137_l_medium.jpg" group-title="Sports", Eurosport HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=546fc0ccb5f55930a4841bc103973655:8e42b4746f4faecad90cb4575d838480,cca1f4e1f2b35baf9c0c4ee0458bd528:de6ca32d38cf5f214c416b7dbb0bdd06,c6210817c30950769549bdb49bf6cd6e:effc83e62efc81dd4408f4d5f771fd9d,4512a52315f9581990d74250b3c997d9:95bcacafeb40ec0eb5a93097e9052483,7fb2893008d556e2be9f8895051f426d:9196d9ed0c4b6106407b23ad798cc6f6
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=53ba700fee69fc5fcf6f7f481a0048c6679ea708cd114a51a7cc808fd2bed476
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=53ba700fee69fc5fcf6f7f481a0048c6679ea708cd114a51a7cc808fd2bed476"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Dsports_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_HD_BTS/WDVLive/*~hmac=53ba700fee69fc5fcf6f7f481a0048c6679ea708cd114a51a7cc808fd2bed476&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/94/91/300273_1753198175927_l_medium.jpg" group-title="Sports", Sony Sports Ten 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=91fce77f2f8056f084ed961c7ebe86ed:8214bd5eb6c21444e8ea644824affd79,7eb23fdb3765a427e5c0b0a1dcd7c7a6:fe44ecddba1ab9df11c5522d85c39127,060242b563d188a97ac529066c4dcdc3:d4a4920bb65a879b937a6cf9eb4c1592,aa0992cc96a0815c05619dd5ac448b15:24a1c23da2922264f06b2be0f24a3894,996b070e24d7d4670d4e39acdc426edd:429fd3f762e31db48cc8132ba0be525a,9eb13a589c9454e2be1f123b98ebbd09:dd503e53bc323a51d67c349f3be1993b
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=ba7b3e597c0cd063b1b869769a1825539f3d266f3c8f9a25dc489940b514cac0
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=ba7b3e597c0cd063b1b869769a1825539f3d266f3c8f9a25dc489940b514cac0"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen2HDSTB_BTS/WDVLive/*~hmac=ba7b3e597c0cd063b1b869769a1825539f3d266f3c8f9a25dc489940b514cac0&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 3 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/1/58/300255_1758886957880_l_medium.jpg" group-title="Sports", Sony Sports Ten 3 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=9206064ad8c9576d290ff2a7aae70b92:840e26bca9696f6afc2dd21b54072bb9,b36a06969623c2079f1b2ab5a9d1c7c5:f2ac8463bf3ce3090632352c26a8057c,dc7a410ba231bb588bdc474288669d19:b1dd02e33b6b83e04df4b72eaabd16f6,08638117638b55649c6befa7f5cb34f6:58ddb6bbdf8d8b1e2aac2b5312e99086,111e6908b965566e97139efddf7ef459:c87903abbc052e1f21e51ebd928d9cb1,17c4a71ccf934a616c91dd002dce09f4:b6af28ce54a2a3e26856cf2b06cccdb1
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=98a95727848e2a34225ec5f67116a119a9d90806d142265860df7bca03f5dc68
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=98a95727848e2a34225ec5f67116a119a9d90806d142265860df7bca03f5dc68"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738622~exp=1791760222~acl=/bpk-tv/SonySportsTen3HinHDSTB_BTS/WDVLive/*~hmac=98a95727848e2a34225ec5f67116a119a9d90806d142265860df7bca03f5dc68&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/76/32/301981_1749665662917_p.jpg" group-title="Sports", Star Sports 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=45825561f4ef516691a9e16d1d35dd1d:3951fe7fbf4b0b33e6246fc401f76797,bf95222d9be85d85a91281e796e99481:bc932276031b3e63758cf67b147c3557,f217e9fe133e5b948c323abef1e39b47:63640dc4430d14003def0c97a2dbab4d,eda3132244745c10920b01536bc8a75b:2b5f8bdba279032943a040078bb22b35,06dd6287c4b956419d15d6c8e513aeed:a82d6b850b5c281c0054fc6d55e1c6ba
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_BTS/WDVLive/*~hmac=18d97a63bea741840880077342e7d864e4d10309c480e3f50a5ec1cb0cde9412
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_BTS/WDVLive/*~hmac=18d97a63bea741840880077342e7d864e4d10309c480e3f50a5ec1cb0cde9412"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_BTS/WDVLive/*~hmac=18d97a63bea741840880077342e7d864e4d10309c480e3f50a5ec1cb0cde9412&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/5/6/301982_1760005838319_l_medium.jpg" group-title="Sports", Star Sports 1 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a1ce6b9514f35174bda80ddd425bfd88:5c5e158724eced92f92ca8286fb28fd9,dd2fa604afc45e528f8a2283bf3cb628:bf01e6e8ff74797fb5215fead779e8b9,209975f6753d5de1ac26d604dace5313:1c54093eff75aeeccd30ebbc53c0e5a3,7bdccdb3dd015c37a0c3bd70675c8e4c:6f1b59376090d1f7ce33cbd264e87c36,11073b8f426e5092a2effada3bfaf721:5d1c59a8052956bd056810f260161e5f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/*~hmac=aed15d9188eb75cbc4d50346af1e9d7858ee3e1d341fdaa8779dc0263e88301d
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/*~hmac=aed15d9188eb75cbc4d50346af1e9d7858ee3e1d341fdaa8779dc0263e88301d"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/*~hmac=aed15d9188eb75cbc4d50346af1e9d7858ee3e1d341fdaa8779dc0263e88301d&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/41/51/301983_1753189521352_l_medium.jpg" group-title="Sports", Star Sports 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b99144f27b865d6ba1007fead82b2783:b2f10f09a771327b9bc69484d78e4595,04204c98718a52bf9e88d5e676fd7a63:7eebb539b03f674a26e84ae096320c70,33fab6f82a59590eb875f949389e03a9:26eff9e972154e624faf259ae0ee78ec,a5ad7dec861451a49029044a3b9d30bb:eff028a1ed3253b6a68b6a9cb4f1a0e4,c1ea31b9099b5030b14b67b41dc547d2:db74fb55442bc9cf7e8e9f364b3fe37f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD2_BTS/WDVLive/*~hmac=f31472dfab836085c9fb85fe9bf889c38877abb8c017560d3f3479ae730c70c7
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD2_BTS/WDVLive/*~hmac=f31472dfab836085c9fb85fe9bf889c38877abb8c017560d3f3479ae730c70c7"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD2_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD2_BTS/WDVLive/*~hmac=f31472dfab836085c9fb85fe9bf889c38877abb8c017560d3f3479ae730c70c7&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports Select 1" tvg-logo="https://img.media.jio.com/tvpimages/13/82/301994_1753199405995_l_medium.jpg" group-title="Sports", Star Sports Select 1
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=df07fac4675a597f92cbe2edbffb7c23:0d6b8e6559306e043bf26c7c8ffce7ae,2fc7c3483e9c55649ef931c45f07c23a:fe4b825679c963bd512fdc915ca1285c,00ff99896b8255da8e2aa9c5f34ab032:a23957bf29cd2e732c74bb97854a3df4,215579a9533d5eb1974eff2778f34853:7ea41017954ec6a5317e8b7ba94bcab5,e59df2d0e3545c9aae4889fb2aa2a3c4:e1e9fac754f6c9c772f7a05536aff920
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_1_BTS/WDVLive/*~hmac=37210fcf5ddae47dc5dce3775f853a6f02cc998bfff8f60d3991741c79f16614
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_1_BTS/WDVLive/*~hmac=37210fcf5ddae47dc5dce3775f853a6f02cc998bfff8f60d3991741c79f16614"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_1_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_1_BTS/WDVLive/*~hmac=37210fcf5ddae47dc5dce3775f853a6f02cc998bfff8f60d3991741c79f16614&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/11/37/301993_1753188279062_l_medium.jpg" group-title="Sports", Star Sports 2 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=7af2add2968a56309519ac2680c18f25:f992a14e0def17ccdfe16f58c4ce56c5,c1912ef5d59d6294c3fb320a3055a4af:955c74eb562982ceeffc783b596b53a1,3e21bd6fbe7df4ca1c424268070ab851:6d8b3b875f13799209d7654a86782313,e538f2cc72114d7785b4b5303441a31d:87bee8c558864951f82e5c6caa0282fa,bf4131c5bb8701ebb100635aaaa7bb80:451169b3ed1b9e0adeba5ecd487682fe
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/*~hmac=a5defe18c1d8b6d9240c4c81728ac888f10a5944645882fd7d9f2c428dc0efc1
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/*~hmac=a5defe18c1d8b6d9240c4c81728ac888f10a5944645882fd7d9f2c428dc0efc1"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_2_Kannada_BTS/WDVLive/*~hmac=a5defe18c1d8b6d9240c4c81728ac888f10a5944645882fd7d9f2c428dc0efc1&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/69/73/301995_1753199516930_l_medium.jpg" group-title="Sports", Star Sports 1 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a05f788e6082561fad5565ab7457c995:5756af02f8923b1e8767ff375bafb4aa,98d64cf45a5c573bb2748de1b5705d82:77f9ddfe39ed4b3a2c553522b969ec70,c18ddd5e92535dbe966277225da678db:e726b549ff5b0a3de8d462f383cd3380,79ab966495a759bd88fce5cf42fdb1e3:77c116373217d070073ae128bc43aea3,d17006fd6b205e7b8847df113bb213ae:0e20bb3c253b5659a0faae19b102555f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/*~hmac=2a582d377c1ed231751dabaf31874ff3827cfa94ba24becba23c43bb210f3906
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/*~hmac=2a582d377c1ed231751dabaf31874ff3827cfa94ba24becba23c43bb210f3906"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_1_Tamil_BTS/WDVLive/*~hmac=2a582d377c1ed231751dabaf31874ff3827cfa94ba24becba23c43bb210f3906&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports Select 2" tvg-logo="https://img.media.jio.com/tvpimages/98/48/301989_1749664474734_p.jpg" group-title="Sports", Star Sports Select 2
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=2b307daa57a75698acfac3e85d4ff8c5:d3c17909d061caa2e0a9c75e195ae3d4,22531ef1b5805a5cb5afc03f33468fac:3dfc474db4086f173d342f20cdc8e04c,0fde10db386c58aaa92f5932092f0039:db555aaa769ad60c24dc1ef9005a056b,2af0f67137b15f5aabcaa3f2661ba59d:f3a33dbc38942246a89ff471d0062c66,c18272dca01056fb83d6ef0cfd0a93ad:52b8bf00ebe06d6e7cdcd221faed846a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_2_BTS/WDVLive/*~hmac=a1fdd206abc305fc6adc93a4355355357f8050325170a24ff38d39b8f00abfe9
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_2_BTS/WDVLive/*~hmac=a1fdd206abc305fc6adc93a4355355357f8050325170a24ff38d39b8f00abfe9"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_2_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_Select_2_BTS/WDVLive/*~hmac=a1fdd206abc305fc6adc93a4355355357f8050325170a24ff38d39b8f00abfe9&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2" tvg-logo="https://img.media.jio.com/tvpimages/11/30/302116_1753202248468_l_medium.jpg" group-title="Sports", Star Sports 2
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=d49703e538a2545fa2d8d57f34a4d0ba:9ef07c1b359ee137db0493cebd6acdf0,5c18774a8dd85b8c82d42294f16b21b7:9bf764fb4a6e8b557b7829689ee06391,988ab009ff1853bf9c1aec3f8fa24cf0:168f946b9af57ac8965755958f2337cd,2b81174684c8579fac72c577e1143224:976fcc489b4cb178a6fc103577c4aabc,5600b8266e5e570ca150a725cf746992:804fcc4a1693490d546bcfeddb1438f5
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_2_BTS/WDVLive/*~hmac=8d5b4051917af813339fb94f735de5cbcf1079cdfb9b116bbbfa4227144e87de
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_2_BTS/WDVLive/*~hmac=8d5b4051917af813339fb94f735de5cbcf1079cdfb9b116bbbfa4227144e87de"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_2_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_2_BTS/WDVLive/*~hmac=8d5b4051917af813339fb94f735de5cbcf1079cdfb9b116bbbfa4227144e87de&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1" tvg-logo="https://img.media.jio.com/tvpimages/56/92/302115_1753188404703_l_medium.jpg" group-title="Sports", Star Sports 1
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a415207154a154e59d6259f6c436937c:56c3bac0e95368220dd75b6312a4458c,252c89dc4b3956a0bb92063740fdbf0a:7f78443c0d44e2ee4e1113bba3a32e25,dbd441bf8f33570f8b99188e6f0dace5:4b7bfd46ffe9c4787baf7693c4802fb7,741063a722dc579bbc83cf968442f0e7:be83cc5e2f9cd5b58620e8ffb1ad00ce,66b9f83c28505d10b0b7154624b91258:eef259ed3a060abbf9dd0bffcd917f35
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_BTS/WDVLive/*~hmac=5ab8eed968ab791559d6fc98044dc870d532b1f710538760f8ab4abc33213c0f
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_BTS/WDVLive/*~hmac=5ab8eed968ab791559d6fc98044dc870d532b1f710538760f8ab4abc33213c0f"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_BTS/WDVLive/*~hmac=5ab8eed968ab791559d6fc98044dc870d532b1f710538760f8ab4abc33213c0f&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Eurosport" tvg-logo="https://img.media.jio.com/tvpimages/31/7/301589_1764577915153_l_medium.jpg" group-title="Sports", Eurosport
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=389e35e8b5db5726bc56f83a9365c6ab:0a6f766f37e7c3622a4860b3c15bbc9b,6823d5fbee1857b9b5dac4e935efb6c3:72e801119a9684d336727fd4d6c1ba22,7f52f53d78805b2bb75b0f860095eb55:52f2472cff2a62100bda7d59b9db736a,7a610c65ec395eb5bd5014e6425575a9:8baa043ee2857121deff3f82874e5209,ea93cd3229e5595e9e115fce0dc576b7:cb7d0cd8b134c1b15ad9eb4a6261d9c0
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=9740504259cc39f7ae295359ce9345c9df04722b29db7a906baed321f489ac24
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=9740504259cc39f7ae295359ce9345c9df04722b29db7a906baed321f489ac24"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Dsports_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738625~exp=1791760225~acl=/bpk-tv/Dsports_BTS/WDVLive/*~hmac=9740504259cc39f7ae295359ce9345c9df04722b29db7a906baed321f489ac24&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 3" tvg-logo="https://img.media.jio.com/tvpimages/78/2/302117_1753186009563_l_medium.jpg" group-title="Sports", Star Sports 3
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e4e8647eaf0659ac85f61765a5abcdc1:d749c9b27505beb0e1a8b6c30ca65de6,c1f2dcd67cf78cbb23f5db87dae7ff8e:6d93d8d36ec5dba433f2ac9f4ad84f73,2f040ce640ee57978b0bcbb975af00a4:88c56371262c1bbe843c174720bbdeb0,c19b6cfea03150bea01a255345e5d706:8ed757eda2b740285bcf9a8fd9263422,6fbf6696ff3051768337a34da0e66eb8:cae19d171fb1015a89342e5185f8cc7b,2904732c8fb55ebeaa0f59316916997e:f69101b71872dcfb2cd1987d3773c0f4,82dc7db814ef5165a3d93102f40a5142:f767c4c59fbfeaf84754994a0ed5dc8b,b4eef24e5fafdca6a76b1c2a086689e3:9ebaf5fb8f5ebd1bf096c99c2c9a43ce,e7e5e06197b95dafc19068a8a5b70049:512aed219e9bc8431b7cb2afd7550c4d,4b15b7607d7179b8d0d9f2fa4460857b:eca33d9cc5a32fd438bde8d3ba064caf
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_3_BTS/WDVLive/*~hmac=2ed7d1cb19f0f5aa476697912a56cb219f739a78708729498c5441af8a4feae7
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_3_BTS/WDVLive/*~hmac=2ed7d1cb19f0f5aa476697912a56cb219f739a78708729498c5441af8a4feae7"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_3_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_3_BTS/WDVLive/*~hmac=2ed7d1cb19f0f5aa476697912a56cb219f739a78708729498c5441af8a4feae7&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/26/67/302119_1753202587563_l_medium.jpg" group-title="Sports", Star Sports 1 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=8aa7da0842d65285b23e55619ab0f1c6:fe01e21ac81a19049d3ba82b5bd33dfa,c60582b4c0b95e80ae34b1a462f07990:9967dfba4b60a86a9c7a4c861d26337c,4705b7d90af65fa28d3e6b7c9e6b7fc2:bfdbe4ecb7f1496978eb9d022a6640b5,e3321b5b1af253f39010ce4bf6085da6:8eb2ab45a84ca9eef4630d6a2ce9f786,50eaa3fe2ce95eb4908495ea19e3b6d5:a4a789b10f04c39070d64ef131b59911
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/*~hmac=6663309a50095100ad1d3109f3706846573f7fe4b325f68a698b304720571e60
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/*~hmac=6663309a50095100ad1d3109f3706846573f7fe4b325f68a698b304720571e60"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_BTS/WDVLive/*~hmac=6663309a50095100ad1d3109f3706846573f7fe4b325f68a698b304720571e60&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/13/91/302118_1753188135280_l_medium.jpg" group-title="Sports", Star Sports 1 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=bab01ce8ab695712a5fe390f1b4ffca2:5685130a845e217080b84fc170b67f00,5934f65f151c581d9dc75e181c7a406e:ffd92e68261477c61df082b64e648cbf,ec959436fe455d3a8af330c4c93e86cc:56f3b61ef52fb050671036bf50a109dd,fb2bb07aecbf5bc2b807b2dced129cda:80d4747512a52bb7c64c8f60813a0f8f,ae3f86f454395a8ab0793c8a619aa1f6:6034309b589445745ec1c00fd6161a4a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/*~hmac=593a055abbe6a8b08e9c39f88a3fbfb55e5bf74fee1abfe8a3ff90587e5de829
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/*~hmac=593a055abbe6a8b08e9c39f88a3fbfb55e5bf74fee1abfe8a3ff90587e5de829"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Kannada_BTS/WDVLive/*~hmac=593a055abbe6a8b08e9c39f88a3fbfb55e5bf74fee1abfe8a3ff90587e5de829&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/69/98/301739_1758886924465_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b14cdf86a8e8561bb68f98bf608d14f1:fe95ef259d5202e5471b8804c5323b8a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738626~exp=1791760226~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=68164f1a92edf3f297acf9b623ddaf111054bb1b87bb04cdf2b9ff29428c8946
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738626~exp=1791760226~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=68164f1a92edf3f297acf9b623ddaf111054bb1b87bb04cdf2b9ff29428c8946"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738626~exp=1791760226~acl=/bpk-tv/SonySportsTen4TelSTB_BTS/WDVLive/*~hmac=68164f1a92edf3f297acf9b623ddaf111054bb1b87bb04cdf2b9ff29428c8946&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Hindi" tvg-logo="https://img.media.jio.com/tvpimages/57/17/302124_1760690003311_l_medium.jpg" group-title="Sports", Star Sports 2 Hindi
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=f9af22f6d76f5f5d98a35eed7fc35292:b979fbf840b5c984733d28aaf683762e,d20fa4f491ff5e74a4b5b149caed397b:913de95cba91eadbeee9784882185f70,f52d8e6b43c95ee7b5d1092c127049bc:8b6f00199715e5e736fc970e2e887709,b4ae2599a33f5cbc8eff216f82284cef:98cc66f56da9a4e8163f5f831f03cd43,602ce22ddc995d4495d10c1adeeb8e8c:be784f89f9513333c7621900c7a1d89b
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/StarSports2Hin_BTS/WDVLive/*~hmac=575c6c40e6d022adabf46b9c4f09b0bf39737e750f8e214456b57e135cf2baa9
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/StarSports2Hin_BTS/WDVLive/*~hmac=575c6c40e6d022adabf46b9c4f09b0bf39737e750f8e214456b57e135cf2baa9"}
https://jiotvpllive.cdn.jio.com/bpk-tv/StarSports2Hin_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/StarSports2Hin_BTS/WDVLive/*~hmac=575c6c40e6d022adabf46b9c4f09b0bf39737e750f8e214456b57e135cf2baa9&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/56/36/301229_1760688472627_l_medium.jpg" group-title="Sports", Star Sports 2 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=5b50f11a09a15275b5742ef2d6e001e0:9b9ce35c8d91340939031aba5e433991,22e4de22a39b5ce78425c739c4941e7b:b4134f4a41cc0d214ba909cc4ebdb0ba,17a32401741c5072bd2f550f72be7a42:0ddcf0f656f52008a2276a85adbcf42e,0c1a4c4f4a60538a8841ad67b5df3488:939f425e0ef782f16cc9db4b84b47195,8e48435ba038548299ee73fef998535d:b89d18e54ad2b5576c27c3259db19a3e
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsHD2Hin_BTS/WDVLive/*~hmac=7119a831a2b682404f0e9b5b4cbfc79125287e40134383f5c6caf45012316014
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsHD2Hin_BTS/WDVLive/*~hmac=7119a831a2b682404f0e9b5b4cbfc79125287e40134383f5c6caf45012316014"}
https://jiotvpllive.cdn.jio.com/bpk-tv/StarSportsHD2Hin_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsHD2Hin_BTS/WDVLive/*~hmac=7119a831a2b682404f0e9b5b4cbfc79125287e40134383f5c6caf45012316014&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports Khel" tvg-logo="https://img.media.jio.com/tvpimages/88/40/301246_1760689454012_l_medium.jpg" group-title="Sports", Star Sports Khel
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=906cc35528ca58348e7fed58f8796687:3a1eaff9da6a0083567fb1ea1eaa21f5,1dfe1c29b8f15ab3a158bb27eb5b8d3f:432ff00824d61ff1da1348cc53e0968c,942646c32cde5325a7c33b137cf7d46e:ebb1edf4d1ca69738d8cf378a00ad35d,2898824f2440509698d04f0109f1d285:58e2fbc4e5ae22f973c21c402340debc,1eb81f72a86d56c3bdfe905c2e987d1d:4720e2ac970828d2befb2cf838d486fb
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsKhel_BTS/WDVLive/*~hmac=3675a2ec85909722a04abaf3cd991e9031b321e3b30e2f24a4f157491a7cd213
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsKhel_BTS/WDVLive/*~hmac=3675a2ec85909722a04abaf3cd991e9031b321e3b30e2f24a4f157491a7cd213"}
https://jiotvpllive.cdn.jio.com/bpk-tv/StarSportsKhel_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738623~exp=1791760223~acl=/bpk-tv/StarSportsKhel_BTS/WDVLive/*~hmac=3675a2ec85909722a04abaf3cd991e9031b321e3b30e2f24a4f157491a7cd213&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Telugu" tvg-logo="https://img.media.jio.com/tvpimages/63/39/304029_1759237887443_l_medium.jpg" group-title="Sports", Star Sports 2 Telugu
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e85ffc940cfc5c5790a24b714acef1cd:fda416daac7608bfd608ab4c5b724a84,5387c6a9f68a5cecbe1238194db9a82a:14345a3dab1d1bf5e64570b5aa377883,a83960ba4fc854e88c3bcd7f16f08f27:56218453c4619b01c1a0913779234faf,a64b68eea65c5881a0fc5742bd389806:9b446fde7106eb1b7821c5cf251df932,4d3a6feedae059fb8f92f5bcba25128d:46c14f188789753b7befc2707829cb6a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Telugu_BTS/WDVLive/*~hmac=2403572780d4a9c3fec87f5f8ceb8b2aee3317c3b4e33376c2442664c914f1bb
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Telugu_BTS/WDVLive/*~hmac=2403572780d4a9c3fec87f5f8ceb8b2aee3317c3b4e33376c2442664c914f1bb"}
https://jiotvpllive.cdn.jio.com/bpk-tv/StarSports2Telugu_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Telugu_BTS/WDVLive/*~hmac=2403572780d4a9c3fec87f5f8ceb8b2aee3317c3b4e33376c2442664c914f1bb&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/28/30/304030_1759239180896_l_medium.jpg" group-title="Sports", Star Sports 2 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=0a0ad7fdf02955d483496e5d5ad41b64:cc1d01a4ccfd47da4f5ff62e20686505,dc4c605a752d535e8c0e31b9a5deae16:21cb8bd7a0606e2dd9fb190ff719f126,a9f12248bf2a5da99366dd6ce07ff0e5:e87623a2552185d5fbe2f227354c2b15,bfb4dda563535486a380c44fecc92015:27bbe04fb5447de6f3286e1030c7b179,b9fae8a03b2750a4b0db12f0f6b65f15:3b1ab225fb8e08c9b59cfd6ededaddc8
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Tamil_BTS/WDVLive/*~hmac=c7081b5167bc10e95b5755f6148a89fa144c9dd3267fbd21511aed52d71824ad
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Tamil_BTS/WDVLive/*~hmac=c7081b5167bc10e95b5755f6148a89fa144c9dd3267fbd21511aed52d71824ad"}
https://jiotvpllive.cdn.jio.com/bpk-tv/StarSports2Tamil_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738629~exp=1791760229~acl=/bpk-tv/StarSports2Tamil_BTS/WDVLive/*~hmac=c7081b5167bc10e95b5755f6148a89fa144c9dd3267fbd21511aed52d71824ad&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Tamil HD" tvg-logo="https://img.media.jio.com/tvpimages/50/43/302120_1753202206969_l_medium.jpg" group-title="Sports", Star Sports 2 Tamil HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=bd9dfd385dbc56a99a048f88d3828571:eecf613dad0b8250ffaddc7ba86be68b,75ed4a2f9c96531fbdc16458ec75e24d:d61e00cf48a772526e85dccbdfc166ae,6c41267ed8725915bee0695dd5e76d40:4e2ba836b17c916e7368b30453987f2f,a2e868d50d315372a0e7375ee90375d1:0d885213a727867407ca23037e6ffe53,92cfd893ab8551d59a773afce91fc790:906e0d6c4accf6bdaad9a6167f8ae53a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/*~hmac=f48d463266022046ef6f7b30611291b8d8ca2c256240c898c8a096451bfd496a
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/*~hmac=f48d463266022046ef6f7b30611291b8d8ca2c256240c898c8a096451bfd496a"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Tamil_HD_BTS/WDVLive/*~hmac=f48d463266022046ef6f7b30611291b8d8ca2c256240c898c8a096451bfd496a&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Tamil HD" tvg-logo="https://img.media.jio.com/tvpimages/19/19/302122_1753202506698_l_medium.jpg" group-title="Sports", Star Sports 1 Tamil HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=1e9099af7f025ad6b3fd363bb2db8faf:c45a762695c40f6675b8b56ac50e97a5,8d89858c927e55a2b03faaa98fca9eb2:f74858f329157adebdbc5556d0417f47,1e6246cad111577f90bd2082378b7ed2:976e4e4f92d10b8d1702559a9babb5e0,2487a38f900d5fe1ba98eed635d96c6e:58e2099b555b7ea59dee0b957b694cea,439d31fa05525a99b856e852d035023b:83728b98188b1fa8bf69509e1796fb27
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/*~hmac=d81f23460d8815f1952ffab33c61fea6d31d9c792414b1b3bb1275b0e8fb6408
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/*~hmac=d81f23460d8815f1952ffab33c61fea6d31d9c792414b1b3bb1275b0e8fb6408"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Tamil_HD_BTS/WDVLive/*~hmac=d81f23460d8815f1952ffab33c61fea6d31d9c792414b1b3bb1275b0e8fb6408&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 2 Telugu HD" tvg-logo="https://img.media.jio.com/tvpimages/84/67/302121_1753185947631_l_medium.jpg" group-title="Sports", Star Sports 2 Telugu HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=5515e7889d225404939f53765feba2ea:56fa91e2ee09e5a989c630cb3b2df13d,22da87a3906750e78c0e770cc4b88c7e:d25db631bbcdb027d9a3fdddf5ddeba8,86180ee8e6b05175adddc5eb66b01faf:eaaf6aec5ea8cedeb6d248f2e96d6b93,7635f19465ac599197cdf889d9ad9739:068980c25dd4fe33631fe07a35c1289f,01fc3874f21058dcbf8204f07616ce81:8ba560fb1fcdfeaddfc254965e3a42f6
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/*~hmac=8018a08c261cd2d1048a6a965c0d91372a6af63656a8823c134ce781c590938f
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/*~hmac=8018a08c261cd2d1048a6a965c0d91372a6af63656a8823c134ce781c590938f"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports2_Telugu_HD_BTS/WDVLive/*~hmac=8018a08c261cd2d1048a6a965c0d91372a6af63656a8823c134ce781c590938f&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Star Sports 1 Telugu HD" tvg-logo="https://img.media.jio.com/tvpimages/19/57/302123_1753189675445_l_medium.jpg" group-title="Sports", Star Sports 1 Telugu HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a56a509011675303900ead16165ba38f:c4c4c7aec0ff4a3fa0c6d8cb1ae56c30,45bfaa5f2e8c5723a8da41a5c9cd38c1:672fa31cd32888671db2daf259479d49,2130ba84a11d58cb8cf1fae897c241cf:f812838e18377db5fe72277934548d83,c7ecbd81d476502b9f25e08bfa07b842:52e369879fd23e08a6158c8c1224c4dd,a2bcadea362c563d9a9f68dfb2557087:5f4370e02e5eea8c9771d36a27a14afb
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/*~hmac=2c61ff0ce6756c1ddac5fdaa7ddf6168e0f6f9b4504d55a0a6a1fe1cb4f219a4
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/*~hmac=2c61ff0ce6756c1ddac5fdaa7ddf6168e0f6f9b4504d55a0a6a1fe1cb4f219a4"}
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Telugu_HD_BTS/WDVLive/*~hmac=2c61ff0ce6756c1ddac5fdaa7ddf6168e0f6f9b4504d55a0a6a1fe1cb4f219a4&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Tamil" tvg-logo="https://img.media.jio.com/tvpimages/21/10/304240_1787558465564_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Tamil
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=fa9ae315a29652db96c335e993b8c4bf:8ac0bc132a1c277052d8b023392e1685
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=835fa4a4fc6dfc6b6a8b977eddd3b5bdb77e176213b6800acc793375147f4031
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=835fa4a4fc6dfc6b6a8b977eddd3b5bdb77e176213b6800acc793375147f4031"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonySportsTen4TamSTB_BTS/WDVLive/*~hmac=835fa4a4fc6dfc6b6a8b977eddd3b5bdb77e176213b6800acc793375147f4031&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro

#EXTINF:-1 tvg-name="Sony Sports Ten 4 Kannada" tvg-logo="https://img.media.jio.com/tvpimages/88/41/304241_1787558888185_l_medium.jpg" group-title="Sports", Sony Sports Ten 4 Kannada
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=b4708636e982cff03c9e966ca732939b5ee5d6fe52691b8a86bf589c74f6eae9
#EXTHTTP:{"Origin":"https://www.jiotv.com","Referer":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=b4708636e982cff03c9e966ca732939b5ee5d6fe52691b8a86bf589c74f6eae9"}
https://jiotvpllive.cdn.jio.com/bpk-tv/SonyTen4Kan_BTS/WDVLive/index.mpd?|cookie=__hdnea__=st=1791738631~exp=1791760231~acl=/bpk-tv/SonyTen4Kan_BTS/WDVLive/*~hmac=b4708636e982cff03c9e966ca732939b5ee5d6fe52691b8a86bf589c74f6eae9&referer=https://www.jiotv.com/&origin=https://www.jiotv.com&user-agent=@shoebbro"""

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
