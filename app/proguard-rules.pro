# ProGuard/R8-Regeln für OpenRain
#
# Die meisten Bibliotheken (Play Billing, MapLibre GL, OkHttp, WorkManager,
# Jetpack Compose) liefern ihre eigenen consumer-Regeln in ihren AARs mit,
# die R8 automatisch anwendet. Hier stehen nur eigene, defensive Ergänzungen.

# Lesbare Stacktraces in der Play Console (Zeilennummern behalten)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager instanziiert Worker per Reflection über den Klassennamen.
# WorkManagers eigene Regeln decken das bereits ab – hier defensiv abgesichert.
-keep class com.example.rainradar.widget.RadarWidgetWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
