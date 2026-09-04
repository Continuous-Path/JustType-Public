# R8 log-stripping rules for the lean tiers (release + beta).
#
# These rules tell R8 that calls to android.util.Log.d/i/v have no
# observable side effects. R8 removes the calls, and dead-code-elimination
# then removes the string-building bytecode that fed them (StringBuilder
# allocations, append() chains, toString() calls) — IF those expressions
# have no other consumers.
#
# Log.w / Log.e / Log.wtf are intentionally NOT listed: warnings, errors,
# and "what a terrible failure" calls survive R8 stripping in every tier,
# so a field user can still capture severe diagnostics via `adb logcat`.
#
# Inside the codebase itself, the BuildConfig.DEBUG_EDITING gate (set to
# false in the release tier) takes care of stripping the debugLog()
# helper bodies. The inline + lambda rewrite (Phase D4) extends that to
# also strip the string-interpolation at debugLog call sites.

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
