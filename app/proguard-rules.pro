# Camera2 / MediaRecorder / MediaStore are framework classes and need no keep rules.
# Keep Compose tooling metadata out of release builds (handled by R8 automatically).

# Keep enum names used for state restoration / logging.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
