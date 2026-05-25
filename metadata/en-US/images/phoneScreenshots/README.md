# Phone screenshots

F-Droid (and Murena App Lounge) display these in the listing. PNG, native phone
resolution preferred. Files are picked up alphabetically — number them so they
appear in a meaningful order.

Recommended set:
- `01-player.png` — Player tab with a book loaded, mid-playback.
- `02-library.png` — Library tab showing a few imported books.
- `03-settings.png` — Settings tab.
- `04-voice-setup.png` — Voice setup screen.

## Capture script (PowerShell on Windows + adb)

Phone must be unlocked, narrator installed, and at least one book imported.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = "$PSScriptRoot"

function shot([string]$name) {
  & $adb shell screencap -p /sdcard/$name
  & $adb pull /sdcard/$name "$out/$name"
  & $adb shell rm /sdcard/$name
}

# Manually navigate the app between each call — adb input tap is fragile across
# devices because coordinates aren't portable. Run one line, swipe to the next
# tab, run the next.
shot 01-player.png      # be on the Player tab with a book loaded
shot 02-library.png     # switch to Library tab
shot 03-settings.png    # switch to Settings tab
shot 04-voice-setup.png # tap Voice setup from Settings
```

## Also needed

- `metadata/en-US/images/icon.png` — square launcher icon, typically 512×512.
- `metadata/en-US/images/featureGraphic.png` — optional, 1024×500.

`icon.png` can be generated from the mipmap source: open `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
in Android Studio and export at 512×512, or extract the highest-res mipmap PNG
directly from `app/src/main/res/mipmap-xxxhdpi/`.
