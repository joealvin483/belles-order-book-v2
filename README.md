# Belle's Order Book — Android app

This turns the single-file order book into a real installable Android app.
You do **not** need Android Studio or a developer machine: GitHub builds the
APK for you and hands you a file to install.

---

## Build it (about 20 minutes, once)

1. Make a free account at **github.com** if you don't have one.
2. Click **+** (top right) → **New repository**.
   - Name: `belles-order-book`
   - Choose **Private**
   - Click **Create repository**
3. On the next screen click **uploading an existing file**.
4. Drag in **everything from this folder** — the `app` folder, the `.github`
   folder, and the four files beside them (`build.gradle`, `settings.gradle`,
   `gradle.properties`, `README.md`). Keep the folder structure.
5. Click **Commit changes**.
6. Open the **Actions** tab. A run called *Build APK* starts by itself. Wait
   about 4 minutes for the green tick.
7. Click the finished run, scroll to **Artifacts**, and download
   **belles-order-book-apk**. Inside is `belles-order-book.apk`.

If the Actions tab says workflows are disabled, click the button to enable them
and then **Run workflow** on the left.

## Install it on the tablet

1. Move the APK to the tablet (email it to yourself, Google Drive, USB cable).
2. Tap the file. Android will say installing from unknown sources is blocked —
   tap **Settings** and allow it for the app you're installing from (Files or
   Chrome), then tap the APK again.
3. Install, then open **Belle's Order Book** from the home screen.
4. First launch asks you to create the owner account, same as before.

## Set it up again after installing

The app does not carry over anything from the browser version. So:

- **Setup → This tablet** — name it and give it an order code (A, B, …)
- **Setup → Sync** — paste the Apps Script URL again
- **Setup → Schools / Departments / Products** — or better, restore a JSON
  backup from the browser version: **Setup → Restore (replace all)**

That last option is the quick way across: take a backup in the browser version
first, then restore it in the app.

## What the app does better than the browser version

- Its own icon, no address bar, no accidental tab closing
- Backups and CSV files save straight to Downloads with **no prompt**
- Printing goes through Android's print service, so a thermal printer app
  like RawBT appears as a target
- The form scanner can open the camera directly
- Receipts hand off properly to Messages, WhatsApp, Viber, Telegram and email
- **Google Sheet sync stops being blocked.** In the browser the page runs from
  a file with no origin, which Google can refuse. The app serves the page from
  a real address, so sync just works.

## Changing the app later

The whole order book is one file: `app/src/main/assets/index.html`. To update
it, replace that file in the repository and bump `versionCode` and
`versionName` in `app/build.gradle`. Actions rebuilds and you install the new
APK over the old one — **saved orders are kept**, because the app's storage is
tied to the app, not the file.

## Notes

- The APK is *debug signed*. It installs and runs fine, and is normal for a
  private in-house app. It cannot be published on the Play Store as-is.
- Minimum Android 7. Any tablet from the last several years is fine.
- Orders live in the app's private storage. Uninstalling the app deletes them,
  so keep the automatic JSON backups and the Sheet sync switched on.
