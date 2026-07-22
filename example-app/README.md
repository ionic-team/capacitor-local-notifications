# Example app — @capacitor/local-notifications

Minimal Capacitor app that exercises every plugin method (one button per method,
plus live `localNotificationReceived` / `localNotificationActionPerformed`
listeners). The plugin is consumed from the repo root via `file:..`.

## Run

```
npm install
npx cap add ios        # and/or: npx cap add android
npx cap copy
npx cap run ios        # or android  (device/simulator)
```
