Partner - setup

Is folder ki saari files GitHub repo me ek hi jagah (kisi folder ke andar nahi) upload karni hain.
Sirf android.yml ka text alag se is path par banana hai:  .github/workflows/android.yml

1. Add file > Upload files: android.yml ko chhodkar baaki saari files upload kar, Commit kar.
2. Add file > Create new file. Naam me type kar:  .github/workflows/android.yml
   android.yml ka pura text copy karke paste kar, Commit kar.
3. Web version: Settings > Pages > Deploy from a branch > main / (root).
4. APK: Actions > Build APK > Run workflow. 5-10 minute baad Artifacts me Partner-APK.

debug.keystore ko kabhi delete ya badalna mat. Wahi purani APK ke upar update install hone deta hai.


Nayi files (alarm, calendar, lockdown): PhonePlugin.java, LockdownPlugin.java, LockdownService.java, PartnerAdminReceiver.java, lockdown_service.xml, device_admin.xml, lockdown_strings.xml. Inhe bhi baaki files ke saath ek hi jagah (root) upload karna hai. android.yml ka text bhi naya hai, use dobara paste karna.
