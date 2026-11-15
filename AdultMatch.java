package com.partner.diary;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a piece of text (the address bar of a browser, a search box, something typed into a search) points to an
 * adult site or asks for adult content. Pure Java, no Android classes, so it can be tested on a computer.
 *
 * Three checks, from sure to careful:
 * 1. the host of an address (and every parent domain of it) is on the list of adult sites, or ends in .xxx .porn .adult .sex .sexy;
 * 2. a word of the text is a name that only means adult content (a site name or a plain word like porn or hentai);
 * 3. a short phrase: the word sex followed by video, chat, story and so on.
 * Words are whole words, so Essex, Sussex, sextant or Scunthorpe are never caught.
 */
final class AdultMatch {

  private AdultMatch() {}

  private static final Set<String> HOSTS = new HashSet<>(
    Arrays.asList(
      "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "xhamster2.com", "xhamster3.com", "redtube.com", "youporn.com",
      "spankbang.com", "eporner.com", "tnaflix.com", "tube8.com", "beeg.com", "porn.com", "pornone.com", "txxx.com", "hclips.com",
      "drtuber.com", "motherless.com", "thumbzilla.com", "4tube.com", "fapello.com", "erome.com", "nhentai.net", "nhentai.to",
      "hentai2read.com", "hentaihaven.xxx", "hanime.tv", "hanime1.me", "hitomi.la", "e-hentai.org", "exhentai.org", "fakku.net",
      "tsumino.com", "rule34.xxx", "rule34.us", "rule34.paheal.net", "gelbooru.com", "8muses.com", "chaturbate.com", "stripchat.com",
      "cam4.com", "bongacams.com", "livejasmin.com", "onlyfans.com", "fansly.com", "brazzers.com", "bangbros.com",
      "naughtyamerica.com", "realitykings.com", "sex.com", "xtube.com", "daftsex.com", "thothub.to", "coomer.su", "coomer.party",
      "kemono.su", "kemono.party", "simpcity.su", "manhwa18.net", "manhwa18.com", "toomics.com", "pornpics.com", "xmovies8.es",
      "javhd.com", "missav.com", "supjav.com", "jav.guru", "hqporner.com", "sunporno.com", "porntrex.com", "pornhat.com",
      "fuq.com", "ixxx.com", "nuvid.com", "yourporn.sexy", "camwhores.tv", "recurbate.com", "efukt.com", "heavy-r.com"
    )
  );

  private static final String[] BAD_TLD = { ".xxx", ".porn", ".adult", ".sex", ".sexy" };

  /** Words that only mean adult content. */
  private static final Set<String> WORDS = new HashSet<>(
    Arrays.asList(
      "porn", "porno", "pornos", "pornhub", "pornstar", "pornstars", "pornography", "xvideos", "xnxx", "xhamster", "redtube",
      "youporn", "spankbang", "xxx", "hentai", "nhentai", "hentaihaven", "rule34", "r34", "doujin", "doujinshi", "futanari",
      "onlyfans", "chaturbate", "stripchat", "livejasmin", "bongacams", "fapello", "camgirl", "camgirls", "camwhore", "nude", "nudes",
      "naked", "blowjob", "handjob", "milf", "creampie", "bdsm", "nsfw", "erotic", "erotica", "sexvideo", "sexvideos", "sexcam",
      "chudai", "antarvasna", "manhwa18", "toomics", "ecchi", "jav", "camsex", "sexting", "sexchat"
    )
  );

  /** Words that mean adult content only when they follow the word "sex" (or "adult"). */
  private static final Set<String> AFTER_SEX = new HashSet<>(
    Arrays.asList(
      "video", "videos", "chat", "story", "stories", "scene", "scenes", "pics", "pictures", "photos", "movie", "movies", "clip",
      "clips", "cam", "cams", "tape", "site", "sites", "gif", "gifs", "tube"
    )
  );
  private static final Set<String> AFTER_ADULT = new HashSet<>(Arrays.asList("video", "videos", "movie", "movies", "site", "sites", "content", "chat"));

  /** True when this text (an address, a search, a title) is adult content. */
  static boolean adult(String raw) {
    if (raw == null) return false;
    String s = raw.trim().toLowerCase(Locale.ROOT);
    if (s.length() < 3 || s.length() > 600) return false;
    // 1. a host
    String host = hostOf(s);
    if (host != null && hostBad(host)) return true;
    // 2 and 3. words
    String[] t = s.split("[^a-z0-9]+");
    String prev = "";
    for (String w : t) {
      if (w.isEmpty()) continue;
      if (WORDS.contains(w)) return true;
      if (("sex".equals(prev) && AFTER_SEX.contains(w)) || ("adult".equals(prev) && AFTER_ADULT.contains(w))) return true;
      prev = w;
    }
    return false;
  }

  /** The host of something that looks like an address, or null. */
  static String hostOf(String s) {
    String x = s;
    int i = x.indexOf("://");
    if (i >= 0) x = x.substring(i + 3);
    int end = x.length();
    for (int k = 0; k < x.length(); k++) {
      char c = x.charAt(k);
      if (c == '/' || c == '?' || c == '#' || c == ' ') {
        end = k;
        break;
      }
    }
    x = x.substring(0, end);
    int at = x.lastIndexOf('@');
    if (at >= 0) x = x.substring(at + 1);
    int colon = x.indexOf(':');
    if (colon >= 0) x = x.substring(0, colon);
    if (x.startsWith("www.")) x = x.substring(4);
    if (x.indexOf('.') <= 0 || x.length() < 4 || x.endsWith(".")) return null;
    for (int k = 0; k < x.length(); k++) {
      char c = x.charAt(k);
      if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) return null;
    }
    return x;
  }

  private static boolean hostBad(String host) {
    for (String t : BAD_TLD) if (host.endsWith(t)) return true;
    String h = host;
    while (true) {
      if (HOSTS.contains(h)) return true;
      int d = h.indexOf('.');
      if (d < 0) return false;
      h = h.substring(d + 1);
      if (h.indexOf('.') < 0) return false;
    }
  }
}
