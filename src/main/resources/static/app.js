const schemaSelect = document.getElementById("schemaSelect");
const addSchemaBtn = document.getElementById("addSchemaBtn");
const xsdFile = document.getElementById("xsdFile");
const xmlInput = document.getElementById("xmlInput");
const lineNumbers = document.getElementById("lineNumbers");
const xmlHighlights = document.getElementById("xmlHighlights");
const xmlSyntax = document.getElementById("xmlSyntax");
const xmlEditor = document.getElementById("xmlEditor");
const validateBtn = document.getElementById("validateBtn");
const goToResultBtn = document.getElementById("goToResultBtn");
const loadFileBtn = document.getElementById("loadFileBtn");
const loadSampleBtn = document.getElementById("loadSampleBtn");
const clearXmlBtn = document.getElementById("clearXmlBtn");
const formatXmlBtn = document.getElementById("formatXmlBtn");
const xmlFile = document.getElementById("xmlFile");
const xmlFileName = document.getElementById("xmlFileName");
const statusText = document.getElementById("statusText");
const resultPanel = document.getElementById("resultPanel");
const resultBanner = document.getElementById("resultBanner");
const errorsBlock = document.getElementById("errorsBlock");
const errorsBody = document.getElementById("errorsBody");

let errorLines = new Set();
let activeErrorLine = null;
let syntaxRefreshTimer = null;

const STORAGE_SCHEMA_KEY = "xsd-validator-last-schema-id";
const XML_FILE_PLACEHOLDER = "Документ для проверки";
const EDITOR_MIN_HEIGHT = 420;

const SCHEMA_GROUPS = [
    { key: "docs", label: "docs" },
    { key: "types", label: "types" },
    { key: "smev", label: "smev" },
    { key: "xsd_uip", label: "xsd_uip" },
    { key: "uploaded", label: "uploaded" },
    { key: "other", label: "other" }
];

function classifySchema(schema) {
    const id = schema.id.replace(/\\/g, "/");
    const idLower = id.toLowerCase();

    if (schema.description === "Загружено через UI") {
        return "uploaded";
    }
    if (idLower.includes("/docs/") || id === "demo") {
        return "docs";
    }
    if (idLower.includes("/smev/")) {
        return "smev";
    }
    if (idLower.includes("/xsd_uip/")) {
        return "xsd_uip";
    }
    if (idLower.includes("_types_")
        || idLower.includes("uni_types")
        || idLower.includes("format_album_types")
        || idLower.includes("legalagentdetailstype")
        || idLower.includes("xmldsig")
        || idLower.includes("signinfo")) {
        return "types";
    }
    return "other";
}

function formatSchemaOptionLabel(schema) {
    const fileName = schema.fileName.replace(/\.xsd$/i, "");
    if (schema.id === fileName || schema.id.endsWith("/" + fileName)) {
        return fileName;
    }
    return `${schema.id} (${schema.fileName})`;
}

function populateSchemaSelect(schemas) {
    schemaSelect.innerHTML = "";

    const grouped = Object.fromEntries(SCHEMA_GROUPS.map((group) => [group.key, []]));
    for (const schema of schemas) {
        grouped[classifySchema(schema)].push(schema);
    }

    for (const group of SCHEMA_GROUPS) {
        const items = grouped[group.key];
        if (items.length === 0) {
            continue;
        }

        items.sort((left, right) => formatSchemaOptionLabel(left).localeCompare(
            formatSchemaOptionLabel(right),
            "ru"
        ));

        const optgroup = document.createElement("optgroup");
        optgroup.label = `${group.label} (${items.length})`;
        for (const schema of items) {
            const option = document.createElement("option");
            option.value = schema.id;
            option.textContent = formatSchemaOptionLabel(schema);
            optgroup.appendChild(option);
        }
        schemaSelect.appendChild(optgroup);
    }
}

async function loadSampleXml() {
    const schemaId = schemaSelect.value;
    if (!schemaId) {
        showApiError("Выберите XSD-схему");
        updateResultNav("invalid");
        return;
    }

    loadSampleBtn.disabled = true;
    statusText.textContent = "Генерация шаблона...";

    try {
        const response = await fetch(`/api/schemas/template?schemaId=${encodeURIComponent(schemaId)}`);
        const payload = await readJsonResponse(response);
        if (!response.ok) {
            throw new Error(payload.message || "Не удалось сгенерировать шаблон");
        }

        applyLoadedXml(payload.xml);
        setXmlFileLabel("");
        statusText.textContent = `Шаблон для схемы «${payload.schemaId}» (корень: ${payload.rootElement})`;
    } catch (error) {
        showApiError(error.message);
        statusText.textContent = "";
    } finally {
        loadSampleBtn.disabled = false;
    }
}

async function readJsonResponse(response) {
    const contentType = response.headers.get("content-type") || "";
    if (!contentType.includes("application/json")) {
        const text = await response.text();
        if (text.trimStart().startsWith("<")) {
            throw new Error("Сервер вернул HTML вместо JSON. Проверьте, что приложение запущено на http://localhost:8080");
        }
        throw new Error(text || "Неожиданный ответ сервера");
    }
    return response.json();
}

async function loadSchemas(selectedId) {
    try {
        const response = await fetch("/api/schemas");
        if (!response.ok) {
            throw new Error("Не удалось загрузить список схем");
        }
        const schemas = await readJsonResponse(response);
        if (schemas.length === 0) {
            schemaSelect.innerHTML = "";
            const option = document.createElement("option");
            option.value = "";
            option.textContent = "Схемы не найдены";
            schemaSelect.appendChild(option);
            validateBtn.disabled = true;
            return;
        }
        populateSchemaSelect(schemas);
        const preferredId = selectedId || localStorage.getItem(STORAGE_SCHEMA_KEY);
        if (preferredId && schemas.some((schema) => schema.id === preferredId)) {
            schemaSelect.value = preferredId;
        }
        validateBtn.disabled = false;
    } catch (error) {
        schemaSelect.innerHTML = "<option value=\"\">Ошибка загрузки схем</option>";
        statusText.textContent = error.message;
        validateBtn.disabled = true;
    }
}

async function uploadSchema(files) {
    const formData = new FormData();
    for (const file of files) {
        formData.append("file", file);
    }

    addSchemaBtn.disabled = true;
    statusText.textContent = files.length > 1
        ? `Загрузка ${files.length} XSD...`
        : "Загрузка XSD...";

    try {
        const response = await fetch("/api/schemas", {
            method: "POST",
            body: formData
        });
        const payload = await readJsonResponse(response);
        if (!response.ok) {
            throw new Error(payload.message || "Не удалось добавить XSD");
        }

        const registered = payload.registered || [];
        if (registered.length === 0) {
            throw new Error("Не удалось добавить XSD");
        }

        const lastFileName = files[files.length - 1].name.replace(/\.xsd$/i, "");
        const preferred = registered.find((schema) => schema.id === lastFileName)
            || registered[registered.length - 1];
        await loadSchemas(preferred.id);

        let message = registered.length === 1
            ? `Схема «${registered[0].id}» добавлена`
            : `Добавлено схем: ${registered.length} (${registered.map((schema) => schema.id).join(", ")})`;

        if (payload.warnings && payload.warnings.length > 0) {
            message += `. Предупреждения: ${payload.warnings.join("; ")}`;
        }

        statusText.textContent = message;
    } catch (error) {
        showApiError(error.message);
        statusText.textContent = "";
    } finally {
        addSchemaBtn.disabled = false;
        xsdFile.value = "";
    }
}

function saveLastSchema(schemaId) {
    if (schemaId) {
        localStorage.setItem(STORAGE_SCHEMA_KEY, schemaId);
    }
}

function updateResultNav(status) {
    goToResultBtn.disabled = !status;
    goToResultBtn.classList.remove("valid", "invalid", "secondary");
    if (status === "valid") {
        goToResultBtn.classList.add("valid");
    } else if (status === "invalid") {
        goToResultBtn.classList.add("invalid");
    } else {
        goToResultBtn.classList.add("secondary");
    }
}

function showResult(data) {
    resultPanel.classList.remove("hidden");
    resultBanner.classList.remove("valid", "invalid");
    clearErrorHighlights();

    if (data.valid) {
        resultBanner.classList.add("valid");
        resultBanner.textContent = `Документ валиден (схема: ${data.schemaId}, ${data.durationMs} мс)`;
        errorsBlock.classList.add("hidden");
        errorsBody.innerHTML = "";
        updateResultNav("valid");
        scheduleEditorLayout(() => resetEditorScroll());
        return;
    }

    const xml = xmlInput.value;
    const firstSummary = data.errors.length > 0 ? explainError(data.errors[0].message) : "";

    resultBanner.classList.add("invalid");
    resultBanner.textContent = firstSummary
        ? `Документ не валиден (схема: ${data.schemaId}): ${firstSummary}`
        : `Документ не валиден (схема: ${data.schemaId}, ошибок: ${data.errors.length})`;

    errorsBlock.classList.remove("hidden");
    errorsBody.innerHTML = "";

    const resolvedErrors = data.errors.map((error) => ({
        error,
        location: resolveErrorLocation(xml, error)
    }));

    errorLines = new Set(
        resolvedErrors
            .map((item) => item.location.line)
            .filter((line) => line > 0)
    );

    resolvedErrors.forEach((item, index) => {
        const { error, location } = item;
        const row = document.createElement("tr");
        row.className = "error-row";
        const fragment = formatFragment(xml, location.line, location.column);
        const summary = explainError(error.message);
        const where = formatWhere(error, location);
        const severityClass = `severity-${(error.severity || "ERROR").toLowerCase()}`;

        row.innerHTML = `
            <td>${index + 1}</td>
            <td class="where">${escapeHtml(where)}</td>
            <td class="fragment">${fragment}</td>
            <td class="summary ${severityClass}">${escapeHtml(summary)}</td>
            <td class="technical message">${escapeHtml(error.message)}</td>
        `;

        row.addEventListener("click", () => {
            for (const activeRow of errorsBody.querySelectorAll(".error-row.active")) {
                activeRow.classList.remove("active");
            }
            row.classList.add("active");
            focusXmlLocation(location.line, location.column);
        });

        errorsBody.appendChild(row);
    });

    const firstResolved = resolvedErrors
        .filter((item) => item.location.line > 0)
        .sort((left, right) => left.location.line - right.location.line)[0];

    updateResultNav("invalid");

    scheduleEditorLayout(() => {
        if (firstResolved) {
            focusXmlLocation(firstResolved.location.line, firstResolved.location.column);
            resolvedErrors.forEach((item, index) => {
                if (item.location.line === firstResolved.location.line
                    && item.error.message === firstResolved.error.message) {
                    errorsBody.children[index]?.classList.add("active");
                }
            });
        } else {
            refreshXmlEditorChrome();
        }
    });
}

function clearErrorHighlights() {
    errorLines = new Set();
    activeErrorLine = null;
    refreshXmlEditorChrome();
}

function clearValidationResult() {
    resultPanel.classList.add("hidden");
    resultBanner.classList.remove("valid", "invalid");
    resultBanner.textContent = "";
    errorsBlock.classList.add("hidden");
    errorsBody.innerHTML = "";
    updateResultNav(null);
}

function getEditorLineHeight() {
    const styles = getComputedStyle(xmlInput);
    const lineHeight = Number.parseFloat(styles.lineHeight);
    return Number.isFinite(lineHeight) ? lineHeight : 21;
}

function getEditorPaddingTop() {
    return Number.parseFloat(getComputedStyle(xmlInput).paddingTop) || 10;
}

function getEditorPaddingBottom() {
    return Number.parseFloat(getComputedStyle(xmlInput).paddingBottom) || 10;
}

function getEditorMaxHeight() {
    return Math.min(Math.floor(window.innerHeight * 0.9), 1200);
}

function measureEditorContentHeight() {
    const lineCount = Math.max(xmlInput.value.split(/\r?\n/).length, 1);
    const lineHeight = getEditorLineHeight();
    const verticalPadding = getEditorPaddingTop() + getEditorPaddingBottom();
    return Math.ceil(verticalPadding + lineCount * lineHeight + 2);
}

function fitEditorToContent() {
    const contentHeight = measureEditorContentHeight();
    const maxHeight = getEditorMaxHeight();
    const fitted = Math.min(maxHeight, Math.max(EDITOR_MIN_HEIGHT, contentHeight));
    xmlEditor.style.height = `${fitted}px`;
}

function scheduleEditorLayout(callback) {
    requestAnimationFrame(() => {
        fitEditorToContent();
        refreshXmlEditorChrome();
        clearTimeout(syntaxRefreshTimer);
        refreshXmlSyntax();
        if (callback) {
            callback();
        } else {
            resetEditorScroll();
        }
    });
}

function resetEditorScroll() {
    xmlInput.scrollTop = 0;
    syncEditorScroll();
}

function resetEditorLayout() {
    xmlEditor.style.height = "";
    resetEditorScroll();
}

function setXmlFileLabel(fileName) {
    const normalized = fileName?.trim() || "";
    xmlFileName.textContent = normalized || XML_FILE_PLACEHOLDER;
    xmlFileName.classList.toggle("is-placeholder", !normalized);
    xmlFileName.title = normalized;
}

function normalizeLoadedXml(xml) {
    return String(xml)
        .replace(/^\uFEFF/, "")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n")
        .trimEnd();
}

function applyLoadedXml(xml) {
    xmlInput.value = normalizeLoadedXml(xml);
    errorLines = new Set();
    activeErrorLine = null;
    scheduleEditorLayout(() => resetEditorScroll());
}

async function requestXmlFormat(xml) {
    const response = await fetch("/api/xml/format", {
        method: "POST",
        headers: {
            "Content-Type": "application/xml; charset=utf-8"
        },
        body: xml
    });

    if (!response.ok) {
        const payload = await readJsonResponse(response);
        throw new Error(payload.message || "Не удалось отформатировать XML");
    }

    return response.text();
}

async function loadXmlFromFile(file) {
    const normalized = normalizeLoadedXml(await file.text());
    setXmlFileLabel(file.name);
    statusText.textContent = "Загрузка и форматирование...";

    try {
        applyLoadedXml(await requestXmlFormat(normalized.trim()));
        statusText.textContent = `Файл «${file.name}» загружен и отформатирован`;
        showEditorPreviewMode();
    } catch (error) {
        applyLoadedXml(normalized);
        showEditorPreviewMode();
        statusText.textContent = `Файл «${file.name}» загружен`;
        if (normalized.trim()) {
            statusText.textContent += " (автоформатирование не выполнено)";
        }
    }
}

function updateEditorOverlayHeight() {
    const contentHeight = measureEditorContentHeight();
    xmlHighlights.style.height = `${contentHeight}px`;
    xmlSyntax.style.minHeight = `${contentHeight}px`;
}

function refreshXmlEditorChrome() {
    const lines = xmlInput.value.split(/\r?\n/);
    const lineCount = Math.max(lines.length, 1);
    const digits = String(lineCount).length;

    lineNumbers.style.minWidth = `${Math.max(3.2, digits + 1.2)}rem`;
    lineNumbers.innerHTML = "";

    for (let line = 1; line <= lineCount; line++) {
        const number = document.createElement("div");
        number.className = "line-number";
        if (errorLines.has(line)) {
            number.classList.add("error-line");
        }
        if (line === activeErrorLine) {
            number.classList.add("active-error");
        }
        number.textContent = line;
        lineNumbers.appendChild(number);
    }

    xmlHighlights.innerHTML = "";
    const lineHeight = getEditorLineHeight();
    const paddingTop = getEditorPaddingTop();

    for (const line of errorLines) {
        const highlight = document.createElement("div");
        highlight.className = "line-highlight";
        if (line === activeErrorLine) {
            highlight.classList.add("active");
        }
        highlight.style.top = `${paddingTop + (line - 1) * lineHeight}px`;
        highlight.style.height = `${lineHeight}px`;
        xmlHighlights.appendChild(highlight);
    }

    updateEditorOverlayHeight();
    syncEditorScroll();
    scheduleXmlSyntaxRefresh();
}

function scheduleXmlSyntaxRefresh() {
    clearTimeout(syntaxRefreshTimer);
    syntaxRefreshTimer = setTimeout(refreshXmlSyntax, 40);
}

function syncSyntaxLayerGeometry() {
    const styles = getComputedStyle(xmlInput);
    xmlSyntax.style.padding = styles.padding;
    xmlSyntax.style.width = `${xmlInput.scrollWidth}px`;
}

function setEditorEditingMode(editing) {
    xmlInput.classList.toggle("is-editing", editing);
    xmlSyntax.style.visibility = "visible";
    refreshXmlSyntax();
}

function showEditorPreviewMode() {
    if (document.activeElement === xmlInput) {
        xmlInput.blur();
    }
    setEditorEditingMode(false);
}

function refreshXmlSyntax() {
    const value = xmlInput.value;
    xmlSyntax.textContent = "";
    xmlSyntax.innerHTML = value ? highlightXmlSyntax(value) : "";
    updateEditorOverlayHeight();
    syncSyntaxLayerGeometry();
    syncEditorScroll();
}

function findTagEnd(text, start) {
    let quote = null;
    for (let index = start + 1; index < text.length; index++) {
        const character = text[index];
        if (quote) {
            if (character === quote) {
                quote = null;
            }
            continue;
        }
        if (character === "\"" || character === "'") {
            quote = character;
            continue;
        }
        if (character === ">") {
            return index;
        }
    }
    return -1;
}

function highlightXmlSyntax(text) {
    let html = "";
    let index = 0;

    while (index < text.length) {
        if (text.startsWith("<!--", index)) {
            const end = text.indexOf("-->", index + 4);
            const close = end === -1 ? text.length : end + 3;
            html += `<span class="xml-comment">${escapeHtml(text.slice(index, close))}</span>`;
            index = close;
            continue;
        }

        if (text.startsWith("<?", index)) {
            const end = text.indexOf("?>", index + 2);
            const close = end === -1 ? text.length : end + 2;
            html += `<span class="xml-pi">${escapeHtml(text.slice(index, close))}</span>`;
            index = close;
            continue;
        }

        if (text.startsWith("<![CDATA[", index)) {
            const end = text.indexOf("]]>", index + 9);
            const close = end === -1 ? text.length : end + 3;
            html += `<span class="xml-cdata">${escapeHtml(text.slice(index, close))}</span>`;
            index = close;
            continue;
        }

        if (text[index] === "<") {
            const end = findTagEnd(text, index);
            if (end === -1) {
                html += escapeHtml(text.slice(index));
                break;
            }
            html += highlightXmlTag(text.slice(index, end + 1));
            index = end + 1;
            continue;
        }

        const nextTag = text.indexOf("<", index);
        const sliceEnd = nextTag === -1 ? text.length : nextTag;
        html += escapeHtml(text.slice(index, sliceEnd));
        index = sliceEnd;
    }

    return html;
}

function highlightXmlTag(tag) {
    if (tag.length < 2 || tag[0] !== "<") {
        return escapeHtml(tag);
    }

    const isClose = tag.startsWith("</");
    const isSelfClosing = tag.endsWith("/>");
    const contentEnd = isSelfClosing ? tag.length - 2 : tag.length - 1;
    let position = isClose ? 2 : 1;

    const nameMatch = tag.slice(position).match(/^([^\s>\/]+)/u);
    if (!nameMatch) {
        return escapeHtml(tag);
    }

    const tagName = nameMatch[1];
    position += tagName.length;

    let html = `<span class="xml-bracket">${escapeHtml(isClose ? "</" : "<")}</span>`;
    html += `<span class="xml-tag-name">${escapeHtml(tagName)}</span>`;
    html += highlightXmlAttributes(tag.slice(position, contentEnd));

    if (isSelfClosing) {
        html += `<span class="xml-bracket">/></span>`;
    } else {
        html += `<span class="xml-bracket">></span>`;
    }

    return html;
}

function highlightXmlAttributes(attributes) {
    const pattern = /(\s+)([^\s=/>]+)(\s*=\s*)("([^"]*)"|'([^']*)')/gu;
    let html = "";
    let lastIndex = 0;
    let match;

    while ((match = pattern.exec(attributes)) !== null) {
        html += escapeHtml(attributes.slice(lastIndex, match.index));
        const quotedValue = match[4];
        html += `${escapeHtml(match[1])}<span class="xml-attr-name">${escapeHtml(match[2])}</span>`;
        html += `<span class="xml-attr-eq">${escapeHtml(match[3])}</span>`;
        html += `<span class="xml-attr-value">${escapeHtml(quotedValue)}</span>`;
        lastIndex = pattern.lastIndex;
    }

    html += escapeHtml(attributes.slice(lastIndex));
    return html;
}

function syncEditorScroll() {
    const offsetY = xmlInput.scrollTop;
    const offsetX = xmlInput.scrollLeft;
    lineNumbers.style.transform = `translateY(-${offsetY}px)`;
    xmlHighlights.style.transform = `translate(${-offsetX}px, ${-offsetY}px)`;
    xmlSyntax.style.transform = `translate(${-offsetX}px, ${-offsetY}px)`;
    syncSyntaxLayerGeometry();
}

function formatWhere(error, location) {
    const line = location?.line > 0 ? location.line : (error.line > 0 ? error.line : "?");
    const column = location?.column > 0 ? location.column : (error.column > 0 ? error.column : "?");
    if (location?.adjusted && error.line > 0 && error.line !== line) {
        return `стр. ${line}, кол. ${column} (в логе: ${error.line})`;
    }
    return `стр. ${line}, кол. ${column}`;
}

function extractElementNameFromMessage(message) {
    if (!message) {
        return null;
    }
    const match = message.match(/element '([^']+)'/i);
    return match ? match[1] : null;
}

function escapeRegex(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function lineContainsElement(line, elementName) {
    const localName = elementName.includes(":") ? elementName.split(":").pop() : elementName;
    return line.includes(`<${elementName}>`)
        || line.includes(`<${elementName} `)
        || line.includes(`<${elementName}/>`)
        || line.includes(`</${elementName}>`)
        || line.includes(`<${localName}>`)
        || line.includes(`<${localName} `)
        || line.includes(`</${localName}>`);
}

function isEmptyElementLine(line, elementName) {
    const compact = line.replace(/\s/g, "");
    const localName = elementName.includes(":") ? elementName.split(":").pop() : elementName;
    const names = [elementName, localName];

    for (const name of names) {
        const escaped = escapeRegex(name);
        if (new RegExp(`<${escaped}>\\s*</${escaped}>|<${escaped}/>`).test(compact)) {
            return true;
        }
    }
    return false;
}

function findElementLineInXml(xml, elementName, message) {
    if (!elementName) {
        return null;
    }

    const lines = xml.split(/\r?\n/);
    const emptyMatches = [];
    const allMatches = [];
    const wantsEmpty = message.includes("minLength")
        || message.includes("Value ''")
        || message.includes("not facet-valid")
        || message.includes("cvc-type.3.1.3");

    for (let index = 0; index < lines.length; index++) {
        const line = lines[index];
        if (!lineContainsElement(line, elementName)) {
            continue;
        }

        const lineNumber = index + 1;
        const tagIndex = line.indexOf(`<${elementName}>`);
        const localName = elementName.includes(":") ? elementName.split(":").pop() : elementName;
        const localTagIndex = line.indexOf(`<${localName}>`);
        const column = Math.max(1, (tagIndex >= 0 ? tagIndex : localTagIndex) + 1);
        const location = { line: lineNumber, column };

        if (isEmptyElementLine(line, elementName)) {
            emptyMatches.push(location);
            continue;
        }

        if (line.includes(`<${elementName}>`) && !line.includes(`</${elementName}>`)) {
            const nextLine = lines[index + 1] || "";
            if (nextLine.trim() === `</${elementName}>` || nextLine.trim().startsWith(`</${elementName}>`)) {
                emptyMatches.push(location);
                continue;
            }
        }

        allMatches.push(location);
    }

    if (wantsEmpty && emptyMatches.length > 0) {
        return emptyMatches[0];
    }

    return allMatches[0] || null;
}

function resolveErrorLocation(xml, error) {
    const elementName = extractElementNameFromMessage(error.message);
    if (elementName) {
        const found = findElementLineInXml(xml, elementName, error.message);
        if (found) {
            return {
                line: found.line,
                column: found.column,
                adjusted: error.line > 0 && error.line !== found.line
            };
        }
    }

    return {
        line: error.line,
        column: error.column,
        adjusted: false
    };
}

function formatFragment(xml, line, column) {
    const lineText = getXmlLine(xml, line);
    if (!lineText) {
        return '<span class="muted">—</span>';
    }

    const trimmed = lineText.trim();
    const display = trimmed.length > 120 ? trimmed.slice(0, 117) + "..." : trimmed;
    const leadingSpaces = lineText.length - lineText.trimStart().length;
    const markerColumn = Math.max(1, column - leadingSpaces);

    if (markerColumn > 0 && markerColumn <= display.length) {
        const before = escapeHtml(display.slice(0, markerColumn - 1));
        const at = escapeHtml(display.charAt(markerColumn - 1) || " ");
        const after = escapeHtml(display.slice(markerColumn));
        return `${before}<mark>${at}</mark>${after}`;
    }

    return escapeHtml(display);
}

function getXmlLine(xml, lineNumber) {
    if (!lineNumber || lineNumber < 1) {
        return null;
    }
    const lines = xml.split(/\r?\n/);
    return lineNumber <= lines.length ? lines[lineNumber - 1] : null;
}

function focusXmlLocation(lineNumber, columnNumber) {
    if (!lineNumber || lineNumber < 1) {
        return;
    }

    const text = xmlInput.value;
    const lines = text.split(/\r?\n/);
    if (lineNumber > lines.length) {
        return;
    }

    let lineStart = 0;
    for (let i = 0; i < lineNumber - 1; i++) {
        lineStart += lines[i].length + 1;
    }

    const lineText = lines[lineNumber - 1];
    const lineEnd = lineStart + lineText.length;
    const column = columnNumber > 0 ? columnNumber : 1;
    const charIndex = Math.min(lineStart + column - 1, lineEnd);

    activeErrorLine = lineNumber;
    refreshXmlEditorChrome();

    xmlInput.focus();
    xmlInput.setSelectionRange(charIndex, Math.min(charIndex + 1, lineEnd));

    const lineHeight = getEditorLineHeight();
    xmlInput.scrollTop = Math.max(0, (lineNumber - 1) * lineHeight - xmlInput.clientHeight / 3);
    syncEditorScroll();
}

function explainError(message) {
    if (!message) {
        return "Ошибка валидации";
    }

    const elementMatch = message.match(/element '([^']+)'/i);
    const elementName = elementMatch ? elementMatch[1] : null;
    const valueMatch = message.match(/value '([^']+)'/i);
    const value = valueMatch ? valueMatch[1] : null;

    if (message.includes("cvc-elt.1.a")) {
        return elementName
            ? `Элемент «${elementName}» не объявлен в выбранной XSD. Скорее всего выбрана схема типов, а нужна схема документа из docs/.`
            : "Корневой или вложенный элемент не объявлен в выбранной XSD.";
    }
    if (message.includes("cvc-complex-type.2.4.a")) {
        return elementName
            ? `Недопустимый дочерний элемент «${elementName}» или нарушен порядок элементов.`
            : "Недопустимый дочерний элемент или нарушен порядок элементов.";
    }
    if (message.includes("cvc-complex-type.2.4.b")) {
        return elementName
            ? `Отсутствует обязательный элемент «${elementName}».`
            : "Отсутствует обязательный дочерний элемент.";
    }
    if (message.includes("cvc-enumeration-valid")) {
        return value
            ? `Недопустимое значение «${value}» — его нет в перечислении (enum) схемы.`
            : "Значение не входит в допустимый список (enum).";
    }
    if (message.includes("cvc-pattern-valid")) {
        return "Значение не соответствует шаблону (pattern), заданному в XSD.";
    }
    if (message.includes("cvc-maxLength-valid")) {
        return "Слишком длинное значение — превышена максимальная длина по XSD.";
    }
    if (message.includes("cvc-minLength-valid")) {
        return "Слишком короткое значение — не достигнута минимальная длина по XSD.";
    }
    if (message.includes("cvc-datatype-valid")) {
        return "Неверный тип данных: значение не соответствует типу, заданному в XSD.";
    }
    if (message.includes("cvc-attribute.3")) {
        return elementName
            ? `Атрибут «${elementName}» не разрешён для этого элемента.`
            : "Недопустимый атрибут.";
    }
    if (message.includes("cvc-minInclusive-valid") || message.includes("cvc-maxInclusive-valid")) {
        return "Числовое значение вне допустимого диапазона по XSD.";
    }

    return elementName
        ? `Проблема с элементом «${elementName}».`
        : "Ошибка структуры или значения XML по XSD.";
}

function showApiError(message) {
    resultPanel.classList.remove("hidden");
    resultBanner.classList.remove("valid");
    resultBanner.classList.add("invalid");
    resultBanner.textContent = message;
    errorsBlock.classList.add("hidden");
}

function escapeHtml(text) {
    return text
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;");
}

async function validateXml() {
    const schemaId = schemaSelect.value;
    const xml = xmlInput.value.trim();

    if (!schemaId) {
        showApiError("Выберите XSD-схему");
        updateResultNav("invalid");
        return;
    }
    if (!xml) {
        showApiError("Введите XML-документ");
        updateResultNav("invalid");
        return;
    }

    saveLastSchema(schemaId);
    validateBtn.disabled = true;
    statusText.textContent = "Проверка...";

    try {
        const response = await fetch(`/api/validate?schemaId=${encodeURIComponent(schemaId)}`, {
            method: "POST",
            headers: {
                "Content-Type": "application/xml; charset=utf-8"
            },
            body: xml
        });

        const payload = await readJsonResponse(response);
        if (!response.ok) {
            showApiError(payload.message || "Ошибка запроса");
            updateResultNav("invalid");
            return;
        }

        showResult(payload);
        statusText.textContent = "";
    } catch (error) {
        showApiError("Сервис недоступен: " + error.message);
        updateResultNav("invalid");
        statusText.textContent = "";
    } finally {
        validateBtn.disabled = false;
    }
}

loadFileBtn.addEventListener("click", () => xmlFile.click());

addSchemaBtn.addEventListener("click", () => xsdFile.click());

xsdFile.addEventListener("change", async (event) => {
    const files = Array.from(event.target.files || []);
    if (files.length === 0) {
        return;
    }

    const invalid = files.find((file) => !file.name.toLowerCase().endsWith(".xsd"));
    if (invalid) {
        showApiError("Все файлы должны иметь расширение .xsd");
        xsdFile.value = "";
        return;
    }

    await uploadSchema(files);
});

xmlFile.addEventListener("change", async (event) => {
    const file = event.target.files[0];
    if (!file) {
        return;
    }
    await loadXmlFromFile(file);
    xmlFile.value = "";
});

async function formatCurrentXml(successMessage = "XML отформатирован") {
    const xml = xmlInput.value.trim();
    if (!xml) {
        statusText.textContent = "Нечего форматировать";
        return false;
    }

    formatXmlBtn.disabled = true;
    statusText.textContent = "Форматирование...";

    try {
        applyLoadedXml(await requestXmlFormat(xml));
        statusText.textContent = successMessage;
        showEditorPreviewMode();
        return true;
    } catch (error) {
        showApiError(`Не удалось отформатировать XML: ${error.message}`);
        statusText.textContent = "";
        throw error;
    } finally {
        formatXmlBtn.disabled = false;
    }
}

async function formatXmlInput() {
    try {
        await formatCurrentXml("XML отформатирован");
    } catch (error) {
        // formatCurrentXml already updated UI state
    }
}

loadSampleBtn.addEventListener("click", loadSampleXml);

clearXmlBtn.addEventListener("click", () => {
    xmlInput.value = "";
    xmlFile.value = "";
    setXmlFileLabel("");
    clearErrorHighlights();
    clearValidationResult();
    resetEditorLayout();
    refreshXmlEditorChrome();
    clearTimeout(syntaxRefreshTimer);
    refreshXmlSyntax();
    statusText.textContent = "";
});

formatXmlBtn.addEventListener("click", formatXmlInput);

xmlInput.addEventListener("focus", () => setEditorEditingMode(true));
xmlInput.addEventListener("blur", () => setEditorEditingMode(false));

xmlInput.addEventListener("input", () => {
    if (errorLines.size > 0) {
        clearErrorHighlights();
        for (const row of errorsBody.querySelectorAll(".error-row.active")) {
            row.classList.remove("active");
        }
    }
    refreshXmlEditorChrome();
});

xmlInput.addEventListener("scroll", syncEditorScroll);

window.addEventListener("resize", () => {
    syncSyntaxLayerGeometry();
    syncEditorScroll();
});

validateBtn.addEventListener("click", validateXml);

goToResultBtn.addEventListener("click", () => {
    resultPanel.scrollIntoView({ behavior: "smooth", block: "start" });
});

schemaSelect.addEventListener("change", () => {
    saveLastSchema(schemaSelect.value);
});

refreshXmlEditorChrome();
loadSchemas();
