# Ponte do Leve Meditar (HTML chama Leve.speak, Leve.close...)
-keepattributes JavascriptInterface
-keepclassmembers class com.leve.launcher.MeditacaoActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
