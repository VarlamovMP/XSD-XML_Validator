# XSD XML Validator

Отдельный Spring Boot сервис для проверки XML против XSD-схем. Есть веб-интерфейс и REST API.

## Быстрый старт

```bash
cd C:\1520\XSD-XML_Validator
mvn spring-boot:run
```

Откройте в браузере: [http://localhost:8080](http://localhost:8080)

Если порт 8080 занят:

```bash
netstat -ano | findstr :8080
taskkill /PID <номер_процесса> /F
```

## Веб-интерфейс

1. **Схема (XSD)** — выбор из загруженных при старте или добавленных через UI. Последняя использованная схема запоминается в браузере.
2. **Добавить XSD** — загрузка своих схем без перезапуска (хранятся в памяти). При `import`/`include` выберите все связанные файлы (Ctrl+клик).
3. **XML-документ** — ввод, загрузка файла или генерация каркаса кнопкой **«Валид»** по выбранной схеме.
4. **Проверить** — валидация XML против выбранной XSD.
5. **К результату** — переход к блоку результата (зелёная кнопка при успехе, красная при ошибке).

### Кнопка «Валид»

Генерирует **структурный шаблон** XML для выбранной схемы:

- корневой элемент и вся вложенность по XSD;
- в листовых полях — заглушки по имени элемента в верхнем регистре (`Имя` → `ИМЯ`, `Дата` → `ДАТА`);
- подтягиваются зависимости (`import`/`include`).

Шаблон не гарантирует успешную валидацию: значения-заглушки могут не проходить `pattern`, `enumeration`, форматы дат и т.п. Для документов выбирайте XSD из `docs/`, а не файлы типов (`*_types_*`).

### Подсветка ошибок

- нумерация строк в редакторе XML;
- подсветка строк с ошибками;
- таблица ошибок с пояснениями на русском;
- клик по строке ошибки прокручивает и подсвечивает место в XML.

## Как добавить XSD

### Через UI

Кнопка **«Добавить XSD»** на главной странице. `schemaId` = имя файла без `.xsd` (например `my-schema.xsd` → `my-schema`).

### В classpath (постоянно)

1. Положите файл в `src/main/resources/schemas/` (можно во вложенные папки).
2. `schemaId` = путь от `schemas/` без `.xsd` (например `xsd/docs/loan_information_request_2024-01-01.xsd` → `xsd/docs/loan_information_request_2024-01-01`).
3. Перезапустите приложение.

Если XSD использует `xs:import` / `xs:include`, связанные файлы тоже должны лежать в `schemas/`.

При старте битые XSD пропускаются с предупреждением в логе — приложение не падает.

## API

### Список схем

```bash
curl http://localhost:8080/api/schemas
```

### Загрузка XSD (multipart)

```bash
curl -X POST http://localhost:8080/api/schemas ^
  -F "file=@schemas\demo.xsd"
```

Несколько файлов (для зависимостей):

```bash
curl -X POST http://localhost:8080/api/schemas ^
  -F "file=@uni_types.xsd" ^
  -F "file=@main.xsd"
```

### Шаблон XML по схеме

```bash
curl "http://localhost:8080/api/schemas/template?schemaId=demo"
```

Ответ:

```json
{
  "schemaId": "demo",
  "rootElement": "order",
  "xml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<order ...>...</order>\n"
}
```

Для `schemaId` с `/` используйте query-параметр (как в примере выше).

### Валидация XML

Простой `schemaId` (без `/`):

```bash
curl -X POST "http://localhost:8080/api/validate/demo" ^
  -H "Content-Type: application/xml" ^
  --data-binary @src\test\resources\valid-sample.xml
```

`schemaId` с путём — через query-параметр:

```bash
curl -X POST "http://localhost:8080/api/validate?schemaId=xsd/docs/loan_information_request_2024-01-01" ^
  -H "Content-Type: application/xml" ^
  --data-binary @document.xml
```

Ответ при успехе:

```json
{
  "valid": true,
  "schemaId": "demo",
  "errors": [],
  "durationMs": 12
}
```

При ошибках:

```json
{
  "valid": false,
  "schemaId": "demo",
  "errors": [
    {
      "line": 3,
      "column": 6,
      "message": "cvc-complex-type.2.4.a: ...",
      "severity": "ERROR"
    }
  ],
  "durationMs": 8
}
```

### Валидация через multipart

```bash
curl -X POST http://localhost:8080/api/validate/upload ^
  -F "schemaId=demo" ^
  -F "xml=@src\test\resources\valid-sample.xml"
```

## Демо-схема

В проекте есть `schemas/demo.xsd`. Для проверки используйте тестовый XML `src/test/resources/valid-sample.xml` или сгенерируйте шаблон кнопкой **«Валид»** при выбранной схеме `demo`.

Тестовые XML в `src/test/resources/`:

| Файл | Ожидание |
|------|----------|
| `valid-sample.xml` | valid |
| `invalid-required.xml` | отсутствует обязательный `id` |
| `invalid-order.xml` | нарушен порядок элементов |
| `invalid-enum.xml` | неверное значение enum |

## Тесты

```bash
mvn test
```

## Конфигурация

`src/main/resources/application.yml`:

| Параметр | По умолчанию | Описание |
|----------|--------------|----------|
| `server.port` | `8080` | Порт HTTP |
| `xml-validator.schemas-directory` | `classpath:schemas` | Папка со схемами |
| `xml-validator.max-xml-size-bytes` | `10485760` (10 MB) | Лимит размера XML/XSD |

## Health check

```bash
curl http://localhost:8080/actuator/health
```
