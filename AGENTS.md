# AGENTS.md

Правила для агентов, работающих в этом репозитории. Основные соглашения проекта
(сборка, тесты, архитектура бэкенда, конвенции фронтенда) — в `CLAUDE.md`; здесь
только то, что к ним не относится.

## Запуск проверок

Не собирай команду `gradle` руками — есть `run/test.sh` (на Windows
`run\test.bat` / `run\test.ps1`). Он сам решает то, что иначе приходится
вспоминать каждый раз: системный Gradle или `./gradlew`, нужен ли init-скрипт
для JDK 21, поднят ли `dockerd` для Testcontainers, `CI=true` для Jest.

    ./run/test.sh            # unit + front — быстрая пара, Docker не нужен
    ./run/test.sh unit       # бэкенд, только *Test
    ./run/test.sh it         # бэкенд, только *IT (dockerd поднимется сам)
    ./run/test.sh back       # все тесты бэкенда
    ./run/test.sh front      # Jest
    ./run/test.sh format     # spotlessCheck
    ./run/test.sh build      # полная сборка
    ./run/test.sh clean      # gradle clean, когда залип кэш toolchain/spotless
    ./run/test.sh smoke      # собрать jar и посмотреть UI в Chromium
    ./run/test.sh pre-pr     # format + back + build — перед пул-реквестом
    ./run/test.sh ci         # то же самое, но с --console=plain

Всё после `--` уходит в Gradle как есть, поэтому одиночный тест не повод
выходить из обёртки — свой `--tests` при этом заменяет фильтр сьюта, а не
добавляется к нему:

    ./run/test.sh unit -- --tests '*ToolTranslationsTest'
    ./run/test.sh back -- --info

Сырые команды остались в `CLAUDE.md` — они нужны, когда чинишь саму сборку, а не
когда просто гоняешь тесты.

## Визуальные проверки

Перед ручной проверкой компонента прочитай `frontend/tests/visual/cases.yaml` —
там уже описаны сценарии и данные для проверенных компонентов. Используй
существующие фикстуры. Новую проверку заводи как кейс в том же формате;
существующие id не переименовывай.
