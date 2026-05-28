# Gboard 120Hz Fix
When typing with Gboard on Pixel devices, the system LTPO automatically locks the refresh rate at 60Hz, regardless of whether Smooth Display is enabled. This module aims to fix this system behavior. By simulating pixel-level keyboard jitter, it avoids frame drops while typing, thus improving the user experience.

How to use:
1. Install [LSPosed](https://t.me/LSPosed).
2. Download and install this module from [Releases](https://github.com/bitepen/Gboard-120Hz-Fix/releases).
3. In the LSPosed manager, enable this module and set its scope to Gboard (com.google.android.inputmethod.latin).
4. Force stop Gboard; the changes take effect without a reboot.

Using this module means you are responsible for any potential risks. The author is not responsible for device damage, including but not limited to bootloop issues and bricking.
