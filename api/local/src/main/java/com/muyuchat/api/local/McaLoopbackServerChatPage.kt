package com.muyuchat.api.local

/**
 * The built-in Local API chat page served at GET / and GET /index.html.
 *
 * Moved verbatim out of [McaLoopbackServer]. The HTML is one raw string literal, so it is
 * byte-for-byte what the server always served; only the declaration moved.
 */
internal fun mcaLocalApiChatPageHtml(): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MCA Web Chat</title>
            <style>
                :root {
                    color-scheme: light;
                    --primary: #1a73e8;
                    --primary-soft: #e8f0fe;
                    --bg: #f8f9fa;
                    --surface: #ffffff;
                    --text: #202124;
                    --muted: #5f6368;
                    --line: #e5e7eb;
                    --danger: #d93025;
                }
                * { box-sizing: border-box; }
                body {
                    margin: 0;
                    background: var(--bg);
                    color: var(--text);
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
                }
                .app {
                    max-width: 900px;
                    height: 100vh;
                    margin: 0 auto;
                    display: flex;
                    flex-direction: column;
                    background: var(--surface);
                    border-left: 1px solid var(--line);
                    border-right: 1px solid var(--line);
                }
                header {
                    padding: 14px 18px;
                    border-bottom: 1px solid var(--line);
                    display: grid;
                    grid-template-columns: 1fr auto;
                    gap: 12px;
                    align-items: center;
                }
                h1 { margin: 0; font-size: 18px; }
                .sub { margin-top: 4px; color: var(--muted); font-size: 12px; }
                .keybar {
                    display: flex;
                    gap: 8px;
                    align-items: center;
                    flex-wrap: wrap;
                    justify-content: flex-end;
                }
                input, textarea, button {
                    font: inherit;
                }
                input {
                    width: 260px;
                    padding: 9px 10px;
                    border: 1px solid var(--line);
                    border-radius: 10px;
                    outline: none;
                }
                input:focus, textarea:focus { border-color: var(--primary); }
                button {
                    border: 0;
                    border-radius: 10px;
                    padding: 9px 14px;
                    background: var(--primary);
                    color: white;
                    cursor: pointer;
                    font-weight: 600;
                }
                button.secondary {
                    background: var(--primary-soft);
                    color: var(--primary);
                }
                button.danger {
                    background: #fce8e6;
                    color: var(--danger);
                }
                button:disabled {
                    opacity: .55;
                    cursor: not-allowed;
                }
                .messages {
                    flex: 1;
                    overflow: auto;
                    padding: 18px;
                    display: flex;
                    flex-direction: column;
                    gap: 14px;
                }
                .msg {
                    max-width: 78%;
                    padding: 12px 14px;
                    border-radius: 16px;
                    white-space: pre-wrap;
                    line-height: 1.55;
                    word-break: break-word;
                }
                .user {
                    align-self: flex-end;
                    background: var(--primary);
                    color: white;
                    border-bottom-right-radius: 4px;
                }
                .assistant {
                    align-self: flex-start;
                    background: #f1f3f4;
                    color: var(--text);
                    border-bottom-left-radius: 4px;
                }
                .reasoning {
                    align-self: flex-start;
                    max-width: 78%;
                    color: var(--muted);
                    background: transparent;
                    border-left: 3px solid var(--line);
                    padding: 4px 0 4px 12px;
                    font-size: 13px;
                    line-height: 1.55;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                .reasoning summary {
                    cursor: pointer;
                    font-weight: 600;
                    color: var(--muted);
                    margin-bottom: 6px;
                }
                .empty {
                    margin: auto;
                    text-align: center;
                    color: var(--muted);
                    max-width: 520px;
                }
                .composer {
                    border-top: 1px solid var(--line);
                    padding: 14px;
                    display: grid;
                    grid-template-columns: 1fr auto auto auto;
                    gap: 10px;
                    align-items: end;
                    background: var(--surface);
                }
                .compose-main {
                    display: grid;
                    gap: 8px;
                }
                .attachments {
                    display: flex;
                    gap: 8px;
                    overflow-x: auto;
                }
                .attachment {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    max-width: 220px;
                    padding: 6px 8px;
                    border-radius: 999px;
                    background: var(--primary-soft);
                    color: var(--primary);
                    font-size: 12px;
                    font-weight: 600;
                }
                .attachment img {
                    width: 24px;
                    height: 24px;
                    border-radius: 7px;
                    object-fit: cover;
                }
                .attachment span {
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .attachment button {
                    width: 22px;
                    height: 22px;
                    padding: 0;
                    border-radius: 50%;
                    background: rgba(26, 115, 232, .12);
                    color: var(--primary);
                    font-weight: 700;
                }
                textarea {
                    width: 100%;
                    min-height: 54px;
                    max-height: 180px;
                    resize: vertical;
                    border: 1px solid var(--line);
                    border-radius: 14px;
                    padding: 12px;
                    outline: none;
                }
                .status {
                    display: inline-flex;
                    gap: 6px;
                    align-items: center;
                    padding: 5px 8px;
                    border-radius: 999px;
                    background: var(--primary-soft);
                    color: var(--primary);
                    font-size: 12px;
                    font-weight: 600;
                }
                .dot {
                    width: 7px;
                    height: 7px;
                    border-radius: 50%;
                    background: #34a853;
                }
                @media (max-width: 700px) {
                    header { grid-template-columns: 1fr; }
                    .keybar { justify-content: stretch; }
                    input { width: 100%; }
                    .composer { grid-template-columns: 1fr; }
                    .msg { max-width: 92%; }
                }
            </style>
        </head>
        <body>
            <div class="app">
                <header>
                    <div>
                        <h1>MCA Web Chat</h1>
                        <div class="sub">
                            <span class="status"><span class="dot"></span><span id="modelText">连接中...</span></span>
                            <span id="apiText"> 请填写 MCA 页面显示的 API Key</span>
                        </div>
                    </div>
                    <div class="keybar">
                        <input id="apiKey" type="password" placeholder="API Key">
                        <button class="secondary" id="saveKey">保存 Key</button>
                        <button class="secondary" id="clearChat">清空</button>
                    </div>
                </header>
                <main id="messages" class="messages">
                    <div class="empty">
                        在手机 MCA 中开启“开放端口”后，这个页面就可以从电脑访问手机端本地模型。<br>
                        这里不会调用云端推理，消息会发送到当前手机上的 MCA 服务。
                    </div>
                </main>
                <div class="composer">
                    <div class="compose-main">
                        <div id="attachments" class="attachments"></div>
                        <textarea id="input" placeholder="输入消息，Enter 发送，Shift + Enter 换行"></textarea>
                    </div>
                    <button class="secondary" id="pickImage">图片</button>
                    <button id="send">发送</button>
                    <button id="stop" class="danger" disabled>停止</button>
                    <input id="imageInput" type="file" accept="image/*" multiple hidden>
                </div>
            </div>
            <script>
                var apiKeyInput = document.getElementById('apiKey');
                var messagesEl = document.getElementById('messages');
                var inputEl = document.getElementById('input');
                var sendBtn = document.getElementById('send');
                var stopBtn = document.getElementById('stop');
                var pickImageBtn = document.getElementById('pickImage');
                var imageInput = document.getElementById('imageInput');
                var attachmentsEl = document.getElementById('attachments');
                var modelText = document.getElementById('modelText');
                var apiText = document.getElementById('apiText');
                var controller = null;
                var chatMessages = [];
                var pendingImages = [];
                var maxImageBytes = 8 * 1024 * 1024;
                var maxImagesPerTurn = 4;

                apiKeyInput.value = localStorage.getItem('mca_api_key') || '';

                document.getElementById('saveKey').onclick = function () {
                    localStorage.setItem('mca_api_key', apiKeyInput.value.trim());
                    loadModels();
                };
                document.getElementById('clearChat').onclick = function () {
                    chatMessages = [];
                    pendingImages = [];
                    renderAttachments();
                    messagesEl.innerHTML = '<div class="empty">对话已清空。</div>';
                };
                stopBtn.onclick = async function () {
                    if (controller) controller.abort();
                    await fetch('/v1/generate/stop', {
                        method: 'POST',
                        headers: authHeaders()
                    }).catch(function () {});
                    setBusy(false);
                };
                sendBtn.onclick = sendMessage;
                pickImageBtn.onclick = function () {
                    if (!sendBtn.disabled) imageInput.click();
                };
                imageInput.onchange = async function () {
                    var files = Array.prototype.slice.call(imageInput.files || []);
                    for (var i = 0; i < files.length; i++) {
                        await addImageFile(files[i]);
                    }
                    imageInput.value = '';
                };
                inputEl.addEventListener('keydown', function (event) {
                    if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        sendMessage();
                    }
                });

                function authHeaders(extra) {
                    var headers = extra || {};
                    var key = apiKeyInput.value.trim();
                    if (key) headers['Authorization'] = 'Bearer ' + key;
                    return headers;
                }

                async function loadModels() {
                    if (!apiKeyInput.value.trim()) {
                        modelText.textContent = '未填写 Key';
                        return;
                    }
                    try {
                        var res = await fetch('/v1/models', { headers: authHeaders() });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var json = await res.json();
                        var first = json.data && json.data.length ? json.data[0] : null;
                        modelText.textContent = first ? (first.display_name || first.id) : '未发现模型';
                        var visionReady = first && first.vision_ready;
                        apiText.textContent = visionReady ? ' 已连接本机 MCA API · 识图就绪' : ' 已连接本机 MCA API';
                    } catch (error) {
                        modelText.textContent = '连接失败';
                        apiText.textContent = ' 请检查 Key、手机端开放端口和同网段网络';
                    }
                }

                function addBubble(role, text) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var div = document.createElement('div');
                    div.className = 'msg ' + (role === 'user' ? 'user' : 'assistant');
                    div.textContent = text || '';
                    messagesEl.appendChild(div);
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return div;
                }

                function addReasoningBox(beforeEl) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var details = document.createElement('details');
                    details.className = 'reasoning';
                    var summary = document.createElement('summary');
                    summary.textContent = '思考过程';
                    var body = document.createElement('div');
                    details.appendChild(summary);
                    details.appendChild(body);
                    if (beforeEl && beforeEl.parentNode === messagesEl) {
                        messagesEl.insertBefore(details, beforeEl);
                    } else {
                        messagesEl.appendChild(details);
                    }
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return body;
                }

                function renderAttachments() {
                    attachmentsEl.innerHTML = '';
                    pendingImages.forEach(function (item, index) {
                        var chip = document.createElement('div');
                        chip.className = 'attachment';
                        var img = document.createElement('img');
                        img.src = item.dataUrl;
                        var name = document.createElement('span');
                        name.textContent = item.name;
                        var remove = document.createElement('button');
                        remove.type = 'button';
                        remove.textContent = '×';
                        remove.onclick = function () {
                            pendingImages.splice(index, 1);
                            renderAttachments();
                        };
                        chip.appendChild(img);
                        chip.appendChild(name);
                        chip.appendChild(remove);
                        attachmentsEl.appendChild(chip);
                    });
                }

                function readAsDataUrl(file) {
                    return new Promise(function (resolve, reject) {
                        var reader = new FileReader();
                        reader.onload = function () { resolve(String(reader.result || '')); };
                        reader.onerror = function () { reject(reader.error || new Error('图片读取失败')); };
                        reader.readAsDataURL(file);
                    });
                }

                async function addImageFile(file) {
                    if (!file || !file.type || !file.type.startsWith('image/')) {
                        alert('请选择图片文件');
                        return;
                    }
                    if (file.size > maxImageBytes) {
                        alert('单张图片不能超过 8 MB');
                        return;
                    }
                    if (pendingImages.length >= maxImagesPerTurn) {
                        alert('每轮最多发送 4 张图片');
                        return;
                    }
                    pendingImages.push({
                        name: file.name || 'image',
                        mimeType: file.type || 'image/jpeg',
                        dataUrl: await readAsDataUrl(file)
                    });
                    renderAttachments();
                }

                function setBusy(busy) {
                    sendBtn.disabled = busy;
                    stopBtn.disabled = !busy;
                    inputEl.disabled = busy;
                    pickImageBtn.disabled = busy;
                }

                async function sendMessage() {
                    var text = inputEl.value.trim();
                    var imagesToSend = pendingImages.slice();
                    if ((!text && imagesToSend.length === 0) || sendBtn.disabled) return;
                    if (!apiKeyInput.value.trim()) {
                        alert('请先填写 MCA 页面里的 API Key');
                        return;
                    }
                    var effectiveText = text || '请描述这张图片。';
                    var contentParts = [];
                    if (effectiveText) contentParts.push({ type: 'text', text: effectiveText });
                    imagesToSend.forEach(function (image) {
                        contentParts.push({
                            type: 'image_url',
                            image_url: { url: image.dataUrl, detail: 'auto' }
                        });
                    });
                    var userMessage = imagesToSend.length
                        ? { role: 'user', content: contentParts }
                        : { role: 'user', content: effectiveText };
                    inputEl.value = '';
                    pendingImages = [];
                    renderAttachments();
                    addBubble('user', effectiveText + (imagesToSend.length ? '\n[已附加 ' + imagesToSend.length + ' 张图片]' : ''));
                    chatMessages.push(userMessage);
                    var reasoningBody = null;
                    var reasoningText = '';
                    var assistantBubble = addBubble('assistant', '');
                    var assistantText = '';
                    controller = new AbortController();
                    setBusy(true);
                    try {
                        var res = await fetch('/v1/chat/completions', {
                            method: 'POST',
                            headers: authHeaders({ 'Content-Type': 'application/json' }),
                            body: JSON.stringify({ messages: chatMessages, stream: true }),
                            signal: controller.signal
                        });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var reader = res.body.getReader();
                        var decoder = new TextDecoder('utf-8');
                        var buffer = '';
                        while (true) {
                            var chunk = await reader.read();
                            if (chunk.done) break;
                            buffer += decoder.decode(chunk.value, { stream: true });
                            var lines = buffer.split('\n');
                            buffer = lines.pop() || '';
                            for (var i = 0; i < lines.length; i++) {
                                var line = lines[i].trim();
                                if (!line.startsWith('data:')) continue;
                                var data = line.slice(5).trim();
                                if (data === '[DONE]') {
                                    buffer = '';
                                    break;
                                }
                                try {
                                    var json = JSON.parse(data);
                                    if (json.error) {
                                        assistantText += '\n[错误] ' + json.error;
                                    } else {
                                        var delta = (((json.choices || [])[0] || {}).delta || {});
                                        var reasoning = delta.reasoning_content || '';
                                        if (reasoning) {
                                            if (!reasoningBody) reasoningBody = addReasoningBox(assistantBubble);
                                            reasoningText += reasoning;
                                            reasoningBody.textContent = reasoningText;
                                        }
                                        assistantText += delta.content || '';
                                    }
                                    assistantBubble.textContent = assistantText;
                                    messagesEl.scrollTop = messagesEl.scrollHeight;
                                } catch (ignore) {}
                            }
                        }
                        chatMessages.push({ role: 'assistant', content: assistantText });
                    } catch (error) {
                        if (error.name !== 'AbortError') {
                            assistantBubble.textContent = assistantText || ('请求失败：' + error.message);
                        }
                    } finally {
                        setBusy(false);
                    }
                }

                loadModels();
            </script>
        </body>
        </html>
    """.trimIndent()
