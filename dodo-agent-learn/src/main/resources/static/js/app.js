const conversationId = crypto.randomUUID();
const messageInput = document.querySelector('#message');
const output = document.querySelector('#output');
const status = document.querySelector('#status');
const sendButton = document.querySelector('#send');
const stopButton = document.querySelector('#stop');
const toolTrace = document.querySelector('#tool-trace'); // 保存工具轨迹容器，用于创建和更新调用卡片。
const toolCards = new Map(); // 按 toolCallId 保存卡片，确保结束事件更新对应的开始事件。

document.querySelector('#conversation-id').textContent = conversationId;

let requestController;

function setRunning(running) {
    sendButton.disabled = running;
    stopButton.disabled = !running;
}

function setStatus(text, state) {
    status.textContent = text;
    status.dataset.state = state;
}

function handleEvent(event) {
    if (event.type === 'text') {
        output.textContent += event.content;
    } else if (event.type === 'tool_start') { // 收到 Action 时创建一张处于运行中的工具卡片。
        handleToolStart(event); // 把工具名称、调用编号和参数写入轨迹区域。
    } else if (event.type === 'tool_end') { // 收到 Observation 时查找并完成同一次调用卡片。
        handleToolEnd(event); // 用工具执行结果更新对应卡片的状态和内容。
    } else if (event.type === 'error') {
        setStatus(event.content, 'error');
    } else if (event.type === 'complete') {
        setStatus('完成', 'complete');
    }
}

function handleToolStart(event) { // 根据 tool_start 事件创建一张可被后续更新的工具卡片。
    toolTrace.querySelector('.tool-empty')?.remove(); // 第一次工具调用到达时移除初始空状态文案。
    const card = document.createElement('article'); // 创建单次工具调用的语义化卡片元素。
    card.className = 'tool-card'; // 应用运行中工具卡片的基础样式。
    card.dataset.toolCallId = event.toolCallId; // 把调用编号写入 DOM，便于人工检查事件关联。
    const title = document.createElement('strong'); // 创建只显示工具名称的安全文本节点。
    title.textContent = event.toolName; // 使用 textContent 防止模型返回内容被当成 HTML 执行。
    const state = document.createElement('span'); // 创建展示当前生命周期状态的标签。
    state.className = 'tool-state'; // 应用紧凑状态徽标样式。
    state.textContent = '运行中'; // 工具开始事件到达时标记尚未取得 Observation。
    const argumentsBlock = document.createElement('pre'); // 创建保留 JSON 格式的参数展示区域。
    argumentsBlock.className = 'tool-data'; // 应用工具输入输出共用的等宽样式。
    argumentsBlock.textContent = formatArguments(event.arguments); // 格式化合法 JSON，非法文本则保持原样展示。
    card.append(title, state, argumentsBlock); // 按名称、状态、参数顺序组装工具卡片。
    toolTrace.append(card); // 把新调用追加到轨迹末尾以保持事件发生顺序。
    toolCards.set(event.toolCallId, card); // 保存调用编号到卡片的映射供 tool_end 使用。
} // 结束工具开始事件处理函数。

function handleToolEnd(event) { // 根据 tool_end 事件完成对应工具卡片。
    const card = toolCards.get(event.toolCallId); // 使用调用编号查找开始事件创建的卡片。
    if (!card) return; // 若开始事件缺失则忽略孤立结束事件，避免脚本异常中断后续 SSE。
    card.classList.add('complete'); // 添加完成样式，直观区分运行中和已返回的工具。
    card.querySelector('.tool-state').textContent = '已完成'; // 更新同一张卡片的生命周期状态。
    const observation = document.createElement('pre'); // 创建工具 Observation 的独立展示区域。
    observation.className = 'tool-data observation'; // 应用结果区域的强调样式。
    observation.textContent = event.content; // 安全写入服务端返回的工具结果文本。
    card.append(observation); // 把 Observation 追加在调用参数之后形成完整轨迹。
} // 结束工具结束事件处理函数。

function formatArguments(argumentsText) { // 尝试把工具 JSON 参数格式化为更易阅读的缩进文本。
    if (!argumentsText) return '{}'; // 缺少参数时展示明确的空 JSON 对象。
    try { // 合法 JSON 使用浏览器解析器进行标准格式化。
        return JSON.stringify(JSON.parse(argumentsText), null, 2); // 以两个空格缩进返回格式化参数。
    } catch { // 模型偶尔可能返回非 JSON 参数，展示层不应因此失败。
        return argumentsText; // 无法解析时保留原始文本以便学习和诊断。
    } // 结束参数解析失败回退分支。
} // 结束工具参数格式化函数。

function consumeFrames(buffer) {
    const frames = [];
    let separator = buffer.match(/\r?\n\r?\n/);
    while (separator) {
        const index = separator.index;
        frames.push(buffer.slice(0, index));
        buffer = buffer.slice(index + separator[0].length);
        separator = buffer.match(/\r?\n\r?\n/);
    }
    return {frames, remainder: buffer};
}

function parseFrame(frame) {
    const data = frame
        .split(/\r?\n/)
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n');
    return data ? JSON.parse(data) : null;
}

async function sendMessage() {
    const message = messageInput.value.trim();
    if (!message) {
        setStatus('请输入消息', 'error');
        return;
    }

    requestController = new AbortController();
    setRunning(true);
    setStatus('流式响应中', 'running');
    output.textContent = '';
    toolTrace.innerHTML = '<p class="tool-empty">本轮尚未调用工具</p>'; // 新请求开始时清空上一轮工具轨迹并恢复占位文案。
    toolCards.clear(); // 清除旧调用编号映射，避免新旧请求的卡片错误关联。

    try {
        const url = new URL('/api/agent/chat/stream', window.location.origin);
        url.searchParams.set('conversationId', conversationId);
        url.searchParams.set('message', message);

        const response = await fetch(url, {signal: requestController.signal});
        if (!response.ok || !response.body) {
            throw new Error('请求失败，HTTP ' + response.status);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
            const {done, value} = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, {stream: true});
            const parsed = consumeFrames(buffer);
            buffer = parsed.remainder;
            for (const frame of parsed.frames) {
                const event = parseFrame(frame);
                if (event) handleEvent(event);
            }
        }
    } catch (error) {
        if (error.name === 'AbortError') {
            setStatus('已停止', 'stopped');
        } else {
            setStatus(error.message, 'error');
        }
    } finally {
        requestController = undefined;
        setRunning(false);
    }
}

async function stopMessage() {
    const response = await fetch(
        '/api/agent/tasks/' + encodeURIComponent(conversationId) + '/stop',
        {method: 'POST'});
    if (!response.ok) {
        setStatus('停止请求失败', 'error');
        return;
    }
    requestController?.abort();
}

sendButton.addEventListener('click', sendMessage);
stopButton.addEventListener('click', stopMessage);
messageInput.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        sendMessage();
    }
});
