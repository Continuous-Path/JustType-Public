# JustType IME ProGuard/R8 rules
#
# Most manifest-referenced components (activities, services, receivers)
# are kept automatically via AAPT-generated rules. These explicit rules
# cover edge cases and runtime reflection.

# Keep BuildConfig (used at runtime for DEBUG_EDITING flag)
-keep class org.continuouspath.justtype.BuildConfig { *; }

# Keep the broadcast receiver referenced by HeadBoard companion app
-keep class org.continuouspath.justtype.receiver.** { *; }

# Preserve custom view constructors used in XML layouts
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve custom drawables referenced by fully-qualified class name from XML
# (<drawable class="...">, e.g. KeyTileDrawable in button_background*.xml) — the
# XML inflater looks up the class by string name at runtime, which R8 can't see.
-keep class * extends android.graphics.drawable.Drawable {
    public <init>();
}
