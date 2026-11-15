# NFC: what works, what cannot, and what still needs a real phone

This note is deliberately plain about the limits. An app that reads a medical sensor should not
promise more than the phone can do.

## What the app does

- **Reads a FreeStyle Libre 2 (and Libre 2 Plus) over NFC.** It sends only read commands. The one
  command that changes anything is "enable streaming", sent only if the person switches on live
  values themselves.
- **Shows the sensor's own serial number**, derived from the tag's UID the same way the number on
  the box is. If a tag does not hand over a usable identifier, the screen says
  **"Not available"** rather than showing a number. A wrong number here is worse than none: it is
  the number a doctor reads off the box.
- **Looks for the sensor by itself while a screen is open** (NFC reader mode, NFC-V). Nothing to
  press.
- **Can open the scan screen when the phone touches a sensor**, if that is switched on in the
  settings. It is off by default so that installing the app changes nothing for other apps that
  read the sensor.

## What Android does not allow, so the app does not promise it

- **NFC does not work while the screen is off.** This is Android, not this app, and no app can
  change it. The scan screen says so in as many words.
- **The phone must be unlocked.** Same reason.
- **Background NFC scanning is not a thing.** There is no supported way for an app to sit in the
  background and poll for a tag. Anything claiming otherwise is either rooted, or not telling the
  whole story.

## FreeStyle Libre 3 has no NFC at all

The Libre 3 sends its values over Bluetooth Low Energy only. Tapping it with a phone will never
work, whatever an app claims. If you have a Libre 3, use **Live values** (Bluetooth) or
**LibreLinkUp** instead. The scan screen says this too.

## What has NOT been verified

**Nothing here has been tested on real hardware.** It has been tested against recorded data from
real Libre 2 scans (two sensors, in `app/src/test/.../reference/`) and against a simulated tag,
which is not the same thing as a sensor taped to somebody's arm.

Before calling NFC finished, check on a real phone with a real sensor:

1. The scan screen opens when the phone touches the sensor (with auto-launch switched on).
2. The serial number shown matches the one on the sensor box.
3. A scan takes about a second and the phone vibrates.
4. Holding the phone still for a second scan right away does not double-count (a repeat inside
   three seconds is ignored on purpose).
5. With live values on, the sensor keeps sending to this app.
6. A phone case: try with and without.

## Where to look if a scan fails

The scan screen says why: no NFC on the phone, NFC switched off, tag lost mid-read, sensor not
started, sensor ended, or "not a Libre sensor". Each of those comes from a real answer (or the
absence of one) from the tag, not a guess.

Logs: `supabase functions logs walhad-ai` for the AI, and the details screen of the app for the
last scan.
