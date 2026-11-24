# Lyrix 1.1.0 — обновление (только изменённые файлы)

Папки внутри архива повторяют структуру репозитория: **просто закинь папку `app` в корень репозитория
с заменой файлов** (GitHub → Add file → Upload files → перетащи папку `app`). Совпадающие файлы заменятся, новые добавятся.

## 1. Что куда вставить

| Файл(ы) в архиве | Куда в репозитории |
|---|---|
| `app/build.gradle.kts` | `app/build.gradle.kts` (заменить) — версия 1.1.0, BuildConfig |
| `app/src/main/AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` (заменить) |
| `app/src/main/java/com/lyrix/overlay/*.kt` (17 файлов) | `app/src/main/java/com/lyrix/overlay/` (заменить/добавить) |
| `app/src/main/res/values/colors.xml`, `themes.xml` | `app/src/main/res/values/` (заменить) |
| `app/src/main/res/drawable/ic_b_*.xml`, `ic_launcher_fg_*.xml` | `app/src/main/res/drawable/` (добавить) |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_*.xml` | `app/src/main/res/mipmap-anydpi-v26/` (добавить) |

## 2. Что УДАЛИТЬ из репозитория (иначе не соберётся)
- `app/src/main/java/com/lyrix/overlay/PlayerCard.kt`
- `app/src/main/java/com/lyrix/overlay/Ui.kt`

(на GitHub: открыть файл → ⋯ → Delete file → Commit)

## 3. Не трогать
`settings.gradle.kts`, корневой `build.gradle.kts`, `gradle.properties`, `.github/workflows/build.yml`,
`res/values/strings.xml`, `res/drawable/ic_play|pause|next|prev|notif.xml`, `ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml`.

После коммита GitHub Actions сам соберёт APK (Actions → Build APK → Artifacts).
