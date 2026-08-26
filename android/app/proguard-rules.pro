# 지금은 minifyEnabled false 라 이 파일이 쓰이지 않는다.
# 나중에 난독화를 켜더라도 웹↔앱 통신이 조용히 끊기지 않도록 미리 막아 둔다.
#
# @JavascriptInterface 메서드는 자바스크립트가 이름으로 찾아 부른다.
# 난독화되면 이름이 바뀌어 AndroidBridge.addFile 같은 호출이 그냥 없는 함수가
# 되어 버린다(오류도 안 나고 공유만 안 된다).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.workbook.scanner.MainActivity$Bridge { *; }
