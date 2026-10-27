Partner - setup

Is folder ki saari files GitHub repo me ek hi jagah (kisi folder ke andar nahi) upload karni hain.
Sirf android.yml ka text alag se is path par banana hai:  .github/workflows/android.yml

1. Add file > Upload files: android.yml ko chhodkar baaki saari files upload kar, Commit kar.
2. Add file > Create new file. Naam me type kar:  .github/workflows/android.yml
   android.yml ka pura text copy karke paste kar, Commit kar. (Purani file ho to usi ko edit karna, naya naam mat banana.)
3. APK: Actions > Build APK > Run workflow. 5-10 minute baad Artifacts me Partner-APK.
4. Install: nayi APK purani app ke upar seedhi install karo. Uninstall mat karo. App me Settings ke neeche "Version - build N" dikhta hai.

debug.keystore ko kabhi delete ya badalna mat. Wahi purani APK ke upar update install hone deta hai.
Repo ko Private rakho (Settings > Danger zone > Change visibility) aur debug.keystore ki ek copy phone ya Google Drive me alag bhi rakho.
Workflow file ka naam ya jagah mat badalna, warna build number 1 se shuru ho jata hai aur Android update rok deta hai.

Web version (Pages) ab zaroori nahi hai, sirf Android app chalti hai.
