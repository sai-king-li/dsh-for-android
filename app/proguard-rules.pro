# Keep Kotlin coroutines / lifecycle annotations
-keepattributes *Annotation*

# WebView debugging helpers are stripped in release automatically.

# No reflection-heavy libraries used; rules below are defensive.
-dontwarn org.jetbrains.annotations.**
