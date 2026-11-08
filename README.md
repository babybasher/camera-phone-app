# Phone Camera

Android app that turns a spare phone or tablet into a camera for the home camera
system (router: go2rtc + camctl). It speaks the same protocol as the web camera
page (`cam.html`), but runs as a foreground service so it keeps working with the
screen off.

- Live view and listen: WebRTC publish to go2rtc (`api/webrtc?dst=<id>`), H.264 preferred.
- Talk: plays `<id>_talk` from go2rtc.
- Clips (mp4, phone encoders) and photos: uploaded to camctl, then deleted from the phone.
- Motion detection: small greyscale frame differencing, same rule as the web page.

Built by GitHub Actions on every push (artifact `camera-apk`). The signing key is
in `signing/` (this repo is private): keep it, since Android only installs updates
signed with the same key.
