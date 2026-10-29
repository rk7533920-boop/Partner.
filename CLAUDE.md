# Partner: instructions and memory for Claude

Read this whole file before you touch anything. It is the single place where everything about this app is written down, so that any Claude (any account, any chat, Claude Code) can continue the work without asking the owner to explain it all again.

Keep this file up to date. When the owner approves a new rule, feature or design choice, or you change how something works, add it here in the same delivery. Never put API keys, passwords or personal data in this file (the GitHub repo may be public).

---

## 1. What Partner is

A personal diary and mentor app for one person (the owner), built by himself with Claude's help. It runs as an Android APK (Capacitor wrapper around one big `index.html`) and also as a web page. He mostly uses it with the phone locked, through the lock-screen talk button and voice.

- Talks to the Claude API directly from the phone with his own key (no server). Default chat model is Haiku (cheap). Claude answers only by calling tools (no plain-text answers).
- Core idea: he says what he is doing, the app writes the diary by itself (start and end times, food, study, gym, sleep, phone time), tracks targets, habits, routines, gym, food macros, and nudges him like a mentor.
- Tabs: Mentor (chat/voice), Today (diary timeline), Habits, Health, Insights (Patterns, Identity). Settings holds everything else (Lockdown, Phone access, voices, backup, memory, food databank, problems list).

## 2. How to work with the owner (his standing rules)

- He writes in Hinglish (Roman Hindi + English). Reply to him in Hinglish. **All text inside the app UI stays in English.** The AI mentor's chat replies stay in Hinglish (Roman letters, never Devanagari); the `speak` text for text-to-speech is Devanagari Hindi.
- He wants a minimalist, aesthetic, clean UI.
- **Judge every request first**: is it genuine and useful? If you have any doubt, ask before changing. Never change anything without telling him. After changing, say plainly what changed.
- When he reports a problem, check the logic end to end and fix related problems yourself, without him having to spell out every detail or say "do it" each time; then tell him what you changed.
- Tokens are not a worry: no upper limit on tokens for him. Still use prompt caching for static data.
- UI/UX redesign: only mockups so far. Do not build a redesign until he says so. He approved only the Settings and Lockdown screens and the Undo chips of the minimalist mockup; Today, Habits and Health keep their current look.
- Deliver only what changed, plus an updated full zip when asked. Always keep `.github/workflows/android.yml` and `debug.keystore` (see section 4).
- Before delivering: run the checks in section 8.

## 3. Repo layout (flat, everything in the root)

- `index.html`: the whole app (HTML, CSS, JS, about 230 KB). Almost all work happens here.
- Native Java (package `com.partner.diary`): `MainActivity`, `UsageStatsPlugin` (screen time), `QuickPlugin` (lock-screen button), `PartnerAssistService`, `PartnerSessionService`, `PartnerRecognitionService` (assistant/voice), `SpeakerPlugin`, `SpeakService`, `SpeakReceiver`, `SpeakBootReceiver` (spoken reminders and timer ring), `HotwordService`, `HotwordPlugin` ("Hey Partner"), `OfflineVoicePlugin` (offline Hindi voice), `PhonePlugin` (alarms and calendar), `LockdownPlugin`, `LockdownService`, `LockdownAcc`, `PartnerAdminReceiver`, `BankModeReceiver` (lockdown and banking mode).
- Native Java added 2026-10-28 (copied by the workflow step "Add spending, night call rule and call screening", which also adds the manifest entries and the permissions READ_SMS, READ_CONTACTS, ANSWER_PHONE_CALLS, READ_PHONE_STATE): `SpendPlugin` (plugin `Spend`: bank/UPI SMS list, payment-notification queue, contacts, permission and role requests), `NightRule` (the night-rule setting and its helpers, shared by the three below), `NightCallScreen` (CallScreeningService: rejects calls to and from people not on the list during the night window, and records every call it sees as allowed or not), `PartnerNotifListener` (NotificationListenerService: keeps UPI payment notifications for the spending page, and hangs up WhatsApp and other app calls from people not on the list by pressing the Hang up or Decline button of the call notification).
- XML: `assist_service.xml`, `lockdown_service.xml`, `device_admin.xml`, `lockdown_strings.xml`.
- `.github/workflows/android.yml`: builds the APK. It creates the Android project with `npx cap add android`, copies the Java and XML files above into it and patches the manifest with `sed`. **A new native file must be added to this workflow, or it is silently left out of the APK.**
- `package.json`, `capacitor.config.json` (appId `com.partner.diary`, webDir `www`), `sw.js` and `manifest.webmanifest` (web version), icons, `README.md` (setup steps, Hinglish), `test_day_logic.js` (see section 8).
- `debug.keystore`: signs the APK. Never delete, regenerate or replace it, or updates will not install over the old app. SHA-256 of the key: `e17a7b96668a6e242007bb33a17278a5054431a6135baa76aaafeea7300be1ac` (also in `android.yml` as `EXPECTED_KEY_SHA256`; the build stops if the key in use differs). Optional: the workflow uses the GitHub Secret `PARTNER_KEYSTORE_B64` (base64 of the keystore) when it exists, otherwise the repo file. The owner keeps the repo Private and a copy of the keystore outside GitHub.
- App version: `android.yml` sets `versionCode` = `github.run_number` and `versionName` = `1.0.<run number>` right after `npx cap add android`, and stamps the same number into `const BUILD='dev';` in `www/index.html` (shown in Settings as "Version · build N"). Without a rising versionCode Android treats every build as the same version and the update will not install over the old app. **Never rename or recreate the workflow file**: the run number restarts and the new APK would look older than the installed one.

Build: GitHub, Actions, "Build APK", Run workflow, about 5 to 10 minutes, then download the artifact `Partner-APK`. Web version: GitHub Pages from `main` root.

## 4. Architecture of index.html (map)

- State object `S` (defaults in `D`). Stored in IndexedDB database `partner`, object store `kv`, one key per field: `set, mem, routines, rrun, foods, entries, sleep, usage, targets, chat, fu, ana, notes, tags, tl, habits, hlog, gym, wt, open, rx, timers, errs`. `put(key)` saves one key.
- Lock-screen mode `QK`: the app opened from the locked phone loads only a few keys and does **not** write to the database. Its changes go to an `inbox` key, and the full app merges it in `mergeInbox()` (idempotent: repeating a bed/wake or an entry id must never create a duplicate).
- Prompt: `sysA()` (static rules, cached), the "About him" block (cached) and the "Right now" block, built in `sys()`. `LIST` = numbered diary entries of yesterday and today that the AI can fix by number.
- Tools the AI can call are defined in `TL` (`log_entry`, `start_activity`, `end_activity`, `update_entry`, `delete_entry`, targets, habits, gym, weight, reminders, alarms, calendar, timers, routines, memory, food databank, banking mode, ...). `send()` applies them. There is a regex backup (`rxScan`, `S.rx`) that logs start/end phrases when Claude fails or logs nothing; the AI may teach it new phrases with `learn_phrase`.
- Rendering: `dayView()` (Today), `diaryRows()` (timeline), `segsFor()` (the "Where your day went" bar), editor sheet `buildEdt/drawEdit/A.esave`.
- Backups: automatic to phone storage folder `Partner/` (one file per day), import takes the whole folder; API keys may be included (files stay on the phone).
- Gotcha: every handler name in the `A` object must be unique (`test_day_logic.js` now checks this). `A.bknow` was defined twice, so "Back up now" ran banking mode; banking mode is now `A.bkbank`. Wrappers `go`, `tab`, `mic`, `review` are deliberate.
- Gotcha (old): every handler name in the `A` object must be unique. `A.undo` (chat Undo) was silently replaced by a later `A.undo` (toast Undo) and the chat button did nothing; the toast one is now `A.undot`. Undo also takes back a bed or wake-up logged by that message.
- Failures must be visible: `fail()` shows a banner, lists it in Settings > Problems and sends an Android notification when the app is in the background.

## 5. Diary logging rules (the most important part)

These are decisions made together with the owner. Do not undo them without asking.

**Days**
- A day runs from one night-sleep wake-up to the next, not midnight. **A new day starts automatically** when he wakes after a night's sleep. He never has to say "din khatam" (the phrase still marks a sleep as night sleep if said, but nothing depends on it).
- What counts as night sleep (`isNight`): an explicit choice wins (sleep editor: Automatic / Night sleep / Nap); otherwise any sleep of 6 h or more, or 90 min or more that starts between 9 PM and 7 AM. A wake-up with no bed time counts if it is between 3 AM and 1 PM. Two night wake-ups less than 5 h apart count as one night (the later one starts the day). A daytime nap never starts a day.
- Night sleep belongs to the day that is ending (bed 2:30 AM Saturday is in Friday's day). Naps belong to the day they happen in.
- If no wake-up is logged, the app changes the day by itself at the first 5 AM at least 14 h after the last wake-up (`synDay`). While a bed time is open (asleep, not yet woken, younger than 14 h) the day does not change.
- `dayStart(ts)` is the one function that answers "which day is this moment in". Use it everywhere; never use midnight.

**Entries and running activities**
- Things that take time are a start and an end. "Going to eat" opens a running activity; "came back" closes it with the app computing the length. The end is logged immediately even when details are missing (they are filled in later with `update_entry`). A start time in the future is not accepted (the app uses now and says so).
- An end only closes a running activity of the same kind (or one the AI points to by its O number). A meal end never closes a study.
- Something he says with both start and end ("2 baje se 4 baje tak padha") is one finished entry.
- Earlier days: `log_entry` and `end_activity` accept `date` (YYYY-MM-DD); with no time the app uses 12:00 and says which day it saved to. Yesterday's entries are in `LIST` so he can correct them by voice.
- **The diary shows only what he actually did**, so he can see where the day went, what he did not do and where time was wasted (the purpose of keeping a day log). Kinds `plan`, `feeling` and `skip` are stored for the AI's own analysis (they are in the prompt's diary list) but are never shown in the diary and do not count as "something was logged" for the check-in. "Going to eat", "sat down to study", "starting now" are `start_activity`, never `plan`. A correction of something already told ("nahi, 4 ande the") is `update_entry`, never a new entry.
- Phone entries are hidden only when real screen-time blocks cover at least half of them (`PHN`). A call or chat (kind other, tag Social) is never screen time. Anything the AI says it logged must be visible somewhere; if the app cannot show it, it must say so.
- Identical back-to-back entries (same text and tag, gap up to 5 min) become one. The same entry id, or the same sleep (same id, same or almost same times), twice becomes one (`fixData`).
- Entries that overlap an existing one by 60 percent or more are kept, but the app tells him ("delete one if it is a duplicate").
- Wake before bed, a repeated wake-up, or a bed time that never got a wake-up are never dropped silently: the reply shows a note. An open bed time older than 16 h is treated as never closed.
- The same activity started again within 2 minutes of the earlier one ending continues it (no second row, no gap); screen-time blocks of one app 2.5 minutes apart or less are one block.
- A running activity older than 16 h is kept as an entry without a length ("Never ended") and he is told, never deleted silently.
- Unlogged time is unknown, not zero (grey). Missing nutrition is "not logged", not 0 kcal.
- Timeline scale: 1 hour = 1 cm on screen. Same-time entries: he wants the one that started earlier (and has ended) first, e.g. Woke up before Chai; the current sort is by start time, then shorter first, so check this case.
- AI must never estimate calories itself: it names foods and amounts, the app computes from the Food databank.
- Phone time comes from real screen-time data; the AI must never log phone entries from his words alone (it may ask once what a long unexplained phone block was).

**Honesty and data hygiene (added after analysing his real backup)**
- `said` (the Roman-letter copy of a Devanagari voice message) must be his words. If it matches the AI's own reply (`saidIsReply`) it is ignored; otherwise the reply text got logged as his message and its words ("khatam") ended his activities. The backup regex rule never treats "finished" words in a future or negative sentence as an end.
- An entry with no text from the AI gets the kind name ("Meal"), never his raw sentence.
- Screen time is cleaned (`cleanUsage`, `usClean`): a Settings block over 30 min and any block over 4 h are phantoms (Settings showed up as a 7 to 18 hour block every night and turned sleep into Phone time); overlapping blocks of one app become one. In "Where your day went" real sleep always wins over a phone-use block (`segsFor`).
- Food told after the meal (the AI had written "details pending") is attached to that meal entry, not saved as a second meal. A new entry that falls inside a sleep gets a warning.
- Meals in Health: each meal entry (`kind:'meal'`) is shown as "Meal N" (numbered by time inside the Partner day); tapping it opens a sheet with every ingredient from `e.items` ({food,q,u,k,p,c,f}). An ingredient that could not be worked out is stored as {food,q,u,na:1} and is never counted as zero; the meal shows Incomplete and its totals get a "+". The meal total is always the sum of its counted ingredients (`mealSum`). One calculation path: `foodCalc` (used by `foodFill` for the AI and by the sheet for add/edit). Add, edit and remove change the same entry by id and call `put('entries')`. Day arrows (`HD`) change the meals list and the four tiles; the 7-day bars are tappable. Settings > "Meal text from AI" (`S.set.mealTxt`) shows or hides the AI's text under "Meal N". Tests for this are at the end of `test_day_logic.js`.
- Weigh-ins within 30 minutes are one (the latest).
- An open bed time with no wake-up is closed where the phone was first used after it (4 min or more), flagged `est`, and the AI is told "he was awake by then, ask when he woke up". Bed or wake is logged only from his own words about sleeping, never from eating or chatting late at night.
- The "nothing logged since X" check-in says what the phone shows since then (phone time, not counting his Claude/ChatGPT/Termux apps, against the study timer) and tells him bluntly to study when the time went on the phone (`nudgeInfo`). Scheduled notifications refresh after each screen-time sync.

**Retrospective changes (he can fix the past)**
- Tap any row: Edit (text, start, end with date, tag) or Delete (with Undo). Tap a grey gap: Log activity or Log sleep.
- "+ Activity" and "+ Sleep" next to Diary add something for any time on any day.
- Sleep editor: bed time, wake time and Counts as (Automatic / Night sleep / Nap), which also moves the day boundary.
- By voice: say the earlier day ("kal raat 11 baje khana khaya") or correct an entry by number.

## 6. Other approved decisions

- Tags (AI-assigned, editable, fixed colours) drive "Where your day went". Existing tags are in `DT`.
- Habits: no streaks, small steps count, no guilt on misses, identity-based (Atomic Habits). Routines (ordered steps, e.g. morning routine) start by themselves on wake-up and run until done or the time window ends; the AI lists all remaining steps.
- Health tab: gym trainer style, log exercises by voice, remember last weight/reps and notes per exercise. Four day tiles (calories, protein, fats, carbs) each with the required total; weight is a small weekly control in the lower section, not a big tile. Health goals are asked once on first launch (skippable) and live in Settings only.
- Voices: Google Cloud TTS, ElevenLabs, phone voice and offline Hindi (Piper) are all options in Settings; keep all. Voice should sound natural and stay the same on the lock screen and in reminders.
- Lockdown: rules set up beforehand in Settings (e.g. 15 min on an app locks the phone, only calls work, Partner cannot be uninstalled). **A rule's limit is the app's total time for the whole day, not per session** (day changes at 5 AM; `LockdownService.trackUsage`, saved in the service prefs so a restart does not reset it; after the limit every new use locks again). During a lockdown the notification shade and quick settings are closed at once (`isShade`/`closeShade`), because notifications (even inline replies) were a way around the lock. **The AI can never start or change lockdown** (it may mishear). Banking mode pauses the accessibility service for a few minutes so payment apps work; it is refused during a lockdown.
- **Focus nudges** (Settings > Focus nudges, APK only): he picks allowed apps, writes his own message, and sets "first nudge after", "repeat every" and an active time window. When the screen is on and the front app is not allowed (calls, Partner and the home screen are always allowed), time is counted; at the set minutes Partner speaks the message (phone voice, through the same alarm path as reminders so it works with the screen off) and repeats at the set interval. 90 s in an allowed app, or 2 min of screen off, restarts the count. Config key `S.set.nz`, sent to the service by `nzPush()` (`LK.setNudge`); the logic is `LockdownService.nudgeTick`.
- Permissions are asked once at start, not switched on one by one in Settings.
- Memory notes the AI adds by itself are active immediately but he must be told and can edit or delete them in Settings > Memory.
- Weekly or period analysis happens only through the copy-and-paste report into a Claude chat (compressed), not through in-app Sonnet calls.
- Swipe left/right between tabs; swipe-back from Settings opens Today; bottom tab buttons are large.
- "Hey Partner" must not fire in noisy surroundings.
- Workout planner (approved 2026-10-27, from the ChatGPT brief and mockup). Health has three tabs (`HTAB`: Nutrition, Workout, Weight). Workout tab: Today's workout card (Start/Resume), "Training this week" (X of target from the active plan, counted from real logged data, never from the plan), Log workout (library sheet, `WL`), History (`w-hist`, `w-det`), recent exercises (opens the existing `drawGym` sheet) and the old text log box. Code blocks: `/* ---- workout core ... end workout core ---- */` (pure logic, tested), then workout UI 1 (Health, session), workout UI 2 (library sheet, Settings pages), then "late daily review ... full-screen timer" extras.
- Workout storage: `S.wplans` (plans: `days[{id,n,f,t:'str'|'rec',ex[]}]`, `sched` = 7 day ids Monday first, `perWeek`, `dur`, `arch`), `S.wsess` (sessions: copy of the day at start, `st:'active'|'done'`, `dk` Partner day, `ex[].rows[{i,w,r,b,warm,d}]`), `S.set.wact` (active plan id), `S.set.equip` (equipment flags; default home gear: dumbbells, straight barbell, bent bar, plates, bands, push-up boards, bodyweight; bench, rack, pull-up bar, cable and a band anchor are NOT assumed). All three are in `D`, `BKEYS` and `mergeBackup` (by id).
- Single source of truth for sets: `S.gym`. A finished working set is written once into the exercise record of that Partner day (`gymLog(...,{nohook:1})`) as `{w,r,n:1,i:<set id>,sid:<session id>,u?,b?}`; the session row only keeps the same id (`wsHeal` re-reads history, so a set deleted in history reopens its row). Warm-up rows (`warm:1`) and unit `min` rows (recovery mobility) are never written to `S.gym`. Old records have no new fields and keep working. Units: `kg` (dumbbell = each dumbbell, barbell and bent bar = total on the bar), `bw` (w = extra kg, default 0), `band` (`b` = Light/Medium/Heavy, w 0), `sec`, `min`. Different kinds are never compared with each other (`wSetKind`). `setsStr` (in the core) groups equal neighbouring sets.
- Voice and quick logs while a workout is open: `gymLog` (without nohook) turns the told sets into single sets with ids and attaches them to the matching session exercise (`wsHook`), so nothing is counted twice. Exercises not in the workout stay in history only. The `log_exercise` tool got optional `u` and `b` per set; the old schema still works.
- Progression (`wSuggest`): all planned sets at the top of the rep range with the same load, and last felt not "hard" -> suggests a small increase (`inc`, default 1 kg dumbbell, 2.5 kg bar); otherwise "improve reps". It only shows text and never changes a record. Finish summary (`wsSummary`, `wsBests`) shows only real bests compared with earlier records of the same unit; exercises without a logged working set are saved as skipped. Finishing writes one diary entry (kind `gym`) unless a gym activity was already open or logged for that time; recovery-day sessions create none.
- A workout in progress keeps its own copy of the day: editing the plan applies from the next workout. Discarding asks first and keeps sets already logged.
- Settings > Workout plan (`s-wplan`, `s-wday`, `s-wex`, `s-equip`, `s-wimp`): templates (Home Strength 4 days = Upper A, Lower A + core, Recovery, Upper B, Lower B + core; Home Hypertrophy; Beginner Full Body; Bodyweight + Bands), blank plan, duplicate, archive, delete, import (Partner JSON or plain text lines), copy as text. The local exercise library `WLIB` has no images and no network use; exercises that need unavailable equipment are flagged with a swap to the alternative.
- Daily review can be done for any past day (`A.review` with `start:end` timestamps, `RVD`; `S.set.revDone` by day key). The Today tab shows a card for yesterday when it was not reviewed.
- Habits show the last 7 days, today is the last bar (`last7`), so progress is never hidden on a new week.
- Mentor tab listens by itself (`idleStart`, toggle `S.set.mentorAuto`): after a conversation stops it waits for "Hey Partner" without a tap, only while the Mentor tab is visible. It stops on every other tab and when the app is hidden. The native lock-screen Hey Partner service is unchanged. Tapping the mic to stop it pauses this until the tab is opened again.
- Settings > Ideal day (`S.set.ideal`: on, rem, items from/to/t): goes into the prompt (`idPrompt`, `idRight`); optional spoken reminders use notification ids 700+. Settings > Contingency plan (`S.set.cont`: on, style soft/firm/harsh, why, inc, say, plan): goes into the prompt (`cnPrompt`); harsh never insults worth, body, family or intelligence. Settings > Audio tracks (`S.audio` meta, file blobs in IndexedDB under `au_<id>`, not in the backup): `auTrig` plays a track when his words match its phrases and he is starting the activity, stops on "done".
- Timer is full screen (`#ring` + `.tfs`, `TF`); tapping the running timer in Today reopens it.
- Approved 2026-10-28 (his answers: spending data from bank/UPI SMS and payment notifications; he talks to Chanchal at night on normal and WhatsApp calls; the 11 PM lock has no exit until its end time):
  - Health 7-day graph: the % above a bar is `dayScore` = average of calories % and protein % against his targets, each capped at 100; a part that was not logged is left out (not zero). Carbs and fat are not in it. Bar height is still calories.
  - Identity: `h.ident` is free text; habits with the same identity (`idKey` ignores case, punctuation and a leading "I am someone who") form one group (`idGroups`) and their votes add up (Insights > Identity). The habit form shows existing identities as chips (`A.hident`). Habits are never merged automatically.
  - Contingency plan photos and videos: metadata in `S.set.cont.media`, files in IndexedDB as `cm_<id>` (not in the backup, photos up to 30 MB, videos up to 300 MB). They open full screen (`cmShow`) when he taps "I do not feel like doing it today".
  - Boredom training (card at the bottom of Habits, `S.bore` = `{id,ts,secs,goal(min),left}`): full-screen black screen with a faint timer, screen kept awake, leaving the app ends the sit. First target 2 min; a finished sit makes the next target longer (`boNext`: +10 %, at least 1 min); an early stop keeps the same target; no streaks, no guilt. A sit of 1 min or more also writes a diary entry (tag Rest). The app cannot know if he touched the phone, so it relies on his honesty.
  - Spending (Insights > Spending, `S.spend` = `{id,ts,amt,to,cat,src:'sms'|'notif'|'voice'|'manual',ks:[dedupe keys],q:1 if unsorted,del:1 if removed}`): `spSync` reads debit SMS (since `S.set.spSince`, first time 30 days) and the notification queue, `spParse` takes only amount and shop (raw text is never saved), the same payment from SMS and from a notification is merged (same amount within 6 min, different source). Categories `SPC`, by shop name keywords (`SPKW`) and by what he sorted before (`S.set.spmap`); unknown ones are "unsorted" and shown on the page and to the mentor (`spRight`, S numbers, tools `log_spend` and `tag_spend`), who asks once in the evening. Weekly donut with week arrows. Credits, OTPs and refunds are ignored.
  - Night call rule (Settings > Night call rule, `S.set.night` = `{on, warn, warn2, from, to, allow:[{n,num}], pend, pendAt}`, also stored natively by `LK.setNight`): spoken and written heads-up at `warn` (notification id 760, default 22:45) and 2 minutes before the lock (761), at `from` (default 23:00) until `to` (default 07:00) the phone is locked with NO emergency exit (`LockdownService.nightTick`, `nightUntil`), a running normal call is ended (`TelecomManager.endCall`) only when the call screener saw it and marked it not allowed (an emergency call, or no call screening role, is never touched), calls to and from other people are rejected by `NightCallScreen`, WhatsApp calls are hung up by `PartnerNotifListener` (allowed people matched by the name on the call). A weaker change (off, later start, earlier end, one more allowed contact) waits 24 hours (`ntWeaker`, same idea as lockdown) and never applies in the middle of a night window; while the rule is off nothing counts as weaker. Settings are locked while the window runs. Emergency numbers are always allowed. New permissions are in `SUS` (`sms`, `cn`, `nl`, `scr`).

## 7. Still open (check before assuming done)

- Whole-app UI redesign (mockups only).
- Mic and speech recognition improvements; the "Hey Partner" noise false triggers.
- Why bank apps (Paytm, YONO) fail on his phone (suspected accessibility service) and whether Partner can be marked trustworthy.
- Update over the old APK: fixed in the workflow (rising versionCode, key fingerprint check) but not yet proven on the phone. The first build after this change may still need one uninstall if the installed app was signed with another key.
- Not tested on a phone yet (only in a desktop browser): the Mentor tab's hands-free listening (Android may play a system sound at every restart of the recogniser), audio tracks with the screen off (WebView may pause them; a native player would be the fix), the full-screen timer's screen-awake request, and the workout screens one-handed on a real screen.
- Anything from his older request lists that you cannot verify in the code: verify first, then tell him honestly what is and is not done.

- Not verified on the phone (2026-10-28): the Java above is only syntax-checked. In particular check the WhatsApp hang-up (depends on WhatsApp's call notification having a Hang up or Decline button), `endCall` after the call screening role is granted, reading SMS on One UI, and that the night lock starts at 23:00 and ends at 07:00.

## 8. Checks before delivering

1. Syntax: extract the main `<script>` of `index.html` and run `node --check` on it.
2. `node test_day_logic.js` (must print 0 failed). Add a case there for every day or sleep rule you change.
3. For logging changes, test in a real browser with a faked clock and a stubbed `ask()` that returns tool calls (see how `send()` reads them), and look at what Today actually shows, not only the data.
4. Native Java or workflow changes cannot be tested here (no Android SDK): only a syntax check is possible (`java -m jdk.compiler/com.sun.tools.javac.Main` shows syntax errors, ignore the many "cannot find symbol" ones). Say plainly that the first APK build has to prove it.
5. List every change for the owner in plain words, and update this file.
