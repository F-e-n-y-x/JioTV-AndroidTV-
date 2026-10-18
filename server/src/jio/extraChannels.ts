import fs from "node:fs";
import path from "node:path";
import { config } from "../config";
import type { Channel } from "./channels";

export interface ExtraChannel {
  id: string;
  name: string;
  logoUrl: string;
  group: string;
  channelNumber: number;
  streamUrl: string;
  cookie?: string;
  userAgent?: string;
  referer?: string;
  origin?: string;
  licenseKey?: string;
  isMpd: boolean;
  language: string;
  stbNumber?: number;
}

interface ChannelMeta {
  channelId: number;
  cleanName: string;
  group: string;
  language: string;
  stbNumber?: number;
  logoUrl?: string;
}

const ZEE_M3U_URL = "https://raw.githubusercontent.com/rathee-ashish/zee-playlist/main/zee.m3u";
const SONY_M3U_URL = "https://vk-playlist.pages.dev/sony.m3u";
const LIVE_COOKIE_URL = "https://allinonereborn2.online/jtv-fetch/jstarcookie/cookie.json";

const CACHE_FILE_NAME = "extra_channels_cache.json";
const COOKIE_CACHE_FILE_NAME = "extra_cookies_cache.json";

const CHANNEL_METADATA: Record<string, ChannelMeta> = {
  // Zee Channels
  zeetvhd: { channelId: 167, cleanName: "Zee TV HD", group: "Entertainment", language: "Hindi", stbNumber: 104 },
  zeecinemahd: { channelId: 165, cleanName: "Zee Cinema HD", group: "Movies", language: "Hindi", stbNumber: 204 },
  zeetalkies: { channelId: 153, cleanName: "Zee Talkies", group: "Movies", language: "Marathi", stbNumber: 853 },
  andpictureshd: { channelId: 185, cleanName: "And Pictures HD", group: "Movies", language: "Hindi", stbNumber: 208 },
  andpictures: { channelId: 1839, cleanName: "And Pictures", group: "Movies", language: "Hindi", stbNumber: 207 },
  zeeyuva: { channelId: 414, cleanName: "Zee Yuva", group: "Entertainment", language: "Marathi", stbNumber: 858 },
  zee24taas: { channelId: 442, cleanName: "Zee 24 Taas", group: "News", language: "Marathi", stbNumber: 9005 },
  zeemarathi: { channelId: 445, cleanName: "Zee Marathi", group: "Entertainment", language: "Marathi", stbNumber: 851 },
  andtvhd: { channelId: 472, cleanName: "And TV HD", group: "Entertainment", language: "Hindi", stbNumber: 110 },
  zeecinema: { channelId: 484, cleanName: "Zee Cinema", group: "Movies", language: "Hindi", stbNumber: 203 },
  zeeaction: { channelId: 488, cleanName: "Zee Action", group: "Movies", language: "Hindi", stbNumber: 213 },
  zeebollywood: { channelId: 487, cleanName: "Zee Bollywood", group: "Movies", language: "Hindi", stbNumber: 211 },
  zeenews: { channelId: 504, cleanName: "Zee News", group: "News", language: "Hindi", stbNumber: 1007 },
  zeeuttarpradeshuttarakhand: { channelId: 572, cleanName: "Zee UP UK", group: "News", language: "Hindi", stbNumber: 1020 },
  zeeupuk: { channelId: 572, cleanName: "Zee UP UK", group: "News", language: "Hindi", stbNumber: 1020 },
  zing: { channelId: 585, cleanName: "Zing", group: "Music", language: "Hindi", stbNumber: 453 },
  zeebharat: { channelId: 652, cleanName: "Zee Bharat", group: "News", language: "Hindi", stbNumber: 1022 },
  zeepunjabharyanahimachalpradesh: { channelId: 654, cleanName: "Zee Punjab Haryana HP", group: "News", language: "Punjabi", stbNumber: 9029 },
  zeepunjabharyanahp: { channelId: 654, cleanName: "Zee Punjab Haryana HP", group: "News", language: "Punjabi", stbNumber: 9029 },
  zeemadhyapradeshchattisgarh: { channelId: 658, cleanName: "Zee MP Chattisgarh", group: "News", language: "Hindi", stbNumber: 1017 },
  zeempchattisgarh: { channelId: 658, cleanName: "Zee MP Chattisgarh", group: "News", language: "Hindi", stbNumber: 1017 },
  zeebusiness: { channelId: 657, cleanName: "Zee Business", group: "Business News", language: "Hindi", stbNumber: 1011 },
  zeebiharjharkhand: { channelId: 661, cleanName: "Zee Bihar Jharkhand", group: "News", language: "Bhojpuri", stbNumber: 9015 },
  zeerajasthannews: { channelId: 659, cleanName: "Zee Rajasthan", group: "News", language: "Hindi", stbNumber: 1019 },
  zeerajasthan: { channelId: 659, cleanName: "Zee Rajasthan", group: "News", language: "Hindi", stbNumber: 1019 },
  zeedelhincrharyana: { channelId: 724, cleanName: "Zee Delhi NCR Haryana", group: "News", language: "Hindi", stbNumber: 1051 },
  zeetalkieshd: { channelId: 1358, cleanName: "Zee Talkies HD", group: "Movies", language: "Marathi", stbNumber: 854 },
  zeemarathihd: { channelId: 1360, cleanName: "Zee Marathi HD", group: "Entertainment", language: "Marathi", stbNumber: 852 },
  zeetv: { channelId: 1351, cleanName: "Zee TV", group: "Entertainment", language: "Hindi", stbNumber: 103 },
  zeeclassic: { channelId: 1691, cleanName: "Zee Classic", group: "Movies", language: "Hindi", stbNumber: 217 },
  zeebiskope: { channelId: 1745, cleanName: "Zee Biskope", group: "Movies", language: "Bhojpuri", stbNumber: 878 },
  zeepowerhd: { channelId: 1746, cleanName: "Zee Power HD", group: "Movies", language: "Kannada" },
  zeepunjabi: { channelId: 1751, cleanName: "Zee Punjabi", group: "Entertainment", language: "Punjabi" },
  zeezesthd: { channelId: 2757, cleanName: "Zee Zest HD", group: "Lifestyle", language: "English" },
  zeethirai: { channelId: 2256, cleanName: "Zee Thirai", group: "Movies", language: "Tamil", stbNumber: 922 },
  zeethiraihd: { channelId: 3051, cleanName: "Zee Thirai HD", group: "Movies", language: "Tamil", stbNumber: 923 },

  // Star Sports Channels
  starsportsselect1hd: { channelId: 460, cleanName: "Star Sports Select 1 HD", group: "Sports", language: "English", stbNumber: 410 },
  starsportsselect1: { channelId: 1123, cleanName: "Star Sports Select 1", group: "Sports", language: "English", stbNumber: 409 },
  starsportsselect2hd: { channelId: 461, cleanName: "Star Sports Select 2 HD", group: "Sports", language: "English", stbNumber: 412 },
  starsportsselect2: { channelId: 1114, cleanName: "Star Sports Select 2", group: "Sports", language: "English", stbNumber: 411 },
  starsports1hd: { channelId: 1106, cleanName: "Star Sports 1 HD", group: "Sports", language: "English", stbNumber: 402 },
  starsports1: { channelId: 1142, cleanName: "Star Sports 1", group: "Sports", language: "English", stbNumber: 401 },
  starsports1hindihd: { channelId: 1108, cleanName: "Star Sports 1 Hindi HD", group: "Sports", language: "Hindi", stbNumber: 404 },
  starsports1hindi: { channelId: 362, cleanName: "Star Sports 1 Hindi", group: "Sports", language: "Hindi", stbNumber: 403 },
  starsports2hd: { channelId: 1109, cleanName: "Star Sports 2 HD", group: "Sports", language: "English", stbNumber: 406 },
  starsports2: { channelId: 1141, cleanName: "Star Sports 2", group: "Sports", language: "English", stbNumber: 405 },
  starsports2hindihd: { channelId: 1984, cleanName: "Star Sports 2 Hindi HD", group: "Sports", language: "Hindi" },
  starsports2hindi: { channelId: 1985, cleanName: "Star Sports 2 Hindi", group: "Sports", language: "Hindi" },
  starsports3: { channelId: 1389, cleanName: "Star Sports 3", group: "Sports", language: "Hindi", stbNumber: 407 },
  starsportskhel: { channelId: 1998, cleanName: "Star Sports Khel", group: "Sports", language: "Hindi" },
  starsports1tamilhd: { channelId: 3277, cleanName: "Star Sports 1 Tamil HD", group: "Sports", language: "Tamil" },
  starsports1tamil: { channelId: 1124, cleanName: "Star Sports 1 Tamil", group: "Sports", language: "Tamil", stbNumber: 413 },
  starsports1teluguhd: { channelId: 3278, cleanName: "Star Sports 1 Telugu HD", group: "Sports", language: "Telugu" },
  starsports1telugu: { channelId: 1651, cleanName: "Star Sports 1 Telugu", group: "Sports", language: "Telugu", stbNumber: 414 },
  starsports1kannada: { channelId: 1650, cleanName: "Star Sports 1 Kannada", group: "Sports", language: "Kannada", stbNumber: 415 },
  starsports2kannada: { channelId: 1122, cleanName: "Star Sports 2 Kannada", group: "Sports", language: "Kannada" },
  starsports2tamilhd: { channelId: 3273, cleanName: "Star Sports 2 Tamil HD", group: "Sports", language: "Tamil" },
  starsports2tamil: { channelId: 2853, cleanName: "Star Sports 2 Tamil", group: "Sports", language: "Tamil" },
  starsports2teluguhd: { channelId: 3274, cleanName: "Star Sports 2 Telugu HD", group: "Sports", language: "Telugu" },
  starsports2telugu: { channelId: 2852, cleanName: "Star Sports 2 Telugu", group: "Sports", language: "Telugu" },

  // Sony Sports Channels
  sonysportsten1hd: { channelId: 162, cleanName: "Sony Sports Ten 1 HD", group: "Sports", language: "English", stbNumber: 422, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_HD.png" },
  sonyten1hd: { channelId: 162, cleanName: "Sony Sports Ten 1 HD", group: "Sports", language: "English", stbNumber: 422, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_HD.png" },
  ten1hd: { channelId: 162, cleanName: "Sony Sports Ten 1 HD", group: "Sports", language: "English", stbNumber: 422, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_HD.png" },

  sonysportsten1: { channelId: 514, cleanName: "Sony Sports Ten 1", group: "Sports", language: "English", stbNumber: 421, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_1.png" },
  sonyten1: { channelId: 514, cleanName: "Sony Sports Ten 1", group: "Sports", language: "English", stbNumber: 421, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_1.png" },
  ten1sd: { channelId: 514, cleanName: "Sony Sports Ten 1", group: "Sports", language: "English", stbNumber: 421, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_1.png" },
  ten1: { channelId: 514, cleanName: "Sony Sports Ten 1", group: "Sports", language: "English", stbNumber: 421, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_1.png" },

  sonysportsten2hd: { channelId: 891, cleanName: "Sony Sports Ten 2 HD", group: "Sports", language: "English", stbNumber: 424, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten2_HD.png" },
  sonyten2hd: { channelId: 891, cleanName: "Sony Sports Ten 2 HD", group: "Sports", language: "English", stbNumber: 424, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten2_HD.png" },
  ten2hd: { channelId: 891, cleanName: "Sony Sports Ten 2 HD", group: "Sports", language: "English", stbNumber: 424, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten2_HD.png" },

  sonysportsten2: { channelId: 523, cleanName: "Sony Sports Ten 2", group: "Sports", language: "English", stbNumber: 423, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_2.png" },
  sonyten2: { channelId: 523, cleanName: "Sony Sports Ten 2", group: "Sports", language: "English", stbNumber: 423, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_2.png" },
  ten2sd: { channelId: 523, cleanName: "Sony Sports Ten 2", group: "Sports", language: "English", stbNumber: 423, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_2.png" },
  ten2: { channelId: 523, cleanName: "Sony Sports Ten 2", group: "Sports", language: "English", stbNumber: 423, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_2.png" },

  sonysportsten3hd: { channelId: 892, cleanName: "Sony Sports Ten 3 HD", group: "Sports", language: "Hindi", stbNumber: 426, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten3_HD.png" },
  sonyten3hd: { channelId: 892, cleanName: "Sony Sports Ten 3 HD", group: "Sports", language: "Hindi", stbNumber: 426, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten3_HD.png" },
  ten3hd: { channelId: 892, cleanName: "Sony Sports Ten 3 HD", group: "Sports", language: "Hindi", stbNumber: 426, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten3_HD.png" },

  sonysportsten3: { channelId: 524, cleanName: "Sony Sports Ten 3", group: "Sports", language: "Hindi", stbNumber: 425, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_3.png" },
  sonyten3: { channelId: 524, cleanName: "Sony Sports Ten 3", group: "Sports", language: "Hindi", stbNumber: 425, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_3.png" },
  ten3sd: { channelId: 524, cleanName: "Sony Sports Ten 3", group: "Sports", language: "Hindi", stbNumber: 425, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_3.png" },
  ten3: { channelId: 524, cleanName: "Sony Sports Ten 3", group: "Sports", language: "Hindi", stbNumber: 425, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_3.png" },

  sonysportsten4hd: { channelId: 1772, cleanName: "Sony Sports Ten 4 HD", group: "Sports", language: "Tamil", stbNumber: 428, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },
  sonyten4hd: { channelId: 1772, cleanName: "Sony Sports Ten 4 HD", group: "Sports", language: "Tamil", stbNumber: 428, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },
  ten4hd: { channelId: 1772, cleanName: "Sony Sports Ten 4 HD", group: "Sports", language: "Tamil", stbNumber: 428, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },

  sonysportsten4: { channelId: 1774, cleanName: "Sony Sports Ten 4", group: "Sports", language: "Tamil", stbNumber: 427, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },
  sonyten4: { channelId: 1774, cleanName: "Sony Sports Ten 4", group: "Sports", language: "Tamil", stbNumber: 427, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },
  ten4sd: { channelId: 1774, cleanName: "Sony Sports Ten 4", group: "Sports", language: "Tamil", stbNumber: 427, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },
  ten4: { channelId: 1774, cleanName: "Sony Sports Ten 4", group: "Sports", language: "Tamil", stbNumber: 427, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" },

  sonysportsten5hd: { channelId: 155, cleanName: "Sony Sports Ten 5 HD", group: "Sports", language: "English", stbNumber: 430, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Six_HD.png" },
  sonyten5hd: { channelId: 155, cleanName: "Sony Sports Ten 5 HD", group: "Sports", language: "English", stbNumber: 430, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Six_HD.png" },
  sonysixhd: { channelId: 155, cleanName: "Sony Sports Ten 5 HD", group: "Sports", language: "English", stbNumber: 430, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Six_HD.png" },
  ten5hd: { channelId: 155, cleanName: "Sony Sports Ten 5 HD", group: "Sports", language: "English", stbNumber: 430, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Six_HD.png" },

  sonysportsten5: { channelId: 525, cleanName: "Sony Sports Ten 5", group: "Sports", language: "English", stbNumber: 429, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" },
  sonyten5: { channelId: 525, cleanName: "Sony Sports Ten 5", group: "Sports", language: "English", stbNumber: 429, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" },
  sonysix: { channelId: 525, cleanName: "Sony Sports Ten 5", group: "Sports", language: "English", stbNumber: 429, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" },
  sonysixsd: { channelId: 525, cleanName: "Sony Sports Ten 5", group: "Sports", language: "English", stbNumber: 429, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" },
  ten5: { channelId: 525, cleanName: "Sony Sports Ten 5", group: "Sports", language: "English", stbNumber: 429, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" },

  ddsports: { channelId: 204, cleanName: "DD Sports", group: "Sports", language: "Hindi", stbNumber: 400, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/DD_Sports.png" },

  // Sony Entertainment & Movies
  sethd: { channelId: 291, cleanName: "SET HD", group: "Entertainment", language: "Hindi", stbNumber: 108, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_HD.png" },
  set: { channelId: 1396, cleanName: "SET", group: "Entertainment", language: "Hindi", stbNumber: 107, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SD.png" },
  setsd: { channelId: 1396, cleanName: "SET", group: "Entertainment", language: "Hindi", stbNumber: 107, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SD.png" },

  sonysabhd: { channelId: 471, cleanName: "Sony SAB HD", group: "Entertainment", language: "Hindi", stbNumber: 114, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonySAB.png" },
  sabhd: { channelId: 471, cleanName: "Sony SAB HD", group: "Entertainment", language: "Hindi", stbNumber: 114, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonySAB.png" },
  sonysab: { channelId: 154, cleanName: "Sony SAB", group: "Entertainment", language: "Hindi", stbNumber: 113, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SAB.png" },
  sab: { channelId: 154, cleanName: "Sony SAB", group: "Entertainment", language: "Hindi", stbNumber: 113, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SAB.png" },
  sabsd: { channelId: 154, cleanName: "Sony SAB", group: "Entertainment", language: "Hindi", stbNumber: 113, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SAB.png" },

  sonypal: { channelId: 474, cleanName: "Sony Pal", group: "Entertainment", language: "Hindi", stbNumber: 116, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPAL.png" },
  sonypalsd: { channelId: 474, cleanName: "Sony Pal", group: "Entertainment", language: "Hindi", stbNumber: 116, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPAL.png" },

  sonyaath: { channelId: 697, cleanName: "Sony Aath", group: "Entertainment", language: "Bengali", stbNumber: 824, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyAATH.png" },
  sonyaathsd: { channelId: 697, cleanName: "Sony Aath", group: "Entertainment", language: "Bengali", stbNumber: 824, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyAATH.png" },

  sonymarathi: { channelId: 1146, cleanName: "Sony Marathi", group: "Entertainment", language: "Marathi", stbNumber: 859, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMarathi.png" },
  sonymarathisd: { channelId: 1146, cleanName: "Sony Marathi", group: "Entertainment", language: "Marathi", stbNumber: 859, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMarathi.png" },

  sonymaxhd: { channelId: 476, cleanName: "Sony MAX HD", group: "Movies", language: "Hindi", stbNumber: 202, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX.png" },
  sonymax: { channelId: 289, cleanName: "Sony MAX", group: "Movies", language: "Hindi", stbNumber: 201, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Max_SD.png" },
  sonymaxsd: { channelId: 289, cleanName: "Sony MAX", group: "Movies", language: "Hindi", stbNumber: 201, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Max_SD.png" },
  sonymax2: { channelId: 483, cleanName: "Sony MAX 2", group: "Movies", language: "Hindi", stbNumber: 212, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX2.png" },
  max2: { channelId: 483, cleanName: "Sony MAX 2", group: "Movies", language: "Hindi", stbNumber: 212, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX2.png" },

  sonywah: { channelId: 1393, cleanName: "Sony WAH", group: "Movies", language: "Hindi", stbNumber: 214, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyWAH.png" },

  sonypixhd: { channelId: 762, cleanName: "Sony PIX HD", group: "Movies", language: "English", stbNumber: 252, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPIX.png" },
  pixhd: { channelId: 762, cleanName: "Sony PIX HD", group: "Movies", language: "English", stbNumber: 252, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPIX.png" },

  sonybbcearthhd: { channelId: 852, cleanName: "Sony BBC Earth HD", group: "Infotainment", language: "English", stbNumber: 752, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyBBCEarthEng.png" },
  bbcearthhd: { channelId: 852, cleanName: "Sony BBC Earth HD", group: "Infotainment", language: "English", stbNumber: 752, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyBBCEarthEng.png" },

  sonyyay: { channelId: 872, cleanName: "Sony Yay", group: "Kids", language: "Hindi", stbNumber: 654, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyYAYHin.png" },
  sonyyaysd: { channelId: 872, cleanName: "Sony Yay", group: "Kids", language: "Hindi", stbNumber: 654, logoUrl: "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyYAYHin.png" },
};

const EMBEDDED_SONY_M3U = `#EXTM3U

#EXTINF:-1 tvg-id="sony-ten-1-hd" tvg-name="Sony Sports Ten 1 HD" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_HD.png" tvg-language="English",Sony Sports Ten 1 HD
https://sliv.tgaadi.workers.dev/ten1hd.m3u8

#EXTINF:-1 tvg-id="sony-ten-1" tvg-name="Sony Sports Ten 1" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_1.png" tvg-language="English",Sony Sports Ten 1
https://sliv.tgaadi.workers.dev/ten1sd.m3u8

#EXTINF:-1 tvg-id="sony-ten-2-hd" tvg-name="Sony Sports Ten 2 HD" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten2_HD.png" tvg-language="English",Sony Sports Ten 2 HD
https://sliv.tgaadi.workers.dev/ten2hd.m3u8

#EXTINF:-1 tvg-id="sony-ten-2" tvg-name="Sony Sports Ten 2" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_2.png" tvg-language="English",Sony Sports Ten 2
https://sliv.tgaadi.workers.dev/ten2sd.m3u8

#EXTINF:-1 tvg-id="sony-ten-3-hd" tvg-name="Sony Sports Ten 3 HD" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten3_HD.png" tvg-language="Hindi",Sony Sports Ten 3 HD
https://sliv.tgaadi.workers.dev/ten3hd.m3u8

#EXTINF:-1 tvg-id="sony-ten-3" tvg-name="Sony Sports Ten 3" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_3.png" tvg-language="Hindi",Sony Sports Ten 3
https://sliv.tgaadi.workers.dev/ten3sd.m3u8

#EXTINF:-1 tvg-id="sony-ten-4-hd" tvg-name="Sony Sports Ten 4 HD" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" tvg-language="Tamil",Sony Sports Ten 4 HD
https://sliv.tgaadi.workers.dev/ten4hd.m3u8

#EXTINF:-1 tvg-id="sony-ten-4" tvg-name="Sony Sports Ten 4" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png" tvg-language="Tamil",Sony Sports Ten 4
https://sliv.tgaadi.workers.dev/ten4sd.m3u8

#EXTINF:-1 tvg-id="sony-six-hd" tvg-name="Sony Sports Ten 5 HD" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Six_HD.png" tvg-language="English",Sony Sports Ten 5 HD
https://sliv.tgaadi.workers.dev/sonysixhd.m3u8

#EXTINF:-1 tvg-id="sony-six" tvg-name="Sony Sports Ten 5" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Six_SD.png" tvg-language="English",Sony Sports Ten 5
https://sliv.tgaadi.workers.dev/sonysixsd.m3u8

#EXTINF:-1 tvg-id="dd-sports" tvg-name="DD Sports" group-title="Sports" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/DD_Sports.png" tvg-language="Hindi",DD Sports
https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/b17adfe543354fdd8d189b110617cddd/index.m3u8

#EXTINF:-1 tvg-id="sony-hd" tvg-name="SET HD" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_HD.png" tvg-language="Hindi",SET HD
https://sliv.tgaadi.workers.dev/sethd.m3u8

#EXTINF:-1 tvg-id="set-sd" tvg-name="SET" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SD.png" tvg-language="Hindi",SET
https://sliv.tgaadi.workers.dev/setsd.m3u8

#EXTINF:-1 tvg-id="sony-sab-hd" tvg-name="Sony SAB HD" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonySAB.png" tvg-language="Hindi",Sony SAB HD
https://sliv.tgaadi.workers.dev/sabhd.m3u8

#EXTINF:-1 tvg-id="sony-sab" tvg-name="Sony SAB" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_SAB.png" tvg-language="Hindi",Sony SAB
https://sliv.tgaadi.workers.dev/sabsd.m3u8

#EXTINF:-1 tvg-id="sony-pal" tvg-name="Sony Pal" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPAL.png" tvg-language="Hindi",Sony Pal
https://sliv.tgaadi.workers.dev/sonypalsd.m3u8

#EXTINF:-1 tvg-id="sony-aath" tvg-name="Sony Aath" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyAATH.png" tvg-language="Bengali",Sony Aath
https://sliv.tgaadi.workers.dev/sonyaathsd.m3u8

#EXTINF:-1 tvg-id="sony-yay" tvg-name="Sony Yay" group-title="Kids" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyYAYHin.png" tvg-language="Hindi",Sony Yay
https://sliv.tgaadi.workers.dev/sonyyaysd.m3u8

#EXTINF:-1 tvg-id="sony-max-hd" tvg-name="Sony MAX HD" group-title="Movies" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX.png" tvg-language="Hindi",Sony MAX HD
https://sliv.tgaadi.workers.dev/sonymaxhd.m3u8

#EXTINF:-1 tvg-id="sony-max" tvg-name="Sony MAX" group-title="Movies" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Max_SD.png" tvg-language="Hindi",Sony MAX
https://sliv.tgaadi.workers.dev/sonymaxsd.m3u8

#EXTINF:-1 tvg-id="sony-max-2" tvg-name="Sony MAX 2" group-title="Movies" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX2.png" tvg-language="Hindi",Sony MAX 2
https://sliv.tgaadi.workers.dev/max2.m3u8

#EXTINF:-1 tvg-id="sony-wah" tvg-name="Sony WAH" group-title="Movies" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyWAH.png" tvg-language="Hindi",Sony WAH
https://sliv.tgaadi.workers.dev/sonywah.m3u8

#EXTINF:-1 tvg-id="sony-pix-hd" tvg-name="Sony PIX HD" group-title="Movies" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPIX.png" tvg-language="English",Sony PIX HD
https://sliv.tgaadi.workers.dev/pixhd.m3u8

#EXTINF:-1 tvg-id="sony-bbc-earth-hd" tvg-name="Sony BBC Earth HD" group-title="Infotainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyBBCEarthEng.png" tvg-language="English",Sony BBC Earth HD
https://sliv.tgaadi.workers.dev/sonybbcearthhd.m3u8

#EXTINF:-1 tvg-id="sony-marathi" tvg-name="Sony Marathi" group-title="Entertainment" tvg-logo="https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMarathi.png" tvg-language="Marathi",Sony Marathi
https://sliv.tgaadi.workers.dev/sonymarathi.m3u8`;

const EMBEDDED_ZEE_M3U = `#EXTM3U

#EXTINF:-1 tvg-name="Zee TV HD" tvg-logo="https://img.media.jio.com/tvpimages/66/3/300378_1753869902174_l_medium.jpg" group-title="Entertainment", Zee TV HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=7b3d2290787255ac9cdaf43ae31eacce:d0edfa8ebd655926c5e2932d8c59b3d1,24a0c2ce46925d59a5d077532901d775:68c268bda3c33d677db5b77ae8104d09,ef68d6487db25f0fbb08dfd653512fde:c03242e3834dadbb79ccc1cad9afa94d,a03b708ddf985f6eb6513ac0293246a9:e2b653d57ea9684d8e5b2a035105f0af,b553a92b758259b88f48ad521f41cb54:5cb71d334e2f8600addd230370af0e8f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTVHD_BTS/WDVLive/*~hmac=461137246d43be9e977808ff511fb441bfe9aa302be22d9e886080108edc90bf
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeTVHD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Zee Cinema HD" tvg-logo="https://img.media.jio.com/tvpimages/76/6/300330_1768289569650_l_medium.jpg" group-title="Movies", Zee Cinema HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=27eb79e6c8ef594ea8508011b606bb02:1dd808f8b8e367dd9205128bc0865dee,2f73fd6fc33055f0931e6b52989a968b:67821d540d498438c3c84e13b8971f77,eaf2c59f5bad5ce293838b116585174c:a85d1ce160047f14bfb2b93a1ef23859,70df41ef3a3654d7b1cb13fbb2430480:dd83f24ebf2fb5ef5aafb075b9451d44,ae9a750f39ee523c88f71c47872029ce:ce973e45f923884cce42020da0fb6398
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeCinemaHD_BTS/WDVLive/*~hmac=7641963a5cb354e76e636474ac69d315c6227f2e63b81d1be3d8e16d40532ab6
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeCinemaHD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Zee Talkies" tvg-logo="https://img.media.jio.com/tvpimages/1/19/300351_1765975816671_l_medium.jpg" group-title="Movies", Zee Talkies
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b495c6bba74d52039b0d310ef2bc9cc2:8672293f4472f01fdf74660e2e0fd602,d017974d08965ef19ddb80da7bbd9678:14cc669c720dca403ec5566fc97dbff8,ca5d6e1f882750ec8ec4ae5072acd431:d41408529436e60492dde72f0ef493e5,15fd29096c4f52a4b546cc2558a6c1d8:a8f9efa6a718abc90da7eb6d039ab688,dca1d44892b452a7a1121842007ef3b4:c0709e63f9ed2eb851f7a325c7e65b3e
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/ZeeTalkies_BTS/WDVLive/*~hmac=225888b9717cd2bae6b9af04f4b76064a712cbebc614cedd5b0995aa844c6e49
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeTalkies_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="And Pictures HD" tvg-logo="https://img.media.jio.com/tvpimages/57/85/300328_1768298228357_l_medium.jpg" group-title="Movies",And Pictures HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=44fe94d36c805cb19068afa8afefa67d:807af52ba671f10c1127cf7901b16b0a,f46e0d24d4e15eb99dd8739315be218b:6fe0210a22a92a893c55253163151245,e043d52515415ade82356a6546fb99c6:68062e7cb45e88955d64f87f110f0047,22b6b02b053d5271a874fc37b8b62f5c:b74cff91d1091c9968c61ea4c8cb8a3b,b4c6014d89505062bc35dee0ed519bed:2eac17b4204a2fa7a0f67ce63288fafe
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642019~exp=1791663619~acl=/bpk-tv/AndPicturesHD_BTS/WDVLive/*~hmac=4fa42f79a92d19fb2210d2ebca7878bc7b461a9b8cb0163e2b6812aae8dc88f3
https://jiotvpllive.cdn.jio.com/bpk-tv/AndPicturesHD_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Zee Yuva" tvg-logo="https://img.media.jio.com/tvpimages/98/27/300397_1768297472629_l_medium.jpg" group-title="Entertainment", Zee Yuva
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=435d7c3cd9535c87ad612c8fe53c0cd5:1fc01b04272f1ce5b34c000a76e3d55f,f3e22d881aa15d5abac70a5be03a7871:e726ddbd2a68d69786c9152a980e2c6e,5f07d81924f55764bc0b41dc25c8d9b3:48a98029a679e11b7298851f22a68df5,7618430c78b65af78ae375abee12bc9a:62a46a590863081ab0cebbb7e3cc3b2c,a876f23f16825ffbb0b676266e7564f1:16a8c30908b228a05123cf2ec6f97485
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/ZeeYuva_BTS/WDVLive/*~hmac=e8bc44f6ea01220f4c818e12f2481d8eedf143369d56dc6888c11cef75c818a4
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeYuva_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Zee 24 Taas" tvg-logo="https://img.media.jio.com/tvpimages/9/84/301342_1753198923876_l_medium.jpg" group-title="News", Zee 24 Taas
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=e4a7458e3e02550082e97464f0d17937:cf3a9e59be0272d4dd2d63b7d75417ed,1ade9ab20c735684acfa38dacdfcb8c5:0650c82cfb1d4b56a90ae1f095e21159,ce7c15405b49552096c7e07a0d84cb98:ee9a3b8192d3dc42aff1c04a0e5fe12a,c038f68733de51ee8b505ae27b1a9c35:c6c46c4633eb8d281955b871a77aada1,ad96961254c85ef98521840d25f3386d:2f697b8ca2d2b48c34294ee843c0d5ca
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791642020~exp=1791663620~acl=/bpk-tv/Zee_24_Taas_BTS/WDVLive/*~hmac=504b87e48ad225e3cc0b25d3a589d4e1c0fac2448dfb9206e0767518b08c9062
https://jiotvpllive.cdn.jio.com/bpk-tv/Zee_24_Taas_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Zee Thirai HD" tvg-logo="https://img.media.jio.com/tvpimages/59/93/304103_1768465079003_l_medium.jpg" group-title="Movies", Zee Thirai HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=58d0716748ee52f4a55cf7baa6e072bb:8fc185a1015f026fec82236bb0e096f4,88aaf3c74a8258ce8c75c74d259e9d95:12c0cc0b355a86ff3e942123284b94fa,7ddfa2e971cd5ebf8d5337f404ed2878:49d966969a62e77d4ff1004772988b61,c87a82b43b1e5902829e408335628cd4:7b858623533c561989358530c6ad7af7,c2d762acbea256c0bd67b92d59f69757:46c59697457af3a687426a4691393774
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791720053~exp=1791741653~acl=/bpk-tv/ZeeThiraiHD_BTS/WDVLive/*~hmac=4e83653467809f61fdbe8afb0ebf42191e45a361f3d88d8ac0adc5cf1ff4385c
https://jiotvpllive.cdn.jio.com/bpk-tv/ZeeThiraiHD_BTS/WDVLive/index.mpd`;

const EMBEDDED_SPORTS_M3U = `#EXTM3U

#EXTINF:-1 tvg-name="Star Sports 1 Hindi" tvg-logo="https://img.media.jio.com/tvpimages/13/81/302112_1753186300125_l_medium.jpg" group-title="Sports", Star Sports 1 Hindi
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=30039b6e2c545c01aa84e649875c4977:7aa610c68000fe9ed97851d40a52abff,c2f0a204c6c353428954740d05d73c3d:6543b1984330109886d2864d06096590,a9cb8b39035a5393a4450f56c35b15a2:96f3585aab24109d64b74e9f0c6a5ed3,6a28a05d0f9a5e09987e4267eab5a947:38d9de645400fe2df985177aafb6a248,187e53dccc7c50f5b43306635bc93adb:72f9a4a4ff898de730991ae465d06388
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/*~hmac=e19d995d82db073eed325dcf564941459053b26282c96d4e11714918aa76501a
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_Hindi_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports Select 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/98/79/302114_1763719749756_l_medium.jpg" group-title="Sports", Star Sports Select 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=6017d0514d425fa38f6e80ba6dcc852f:0018944b00adf2078f62386f30c68b74,635c740da1cc6d220e356a9b32388a34:0a6c7162aa9174ca4f48914924313465,26a953325a18595be75d2a9655a12afa:44d33860be354f77554aa422fd6d44dd,3db73fcd1d50da37cf7519f663d6bc07:80ba58c928ced9f69d0546529a9a1672,573b359b03bcbde109a9b239cd4297cf:6491a65fe527e0919553e1d1abd75e7a,25fbb2a932b25e9aacae947b2f873d3c:ac6a6a416b9d26be6819e80c2fe5d39f,cb4b21e0cad15e00ba3819fb91660fec:fe6480d2894746ba5799a3c2847e5589,2c443dffefb256a5b7a1364cc4af3529:1d78f35200eb5977a95de669788cf128,928d9058c0a8550fb6ba315124a76cd5:46811be73195049a23b731bd9344cad4,e5c47cf992a75f98b4e1a4d262de61e8:441f225881f816c6bedece0e86ef80e7
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/*~hmac=224ab3c06d81b2153a262d7eb7af7b5dbff10a9ae47582147f99ead21ef864ef
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_HD_2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports Select 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/96/65/302113_1753203780129_l_medium.jpg" group-title="Sports", Star Sports Select 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=8fc8499852ea5182b2a9307bd921cea1:c0ff2fbd59e26d6633f98af470c4d7b6,272c9b98274250f1b0bae9e19612275a:0fbbb12efcf16e2817b3c881847b2abf,fe38155e1cee53778db627b35bd2d20b:13d7c44c0e3fee8d9186a0491207629c,337fe34bbf2152c1bfe5ea8787072cd4:4efacd3a5af887dea393f0b957d266b6,cc04426ac28359a7b620934ddbbecb7c:0074c460557b1f21a60fa0aada550e3a
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/*~hmac=a51dca73f473287a3c77f0263bc152d9f64f6881b52d2af62efe573057f85483
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports 1 HD" tvg-logo="https://img.media.jio.com/tvpimages/76/32/301981_1749665662917_p.jpg" group-title="Sports", Star Sports 1 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=45825561f4ef516691a9e16d1d35dd1d:3951fe7fbf4b0b33e6246fc401f76797,bf95222d9be85d85a91281e796e99481:bc932276031b3e63758cf67b147c3557,f217e9fe133e5b948c323abef1e39b47:63640dc4430d14003def0c97a2dbab4d,eda3132244745c10920b01536bc8a75b:2b5f8bdba279032943a040078bb22b35,06dd6287c4b956419d15d6c8e513aeed:a82d6b850b5c281c0054fc6d55e1c6ba
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_BTS/WDVLive/*~hmac=18d97a63bea741840880077342e7d864e4d10309c480e3f50a5ec1cb0cde9412
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports 1 Hindi HD" tvg-logo="https://img.media.jio.com/tvpimages/5/6/301982_1760005838319_l_medium.jpg" group-title="Sports", Star Sports 1 Hindi HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a1ce6b9514f35174bda80ddd425bfd88:5c5e158724eced92f92ca8286fb28fd9,dd2fa604afc45e528f8a2283bf3cb628:bf01e6e8ff74797fb5215fead779e8b9,209975f6753d5de1ac26d604dace5313:1c54093eff75aeeccd30ebbc53c0e5a3,7bdccdb3dd015c37a0c3bd70675c8e4c:6f1b59376090d1f7ce33cbd264e87c36,11073b8f426e5092a2effada3bfaf721:5d1c59a8052956bd056810f260161e5f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/*~hmac=aed15d9188eb75cbc4d50346af1e9d7858ee3e1d341fdaa8779dc0263e88301d
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports 2 HD" tvg-logo="https://img.media.jio.com/tvpimages/41/51/301983_1753189521352_l_medium.jpg" group-title="Sports", Star Sports 2 HD
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=b99144f27b865d6ba1007fead82b2783:b2f10f09a771327b9bc69484d78e4595,04204c98718a52bf9e88d5e676fd7a63:7eebb539b03f674a26e84ae096320c70,33fab6f82a59590eb875f949389e03a9:26eff9e972154e624faf259ae0ee78ec,a5ad7dec861451a49029044a3b9d30bb:eff028a1ed3253b6a68b6a9cb4f1a0e4,c1ea31b9099b5030b14b67b41dc547d2:db74fb55442bc9cf7e8e9f364b3fe37f
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738627~exp=1791760227~acl=/bpk-tv/Star_Sports_HD2_BTS/WDVLive/*~hmac=f31472dfab836085c9fb85fe9bf889c38877abb8c017560d3f3479ae730c70c7
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD2_BTS/WDVLive/index.mpd

#EXTINF:-1 tvg-name="Star Sports 1" tvg-logo="https://img.media.jio.com/tvpimages/56/92/302115_1753188404703_l_medium.jpg" group-title="Sports", Star Sports 1
#KODIPROP:inputstream=inputstream.adaptive
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=a415207154a154e59d6259f6c436937c:56c3bac0e95368220dd75b6312a4458c,252c89dc4b3956a0bb92063740fdbf0a:7f78443c0d44e2ee4e1113bba3a32e25,dbd441bf8f33570f8b99188e6f0dace5:4b7bfd46ffe9c4787baf7693c4802fb7,741063a722dc579bbc83cf968442f0e7:be83cc5e2f9cd5b58620e8ffb1ad00ce,66b9f83c28505d10b0b7154624b91258:eef259ed3a060abbf9dd0bffcd917f35
#EXTVLCOPT:http-user-agent=@shoebbro
#EXTVLCOPT:http-referrer=https://www.jiotv.com/
#EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
#EXTVLCOPT:http-cookie=__hdnea__=st=1791738628~exp=1791760228~acl=/bpk-tv/Star_Sports_1_BTS/WDVLive/*~hmac=5ab8eed968ab791559d6fc98044dc870d532b1f710538760f8ab4abc33213c0f
https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_1_BTS/WDVLive/index.mpd`;

// In-memory channel cache
let extraChannelsMap: Map<string, ExtraChannel> = new Map();
let liveCookiesMap: Map<string, { streamUrl: string; cookie: string; expSec: number }> = new Map();
let lastLoadedAt = 0;
let refreshTimer: NodeJS.Timeout | null = null;
let inflightUpdate: Promise<void> | null = null;

function normalizeNameKey(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function parseM3uTag(line: string, tag: string): string {
  const p = `${tag}="`;
  const i = line.indexOf(p);
  if (i === -1) return "";
  const end = line.indexOf('"', i + p.length);
  return end !== -1 ? line.substring(i + p.length, end) : "";
}

function parseM3u(content: string): ExtraChannel[] {
  const lines = content.split(/\r?\n/);
  const out: ExtraChannel[] = [];
  let name = "";
  let logoUrl = "";
  let group = "";
  let language = "Hindi";
  let licenseKey = "";
  let userAgent = "@shoebbro";
  let referer = "https://www.jiotv.com/";
  let origin = "https://www.jiotv.com";
  let cookie = "";
  let isMpd = false;

  for (let idx = 0; idx < lines.length; idx++) {
    const rawLine = lines[idx].trim();
    if (!rawLine) continue;

    if (rawLine.startsWith("#EXTINF:")) {
      name = parseM3uTag(rawLine, "tvg-name");
      if (!name) {
        const commaIdx = rawLine.lastIndexOf(",");
        if (commaIdx !== -1) name = rawLine.substring(commaIdx + 1).trim();
      }
      logoUrl = parseM3uTag(rawLine, "tvg-logo");
      group = parseM3uTag(rawLine, "group-title");
      language = parseM3uTag(rawLine, "tvg-language") || "Hindi";
    } else if (rawLine.startsWith("#KODIPROP:inputstream.adaptive.manifest_type=")) {
      const type = rawLine.substring(rawLine.indexOf("=") + 1).trim();
      isMpd = type.toLowerCase() === "mpd";
    } else if (rawLine.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
      licenseKey = rawLine.substring(rawLine.indexOf("=") + 1).trim();
    } else if (rawLine.startsWith("#EXTVLCOPT:http-user-agent=")) {
      userAgent = rawLine.substring(rawLine.indexOf("=") + 1).trim();
    } else if (rawLine.startsWith("#EXTVLCOPT:http-referrer=")) {
      referer = rawLine.substring(rawLine.indexOf("=") + 1).trim();
    } else if (rawLine.startsWith("#EXTVLCOPT:http-extra-headers=")) {
      const headerLine = rawLine.substring(rawLine.indexOf("=") + 1).trim();
      if (headerLine.toLowerCase().startsWith("origin:")) {
        origin = headerLine.substring(headerLine.indexOf(":") + 1).trim();
      }
    } else if (rawLine.startsWith("#EXTVLCOPT:http-cookie=")) {
      cookie = rawLine.substring(rawLine.indexOf("=") + 1).trim();
    } else if (!rawLine.startsWith("#") && (rawLine.startsWith("http://") || rawLine.startsWith("https://"))) {
      let streamUrl = rawLine;
      if (streamUrl.includes("?|")) {
        const parts = streamUrl.split("?\\|");
        streamUrl = parts[0];
      }
      if (streamUrl.toLowerCase().includes(".mpd")) {
        isMpd = true;
      }

      const norm = normalizeNameKey(name);
      const meta = CHANNEL_METADATA[norm];
      const channelNumber = meta?.channelId ?? (out.length + 9000);
      const finalName = meta?.cleanName ?? name;
      const finalGroup = meta?.group ?? (group || "Other");
      const finalLanguage = meta?.language ?? (language || "Hindi");
      const finalLogo = meta?.logoUrl ?? logoUrl;
      const finalStb = meta?.stbNumber;

      out.push({
        id: String(channelNumber),
        name: finalName,
        logoUrl: finalLogo,
        group: finalGroup,
        channelNumber,
        streamUrl,
        cookie,
        userAgent,
        referer,
        origin,
        licenseKey,
        isMpd,
        language: finalLanguage,
        stbNumber: finalStb,
      });

      // reset
      name = "";
      logoUrl = "";
      group = "";
      language = "Hindi";
      licenseKey = "";
      userAgent = "@shoebbro";
      referer = "https://www.jiotv.com/";
      origin = "https://www.jiotv.com";
      cookie = "";
      isMpd = false;
    }
  }
  return out;
}

export function isTokenExpired(streamUrl?: string, cookie?: string): boolean {
  let token = "";
  if (streamUrl && streamUrl.includes("__hdnea__=")) {
    token = streamUrl.substring(streamUrl.indexOf("__hdnea__=") + 10).split("&")[0];
  } else if (cookie && cookie.includes("__hdnea__=")) {
    token = cookie.substring(cookie.indexOf("__hdnea__=") + 10).split(";")[0];
  }
  if (!token) return false;

  const m = /exp=(\d+)/.exec(token);
  if (!m) return false;
  const expSec = Number(m[1]);
  if (!Number.isFinite(expSec)) return false;

  const nowSec = Math.floor(Date.now() / 1000);
  return nowSec >= expSec - 180; // Expired or expiring within 3 minutes
}

export async function fetchLiveTokens(): Promise<number> {
  let rawJson = "";
  try {
    const res = await fetch(LIVE_COOKIE_URL, {
      headers: { "User-Agent": "Mozilla/5.0" },
      signal: AbortSignal.timeout(10_000),
    });
    if (res.ok) {
      rawJson = await res.text();
      try {
        const p = path.join(config.dataDir, COOKIE_CACHE_FILE_NAME);
        fs.writeFileSync(p, rawJson, "utf8");
      } catch {
        // ignore disk write error
      }
    }
  } catch (err) {
    // try disk cache
    try {
      const p = path.join(config.dataDir, COOKIE_CACHE_FILE_NAME);
      if (fs.existsSync(p)) rawJson = fs.readFileSync(p, "utf8");
    } catch {
      // ignore
    }
  }

  if (!rawJson) return 0;

  let count = 0;
  try {
    const root = JSON.parse(rawJson);
    const items: any[] = [];
    if (Array.isArray(root.failed_results)) items.push(...root.failed_results);
    if (Array.isArray(root.successful_results)) items.push(...root.successful_results);

    for (const item of items) {
      const cid = String(item.channel_id ?? "");
      if (!cid) continue;
      const furl = String(item.error_details?.final_url || item.final_url || "");
      if (!furl.includes("__hdnea__=")) continue;

      const token = furl.substring(furl.indexOf("__hdnea__=") + 10).split("&")[0];
      const cookieStr = `__hdnea__=${token}`;
      const m = /exp=(\d+)/.exec(token);
      const expSec = m ? Number(m[1]) : 0;

      liveCookiesMap.set(cid, { streamUrl: furl, cookie: cookieStr, expSec });

      // Update active in-memory channel if present
      const existing = extraChannelsMap.get(cid);
      if (existing) {
        existing.streamUrl = furl;
        existing.cookie = cookieStr;
        count++;
      }
    }
  } catch {
    // json parse failure
  }
  return count;
}

export async function loadExtraChannels(force = false): Promise<void> {
  if (inflightUpdate) return inflightUpdate;
  if (!force && extraChannelsMap.size > 0 && Date.now() - lastLoadedAt < 3600_000) {
    return;
  }

  inflightUpdate = (async () => {
    let zeeM3uText = "";
    let sonyM3uText = "";

    // 1. Fetch remote playlists with fallback
    try {
      const res = await fetch(ZEE_M3U_URL, { signal: AbortSignal.timeout(12_000) });
      if (res.ok) zeeM3uText = await res.text();
    } catch {
      // fallback
    }

    try {
      const res = await fetch(SONY_M3U_URL, { signal: AbortSignal.timeout(12_000) });
      if (res.ok) sonyM3uText = await res.text();
    } catch {
      // fallback
    }

    const channels: ExtraChannel[] = [];
    if (zeeM3uText) channels.push(...parseM3u(zeeM3uText));
    if (sonyM3uText) channels.push(...parseM3u(sonyM3uText));

    // Fallbacks
    channels.push(...parseM3u(EMBEDDED_ZEE_M3U));
    channels.push(...parseM3u(EMBEDDED_SPORTS_M3U));
    channels.push(...parseM3u(EMBEDDED_SONY_M3U));

    // Deduplicate by channelNumber
    const map = new Map<string, ExtraChannel>();
    const seenNumbers = new Set<number>();
    for (const ch of channels) {
      if (seenNumbers.has(ch.channelNumber)) continue;
      seenNumbers.add(ch.channelNumber);
      map.set(String(ch.channelNumber), ch);
      map.set(`zee_${ch.channelNumber}`, ch);
    }

    extraChannelsMap = map;
    lastLoadedAt = Date.now();

    // Fetch live tokens to ensure all streams have fresh cookies
    await fetchLiveTokens();

    // Apply tokens
    for (const [cid, info] of liveCookiesMap) {
      const ch = extraChannelsMap.get(cid);
      if (ch) {
        ch.streamUrl = info.streamUrl;
        ch.cookie = info.cookie;
      }
    }

    // Persist to disk cache
    try {
      const p = path.join(config.dataDir, CACHE_FILE_NAME);
      const serialized = Array.from(new Set(extraChannelsMap.values()));
      fs.writeFileSync(p, JSON.stringify(serialized), "utf8");
    } catch {
      // ignore disk write error
    }
  })().finally(() => {
    inflightUpdate = null;
  });

  return inflightUpdate;
}

export function initExtraChannels(): void {
  // Try loading from disk cache first for fast 0ms startup
  try {
    const p = path.join(config.dataDir, CACHE_FILE_NAME);
    if (fs.existsSync(p)) {
      const items = JSON.parse(fs.readFileSync(p, "utf8")) as ExtraChannel[];
      for (const ch of items) {
        extraChannelsMap.set(String(ch.channelNumber), ch);
        extraChannelsMap.set(`zee_${ch.channelNumber}`, ch);
      }
      lastLoadedAt = Date.now();
    }
  } catch {
    // ignore
  }

  if (extraChannelsMap.size === 0) {
    // Parse embedded immediately
    const fallback = [
      ...parseM3u(EMBEDDED_ZEE_M3U),
      ...parseM3u(EMBEDDED_SPORTS_M3U),
      ...parseM3u(EMBEDDED_SONY_M3U),
    ];
    for (const ch of fallback) {
      extraChannelsMap.set(String(ch.channelNumber), ch);
      extraChannelsMap.set(`zee_${ch.channelNumber}`, ch);
    }
  }

  // Trigger background network update
  void loadExtraChannels(true);

  // Background recurring scheduler: refresh playlists every 2 hours, cookies every 15 minutes
  if (!refreshTimer) {
    refreshTimer = setInterval(() => {
      void fetchLiveTokens();
      if (Date.now() - lastLoadedAt > 2 * 3600_000) {
        void loadExtraChannels(true);
      }
    }, 15 * 60 * 1000);
    refreshTimer.unref();
  }
}

export function stopExtraChannelsScheduler(): void {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

export function isExtraChannel(channelId: string): boolean {
  const cleanId = channelId.replace(/^zee_/, "");
  return extraChannelsMap.has(channelId) || extraChannelsMap.has(cleanId);
}

export function getExtraChannelSync(channelId: string): ExtraChannel | undefined {
  const cleanId = channelId.replace(/^zee_/, "");
  return extraChannelsMap.get(channelId) || extraChannelsMap.get(cleanId);
}

export async function getExtraChannel(channelId: string): Promise<ExtraChannel | undefined> {
  const cleanId = channelId.replace(/^zee_/, "");
  let ch = extraChannelsMap.get(channelId) || extraChannelsMap.get(cleanId);
  if (!ch) {
    await loadExtraChannels(true);
    ch = extraChannelsMap.get(channelId) || extraChannelsMap.get(cleanId);
  }
  if (!ch) return undefined;

  // On-demand token freshness check
  if (isTokenExpired(ch.streamUrl, ch.cookie)) {
    await fetchLiveTokens();
    const live = liveCookiesMap.get(String(ch.channelNumber)) || liveCookiesMap.get(cleanId);
    if (live) {
      ch.streamUrl = live.streamUrl;
      ch.cookie = live.cookie;
    }
  }

  return ch;
}

export async function getExtraChannels(): Promise<Channel[]> {
  if (extraChannelsMap.size === 0) {
    initExtraChannels();
  }
  const seen = new Set<number>();
  const list: Channel[] = [];
  for (const ch of extraChannelsMap.values()) {
    if (seen.has(ch.channelNumber)) continue;
    seen.add(ch.channelNumber);
    list.push({
      id: String(ch.channelNumber),
      name: ch.name,
      logoUrl: ch.logoUrl,
      group: ch.group,
      language: ch.language,
      isDrm: ch.isMpd && !!ch.licenseKey,
      isCatchup: false,
      channelNumber: ch.channelNumber,
      stbNumber: ch.stbNumber,
      isPremium: true,
      planType: "extra",
    });
  }
  return list.sort((a, b) => a.channelNumber - b.channelNumber);
}

export function clearKeyToShakaDrm(clearKeyStr: string): Record<string, string> {
  const pairs = clearKeyStr.split(",");
  const out: Record<string, string> = {};
  for (const p of pairs) {
    const [kidHex, kHex] = p.trim().split(":");
    if (kidHex && kHex) {
      out[kidHex.toLowerCase()] = kHex.toLowerCase();
    }
  }
  return out;
}
