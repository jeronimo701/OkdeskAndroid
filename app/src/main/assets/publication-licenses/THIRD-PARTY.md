# Сторонние компоненты

Gradle Wrapper (gradlew, gradlew.bat, gradle/wrapper/) сохраняет исходные уведомления. Лицензия из META-INF/LICENSE оригинального wrapper JAR приложена в licenses/Gradle-Wrapper-LICENSE.txt. Проект Gradle: https://github.com/gradle/gradle .

Android SDK, Android Gradle Plugin и JDK не входят в репозиторий. Их условия не меняются. Зависимости перечислены в build.gradle и app/build.gradle; собственная лицензия Jeronimo701 их не перелицензирует. Уведомления сторонних библиотек в APK сохраняются при стандартной сборке Gradle.

В OkdeskAndroid используется androidx.webkit:webkit:1.17.1. Исходный проект AndroidX: https://android.googlesource.com/platform/frameworks/support/ . Встроенные userscript assets зафиксированы отдельно от браузерных скриптов; изменения Gist не обновляют Android-приложение автоматически.

AndroidX WebKit 1.17.1: Apache License 2.0 согласно POM поставщика; текст приложен в licenses/Apache-2.0.txt и в assets/publication-licenses APK. Copyright The Android Open Source Project.
