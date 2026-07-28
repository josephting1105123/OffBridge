# Play Console — IARC content rating + target audience

Two separate App-content items, both of which HARD-BLOCK publishing if left incomplete:
1. **Content rating** (IARC questionnaire)
2. **Target audience and content**

## 1. IARC content-rating questionnaire
Category: **Utility / Productivity / Communication** (not a game). Answer the questionnaire honestly;
for OffBridge every sensitive question is "No":

- Violence, scary content, sexual content, nudity: **No**
- Profanity / crude humor: **No**
- Controlled substances (drugs, alcohol, tobacco): **No**
- Gambling / simulated gambling: **No**
- Real-money purchases / in-app purchases: **No** (the app is free, no IAP)
- Does the app collect/share users' location and let others see it? **No**
- Does the app let users interact, or share content/media with each other?
  **Yes, but narrowly** — it transfers the user's own clipboard/files **between that user's own paired
  devices**. It is **not** a social network or public-sharing platform: there is no public audience, no
  user discovery, no comments/feeds, no moderation surface. If the form has a free-text clarification,
  say exactly that. This typically lands the app at **Everyone / PEGI 3 / IARC "3+"**.
- Unrestricted internet access / opens web content: **No** (no in-app browser; `INTERNET` is used only
  for LAN sockets).

Expected outcome: lowest rating tier (Everyone / 3+).

## 2. Target audience and content
- **Target age groups:** select **18+ and 13–17** (i.e., teen and adult). Do **not** include any
  under-13 group. This keeps you out of the "Designed for Families" program and its extra obligations,
  and matches the privacy policy line: *"The App is not intended for use by children under the age of
  13."*
- **Appeal to children:** No — the app is a utility, not styled for or marketed to children.
- **Designed for Families:** No.

## Other App-content items to clear (each can block publish)
- **Government apps:** No.
- **Financial features:** No.
- **Health:** No.
- **Ads:** **No ads** — declare "This app does not contain ads" (true; no ad SDK).
- **News app:** No.
- **COVID-19 / contact tracing:** No.
- **Data safety:** see DATA_SAFETY.md.
