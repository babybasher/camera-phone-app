# Phone Camera

Android app that turns a spare phone or tablet into a camera for the home camera
system (router: go2rtc + camctl). It speaks the same protocol as the web camera
page (`cam.html`), but runs as a foreground service so it keeps working with the
screen off.

- Live view and listen: WebRTC publish to go2rtc (`api/webrtc?dst=<id>`), H.264 preferred.
- Talk: plays `<id>_talk` from go2rtc.
- Clips (mp4, phone encoders) and photos: uploaded to camctl, then deleted from the phone.
- Motion detection: small greyscale frame differencing, same rule as the web page.
- Updates: "Check for updates" reads `app-version.txt` (the build number) from the camera page and
  installs `camera.apk` from there.

Built by GitHub Actions on every push (artifact `camera-apk`). The signing key is
in `signing/` (this repo is private): keep it, since Android only installs updates
signed with the same key.

## Camera Link (`viewer/`)

A second, separate app (`net.homecam.viewer`, file `camera-link.apk`) for phones that *watch* the cameras.
It is a preconfigured WireGuard tunnel (the official tunnel library) that carries only traffic for the home
network, with name lookups answered by the home router (which maps the camera page's name to itself), so the
normal camera page works in Chrome away from home.
The phone makes its own key; the camera page's Remote viewing page hands it a one-time code
(`homecamview://setup?api=...&code=...`) and the router (`camctl`, `/api/vpn/join`) adds the phone as a peer.
