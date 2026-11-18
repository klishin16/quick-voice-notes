Путь к Android 
```
ls -d /opt/android-sdk ~/Android/Sdk ~/.android/sdk 2>/dev/null
```

```
set -Ux ANDROID_HOME /opt/android-sdk
fish_add_path $ANDROID_HOME/platform-tools $ANDROID_HOME/cmdline-tools/latest/bin
```

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle assembleRelease
```