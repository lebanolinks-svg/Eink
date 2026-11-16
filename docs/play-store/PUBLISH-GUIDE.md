# Publishing InkPhone on Google Play: step by step

Everything that can be prepared in advance is in this folder. The steps below are the ones that have to be done by the account owner, in a web browser, at https://play.google.com/console. A computer is easier than a phone for this.

Google changes the wording of its screens from time to time. If a button is named slightly differently, look for the closest match.

## What you have

| File | Used in step |
|---|---|
| `InkPhone-2.0-playstore.aab` (sent separately, not in the repo) | 5 |
| `inkphone-upload-key.jks` and its password (sent separately) | Keep safe; needed for every future update |
| `listing.md` | 3, 4 |
| `privacy-policy.md` | 4 |
| `icon-512.png`, `feature-graphic.png` | 3 |
| `screenshot-1-menu.png`, `screenshot-2-launcher.png` | 3 |

## Step 1: Create the developer account (about 20 minutes, then a wait)

1. Go to https://play.google.com/console/signup and sign in with the Google account that should own the app.
2. Choose the account type:
   - **Organisation** if you have a registered company. Google asks for a D-U-N-S number (a free business ID from Dun & Bradstreet; getting one can take days to weeks if you do not have it).
   - **Yourself (personal)** otherwise. Quicker to open, but Google makes new personal accounts run a closed test with real testers before the app can go public (see step 6).
3. Fill in name, address, phone and contact email. Pay the one-time 25 USD fee.
4. Verify your identity when asked (photo of an ID document). Approval usually takes from a few hours to a few days.

## Step 2: Create the app

1. In Play Console press **Create app**.
2. App name: `InkPhone: E-Ink Look & Limits`
3. Default language: English. App or game: **App**. Free or paid: **Free**. (The 0.99 is an in-app purchase, set up in step 5b. A free app can sell in-app purchases; a paid app cannot offer a free week.)
4. Tick the declarations and press **Create app**.

## Step 3: Store listing

Open **Grow users → Store presence → Main store listing** and copy from `listing.md`:

1. Short description and full description.
2. App icon: upload `icon-512.png`.
3. Feature graphic: upload `feature-graphic.png`.
4. Phone screenshots: upload the two screenshot files from this folder. Add two to four more taken on your own phone: the home screen with doodle icons, an app shown in grey, and the "time's up" page. Real screenshots sell the app far better than the menu alone.
5. Save.

## Step 4: App content (the questionnaires)

Open **Policy and programs → App content** and work down the list. The answers are in `listing.md` under "Play Console declarations".

| Item | Answer |
|---|---|
| Privacy policy | Paste the public address of `privacy-policy.md` (see note below) |
| App access | All functionality is available without special access |
| Ads | No ads |
| Content rating | Fill the questionnaire: category Utility, every content question "No" |
| Target audience | 18 and over |
| Data safety | "No" to collecting or sharing data |
| Government app, financial features, health | No |
| Foreground service permissions | Choose "Other / special use", paste the description from `listing.md`, and add a link to the short video described there |

**Privacy policy address.** Before pasting, put your contact email into `privacy-policy.md` in place of `[CONTACT EMAIL]`. Once this work is merged into the `main` branch, the address is:

`https://github.com/lebanolinks-svg/Eink/blob/main/docs/play-store/privacy-policy.md`

**The video.** Record your phone screen for about 30 seconds: open the InkPhone menu, add a 5-minute limit to an app, open that app, and show the "time's up" page and the InkPhone notification. Upload it to YouTube as "Unlisted" and paste the link.

## Step 5: Upload the app

1. Open **Test and release → Testing → Closed testing** (personal account) or **Production** (organisation account).
2. Press **Create new release**.
3. When asked about app signing, accept **Play App Signing** (the default).
4. Upload `InkPhone-2.0-playstore.aab`.
5. Release name: `2.0`. Release notes: `First release.`
6. Press **Next**, then **Save**. Fix anything Play Console lists as an error; warnings can usually be left.

## Step 5b: Set up the 0.99 unlock

Play Console only lets you create in-app products after a build that contains billing has been uploaded (step 5), and after a payments profile exists.

1. Open **Settings → Payments profile** and create a merchant (payments) profile: business details and the bank account Google pays into. **Check first that Google supports merchant payouts in your country**; search "Google Play supported locations for merchant registration". If your country is not on that list, you cannot sell on Google Play from an account registered there, and this needs solving before anything else.
2. Open **Monetize with Play → Products → In-app products** (on newer consoles: **One-time products**) and press **Create product**.
3. Product ID: `inkphone_unlock`. It must be exactly this; it cannot be changed later.
4. Name, description and price: copy from the "Pricing" section of `listing.md`. Price 0.99 USD.
5. Save and **Activate** the product.
6. To try a purchase without paying: **Settings → License testing**, add your own Google account's email. Purchases from that account on a build installed from Google Play are then free test purchases.

## Step 6: Testing (personal accounts only)

1. In the closed test, create an email list of testers and add at least the number Google asks for (12 at the time of writing; Play Console shows the current number).
2. Send them the opt-in link Play Console gives you. Each tester must tap it and install the app from Google Play.
3. Keep them opted in for the required period (14 days at the time of writing).
4. Then press **Apply for production** on the Play Console dashboard and answer the questions about your test.

## Step 7: Send for review

1. Open **Publishing overview**.
2. Press **Send for review**.

Review of a first app commonly takes several days. If Google rejects it, the email says which policy; the most likely questions are about usage access and drawing over other apps, and the justifications in `listing.md` are written to answer them.

## Later updates

Each update needs a new signed `.aab` with a higher version number, built with the same upload key, uploaded as a new release.
