# Автоматическая сборка и публикация APK

Workflow `.github/workflows/android-release.yml` запускается при каждом push в любую ветку и вручную через **Actions → Android build and release → Run workflow**. После успешной сборки он прикладывает APK к артефактам Actions и создаёт отдельный GitHub Release для каждого push.

## Важно: подпись релизного APK

Чтобы релизный APK был подписан постоянным ключом и обновлялся поверх предыдущей установки, добавьте в **Settings → Secrets and variables → Actions → New repository secret** четыре секрета:

- `ANDROID_KEYSTORE_BASE64` — содержимое файла keystore, закодированное командой `base64 -w 0 release-keystore.jks` (на Linux).
- `ANDROID_KEYSTORE_PASSWORD` — пароль хранилища.
- `ANDROID_KEY_ALIAS` — alias ключа.
- `ANDROID_KEY_PASSWORD` — пароль ключа.

Создайте keystore один раз и храните резервную копию в безопасном месте. Не коммитьте keystore или пароли в репозиторий. Для существующего приложения используйте тот же ключ подписи, которым подписаны установленные пользователем APK, иначе Android не разрешит обновление поверх них.

Если секреты не заданы, workflow всё равно собирает APK, но `app-release-unsigned.apk` не подходит для обычной установки. `app-debug.apk` подписан debug-ключом и пригоден для тестирования, но ключи debug могут отличаться на разных машинах; для постоянных релизов настройте секреты.

## Разрешения GitHub

В **Settings → Actions → General → Workflow permissions** разрешите `Read and write permissions`, если настройки репозитория запрещают workflow создавать Releases. Workflow запрашивает `contents: write`.

## Примечания

- Сборка и публикация запускаются после каждого push; одновременные запуски одной ветки могут отменяться в пользу последнего коммита.
- Убедитесь, что `gradlew` и `gradle/wrapper/gradle-wrapper.jar` находятся в репозитории.
- Если workflow падает, откройте вкладку Actions и изучите лог шага, отмеченного красным.
