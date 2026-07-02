# Чеклист: XSD XML Validator Service

**Проект:** `C:\1520\XSD-XML_Validator`  
**Статус на 01.07.2026:** реализовано · тесты: 8/8 · запуск: `mvn spring-boot:run` → http://localhost:8080

Отмечайте выполненные пункты: `- [x]`

---

## Этап 0. Подготовка и структура проекта

### Репозиторий и стек

- [x] Создан отдельный Maven-проект в `C:\1520\XSD-XML_Validator`
- [x] Java 17+ (сборка на JDK 21)
- [x] Spring Boot 3.4 (`spring-boot-starter-web`)
- [x] Валидация через JDK `javax.xml.validation` (без стороннего XML-парсера)
- [x] `spring-boot-starter-actuator` для health-check

### Структура пакетов

```
com.xsdvalidator
├── api/          — REST-контроллеры, DTO
├── core/         — XsdValidator, SchemaRegistry, ErrorHandler
├── config/       — XmlValidatorProperties
└── resources/
    ├── schemas/  — XSD-файлы
    └── static/   — веб-интерфейс
```

- [x] Пакетная структура создана
- [x] `application.yml`: порт, лимит размера body, путь к схемам

---

## Этап 1. Модель ответа (DTO)

### ValidationError / ValidationErrorDto

- [x] Поле `line`
- [x] Поле `column`
- [x] Поле `message`
- [x] Поле `severity` — `WARNING` / `ERROR` / `FATAL`

### ValidationResponse

- [x] Поле `valid`
- [x] Поле `errors`
- [x] Поле `schemaId`
- [x] Поле `durationMs`

### SchemaInfo

- [x] Поле `id` (имя файла без `.xsd`)
- [x] Поле `fileName`
- [x] Поле `description`

---

## Этап 2. Ядро валидации

### XsdValidator

- [x] Принимает скомпилированный `Schema`
- [x] Метод `validate(Schema, schemaId, InputStream)`
- [x] Кастомный `ErrorHandler` собирает **все** ошибки
- [x] `valid = true` только если нет ERROR/FATAL
- [x] `SAXException` / `IOException` попадают в `errors`

### CollectingErrorHandler

- [x] `warning` → WARNING
- [x] `error` → ERROR
- [x] `fatalError` → FATAL
- [x] Методы не прерывают валидацию исключением

### ClasspathResourceResolver

- [x] Реализован `LSResourceResolver` для `import`/`include`
- [x] Разрешение путей относительно `classpath:schemas/`

---

## Этап 3. Реестр и кэш схем

### SchemaRegistry

- [x] При старте сканируется `classpath:schemas/**/*.xsd`
- [x] `schemaId` = имя файла без расширения (`demo.xsd` → `demo`)
- [x] Скомпилированные `Schema` в `ConcurrentHashMap`
- [x] `getSchema(schemaId)` / `listSchemas()` / `exists(schemaId)`
- [x] Ошибка компиляции XSD → fail-fast при старте (`IllegalStateException`)

### Конфигурация

- [x] `xml-validator.schemas-directory` в `application.yml`
- [x] Демо-схема `schemas/demo.xsd`

---

## Этап 4. REST API

### Эндпоинты

| Метод | Путь | Статус |
|-------|------|--------|
| GET | `/api/schemas` | [x] |
| POST | `/api/validate/{schemaId}` | [x] |
| POST | `/api/validate/upload` | [x] (multipart, опционально) |
| GET | `/actuator/health` | [x] |

- [x] `Content-Type: application/xml` / `text/xml`
- [x] Неизвестный `schemaId` → HTTP 404 + `SCHEMA_NOT_FOUND`
- [x] Превышение лимита XML → HTTP 400
- [x] Ответ всегда JSON (`ValidationResponse`)

### Безопасность и лимиты

- [x] `max-file-size` / `max-request-size` = 10 MB
- [x] `xml-validator.max-xml-size-bytes`
- [ ] CORS для внешнего фронта (не нужен — UI на том же origin)
- [x] В логах не пишется полный XML

---

## Этап 5. Минимальный фронт (`static/`)

- [x] `index.html` — одна страница без сборщика
- [x] Dropdown схем из `GET /api/schemas`
- [x] Textarea для XML
- [x] Кнопка «Проверить»
- [x] Зелёный/красный баннер результата
- [x] Таблица ошибок: строка, колонка, уровень, сообщение
- [x] Индикатор «Проверка...»
- [x] Обработка сетевых ошибок
- [x] Загрузка XML из файла
- [x] Кнопка «Пример (valid)»
- [ ] Вкладка «Своя XSD» через upload (отложено)
- [ ] Подсветка строки в textarea по клику на ошибку (отложено)

---

## Этап 6. Тестовые данные и тесты

### Файлы в `src/test/resources/`

- [x] `valid-sample.xml`
- [x] `invalid-order.xml`
- [x] `invalid-required.xml`
- [x] `invalid-enum.xml`

### Тесты

- [x] `XsdValidatorTest` — 4 сценария
- [x] `ValidationControllerTest` — API + 404
- [x] `mvn test` — 8/8

---

## Этап 7. Сборка и запуск

- [x] `mvn spring-boot:run`
- [x] `http://localhost:8080/` — фронт
- [x] README с инструкцией и curl

```bash
curl -X POST "http://localhost:8080/api/validate/demo" \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/valid-sample.xml
```

- [ ] CI-скрипт с exit code по `valid` (отложено)

---

## Этап 8. Деплой

- [ ] `Dockerfile`
- [ ] Переменные окружения для порта и лимитов
- [ ] Health-check в orchestrator

---

## Этап 9. Приёмка

- [x] Сервис не зависит от генератора XML
- [x] XSD в `src/main/resources/schemas/` + перезапуск
- [x] Ответ: `valid: true/false` + line/column/message
- [x] Фронт для ручной проверки
- [x] Тесты зелёные
- [x] README: как добавить схему и вызвать API

---

## Как добавить свою XSD

1. Скопировать `.xsd` в `src/main/resources/schemas/` (можно в подпапку).
2. Убедиться, что связанные `import`/`include` тоже в `schemas/`.
3. `mvn spring-boot:run` — схема появится в dropdown (`id` = имя файла).
4. Вставить XML, выбрать схему, нажать «Проверить».

---

## Отложено (следующие итерации)

- Upload XSD через UI без перезапуска
- Hot-reload схем без рестарта
- Docker-образ
- Schematron / бизнес-правила вне XSD
- XSD 1.1 (Saxon-EE)
