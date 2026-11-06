# VkDownloader — Java rewrite

## Файлы (скопировать в проект)

```
app/src/main/java/com/example/vkdl/MainActivity.java
app/src/main/java/com/example/vkdl/PlayerActivity.java
app/src/main/java/com/example/vkdl/Mp4DownloadService.java
app/src/main/java/com/example/vkdl/HlsDownloadService.java
app/src/main/AndroidManifest.xml
app/build.gradle.kts
```

Опционально корень:
```
build.gradle.kts  ← без kotlin plugin (см. build.gradle.kts.root)
```

## Удалить Kotlin-исходники

```bash
rm -f app/src/main/java/com/example/vkdl/*.kt
```

## Сборка

```bash
./gradlew :app:assembleDebug
# или release (меньше размер):
./gradlew :app:assembleRelease
```

## Что изменилось

- Весь код на **Java**
- Нет Kotlin / coroutines
- Нет **media3-ui** (свой UI на TextureView)
- Только `media3-exoplayer` + `media3-exoplayer-hls`
- Live-ссылки, встроенный плеер, свайп громкости/яркости, fullscreen
