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
- XML: `assist_service.xml`, `lockdown_service.xml`, `device_admin.xml`, `lockdown_strings.xml`.
- `.github/workflows/android.yml`: builds the APK. It creates the Android project with `npx cap add android`, copies the Java and XML files above into it and patches the manifest with `sed`. **A new native file must be added to this workflow, or it is silently left out of the APK.**
- `package.json`, `capacitor.config.json` (appId `com.partner.diary`, webDir `www`), `sw.js` and `manifest.webmanifest` (web version), icons, `README.md` (setup steps, Hinglish), `test_day_logic.js` (see section 8).
- `debug.keystore`: signs the APK. Never delete, regenerate or replace it, or updates will not install over the old app. (The owner wants it kept private if possible, not on a public repo; a free way was requested.)

Build: GitHub, Actions, "Build APK", Run workflow, about 5 to 10 minutes, then download the artifact `Partner-APK`. Web version: GitHub Pages from `main` root.

## 4. Architecture of index.html (map)

- State object `S` (defaults in `D`). Stored in IndexedDB database `partner`, object store `kv`, one key per field: `set, mem, routines, rrun, foods, entries, sleep, usage, targets, chat, fu, ana, notes, tags, tl, habits, hlog, gym, wt, open, rx, timers, errs`. `put(key)` saves one key.
- Lock-screen mode `QK`: the app opened from the locked phone loads only a few keys and does **not** write to the database. Its changes go to an `inbox` key, and the full app merges it in `mergeInbox()` (idempotent: repeating a bed/wake or an entry id must never create a duplicate).
- Prompt: `sysA()` (static rules, cached), the "About him" block (cached) and the "Right now" block, built in `sys()`. `LIST` = numbered diary entries of yesterday and today that the AI can fix by number.
- Tools the AI can call are defined in `TL` (`log_entry`, `start_activity`, `end_activity`, `update_entry`, `delete_entry`, targets, habits, gym, weight, reminders, alarms, calendar, timers, routines, memory, food databank, banking mode, ...). `send()` applies them. There is a regex backup (`rxScan`, `S.rx`) that logs start/end phrases when Claude fails or logs nothing; the AI may teach it new phrases with `learn_phrase`.
- Rendering: `dayView()` (Today), `diaryRows()` (timeline), `segsFor()` (the "Where your day went" bar), editor sheet `buildEdt/drawEdit/A.esave`.
- Backups: automatic to phone storage folder `Partner/` (one file per day), import takes the whole folder; API keys may be included (files stay on the phone).
- Gotcha: every handler name in the `A` object must be unique. `A.undo` (chat Undo) was silently replaced by a later `A.undo` (toast Undo) and the chat button did nothing; the toast one is now `A.undot`. Undo also takes back a bed or wake-up logged by that message.
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

## 7. Still open (check before assuming done)

- Whole-app UI redesign (mockups only).
- Mic and speech recognition improvements; the "Hey Partner" noise false triggers.
- Why bank apps (Paytm, YONO) fail on his phone (suspected accessibility service) and whether Partner can be marked trustworthy.
- Keeping `debug.keystore` private.
- Anything from his older request lists that you cannot verify in the code: verify first, then tell him honestly what is and is not done.

## 8. Checks before delivering

1. Syntax: extract the main `<script>` of `index.html` and run `node --check` on it.
2. `node test_day_logic.js` (must print 0 failed). Add a case there for every day or sleep rule you change.
3. For logging changes, test in a real browser with a faked clock and a stubbed `ask()` that returns tool calls (see how `send()` reads them), and look at what Today actually shows, not only the data.
4. Native Java or workflow changes cannot be tested here (no Android SDK): only a syntax check is possible (`java -m jdk.compiler/com.sun.tools.javac.Main` shows syntax errors, ignore the many "cannot find symbol" ones). Say plainly that the first APK build has to prove it.
5. List every change for the owner in plain words, and update this file.
