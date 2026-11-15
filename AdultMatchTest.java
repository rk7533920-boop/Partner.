package com.partner.diary;
public class AdultMatchTest {
  static int bad=0;
  static void t(String s, boolean want){ boolean g=AdultMatch.adult(s); if(g!=want){bad++;System.out.println("FAIL: "+s+" -> "+g);} }
  public static void main(String[] a){
    t("https://www.pornhub.com/video?id=1",true); t("xvideos.com",true); t("m.xnxx.com/x",true); t("en.xhamster.com",true);
    t("some.site.xxx",true); t("hentai manga online",true); t("sex video",true); t("sex videos desi",true); t("porn",true);
    t("onlyfans.com/someone",true); t("youtube.com/results?search_query=nsfw+compilation",true); t("google.com/search?q=naked+girls",true);
    t("nhentai.net/g/1",true); t("Hanime.tv",true); t("adult video",true); t("toomics.com/en",true);
    t("google.com",false); t("https://www.youtube.com/watch?v=abc",false); t("Essex weather",false); t("Sussex county",false);
    t("sex education class 8 ncert",false); t("sextant navigation",false); t("whatsapp.com",false); t("github.com/anthropics",false);
    t("Scunthorpe",false); t("anal fissure treatment",false); t("pornography addiction recovery help",true);
    t("amazon.in/dp/B0",false); t("https://music.youtube.com/",false); t("reddit.com/r/india",false); t("instagram.com",false);
    t("kemono.su",true); t("a.b.c.coomer.su/post",true); t("wikipedia.org/wiki/Hentai",true); t("x",false); t("",false);
    System.out.println(bad==0?"ALL OK":"FAILED "+bad);
  }
}
