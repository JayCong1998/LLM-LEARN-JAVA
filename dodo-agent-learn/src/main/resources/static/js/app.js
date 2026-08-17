const conversationId = crypto.randomUUID();
const messageInput = document.querySelector('#message');
const output = document.querySelector('#output');
const status = document.querySelector('#status');
const sendButton = document.querySelector('#send');
const stopButton = document.querySelector('#stop');
const toolTrace = document.querySelector('#tool-trace'); // 保存工具轨迹容器，用于创建和更新调用卡片。
const toolCards = new Map(); // 按 toolCallId 保存卡片，确保结束事件更新对应的开始事件。
const memoryTurnCount = document.querySelector('#memory-turn-count'); // 保存显示已加载历史轮次数量的文本节点。
const memoryStatus = document.querySelector('#memory-status'); // 保存记忆查询、清空和失败状态的展示节点。
const refreshMemoryButton = document.querySelector('#refresh-memory'); // 保存只读取当前会话历史的按钮。
const clearMemoryButton = document.querySelector('#clear-memory'); // 保存显式删除当前会话历史的按钮。
const memoryTurns = document.querySelector('#memory-turns'); // 保存动态创建的历史问答条目容器。

document.querySelector('#conversation-id').textContent = conversationId;

let requestController;
let currentRequestReportedError = false; // 记录本次 SSE 协议是否已经报告失败，防止失败结束后误刷新记忆。
let currentRequestReceivedAnswer = false; // 记录本次 SSE 是否收到最终文本，用于识别可以安全刷新历史的成功终止。

function setRunning(running) {
    sendButton.disabled = running;
    stopButton.disabled = !running;
}

function setStatus(text, state) {
    status.textContent = text;
    status.dataset.state = state;
}

function setMemoryStatus(text, state) { // 统一设置记忆面板的可见状态和语义样式。
    memoryStatus.textContent = text; // 向用户说明当前加载、空历史、成功或失败的状态。
    memoryStatus.dataset.state = state; // 为 CSS 提供稳定状态标记而不依赖文案判断。
} // 结束记忆面板状态更新函数。

function memoryApiUrl() { // 按当前固定 conversationId 组装会话记忆资源地址。
    return '/api/agent/conversations/' + encodeURIComponent(conversationId) + '/memory'; // 对会话编号编码后返回 GET 和 DELETE 共用路径。
} // 结束会话记忆接口地址构造函数。

function renderMemoryTurns(turns) { // 将后端返回的完整问答轮次安全渲染为 DOM 节点。
    memoryTurns.replaceChildren(); // 先删除旧快照，避免刷新后重复累积历史条目。
    memoryTurnCount.textContent = turns.length + '/5'; // 以固定窗口上限直观展示当前已加载轮次。
    if (turns.length === 0) { // 空数组表示当前会话尚未成功保存任何完整问答。
        const empty = document.createElement('p'); // 创建空历史提示元素而不是拼接 HTML 字符串。
        empty.className = 'memory-empty'; // 应用空状态的次要视觉样式。
        empty.textContent = '当前会话暂无已完成问答'; // 明确说明未完成或失败请求不会被写入记忆。
        memoryTurns.append(empty); // 将空状态提示加入历史容器。
        return; // 空历史不需要继续创建问答条目。
    } // 结束空历史分支。
    for (const turn of turns) { // 按后端窗口提供的时间顺序逐轮创建用户与助手内容。
        const item = document.createElement('article'); // 使用语义化文章元素表示一轮完整问答。
        item.className = 'memory-turn'; // 应用单轮历史卡片样式。
        const userLabel = document.createElement('strong'); // 创建用户内容的角色标签。
        userLabel.textContent = '用户'; // 写入固定角色名而不信任外部数据。
        const userContent = document.createElement('p'); // 创建承载历史用户问题的文本元素。
        userContent.textContent = turn.userContent; // 使用 textContent 渲染用户历史，防止内容被解析为 HTML。
        const assistantLabel = document.createElement('strong'); // 创建助手内容的角色标签。
        assistantLabel.textContent = '助手'; // 写入固定角色名而不信任外部数据。
        const assistantContent = document.createElement('p'); // 创建承载历史最终回答的文本元素。
        assistantContent.textContent = turn.assistantContent; // 使用 textContent 渲染助手历史，防止内容被解析为 HTML。
        item.append(userLabel, userContent, assistantLabel, assistantContent); // 以完整用户问答配对顺序组装历史卡片。
        memoryTurns.append(item); // 将当前历史轮次追加到安全的面板容器末尾。
    } // 结束历史轮次渲染循环。
} // 结束历史问答 DOM 渲染函数。

async function refreshMemory() { // 查询并展示当前会话已经成功保存的跨请求历史。
    refreshMemoryButton.disabled = true; // 查询期间暂时阻止重复点击造成无意义的并发 GET 请求。
    clearMemoryButton.disabled = true; // 查询期间同时锁定清空按钮，避免显示旧快照与清空结果交错。
    setMemoryStatus('正在加载记忆', 'loading'); // 在网络请求开始前提供明确的加载反馈。
    try { // 将网络和 JSON 边界失败转换为面板可理解的状态。
        const response = await fetch(memoryApiUrl()); // 调用当前会话的记忆查询接口而不启动 Agent。
        if (!response.ok) { // 非成功 HTTP 状态不能被当成可渲染的历史响应。
            throw new Error('读取记忆失败，HTTP ' + response.status); // 保留状态码以便学习和诊断。
        } // 结束查询 HTTP 状态保护分支。
        const payload = await response.json(); // 解析控制器约定的 conversationId 与 turns JSON 响应。
        if (!Array.isArray(payload.turns)) { // 防御服务端响应结构异常，避免脚本因遍历非数组中断。
            throw new Error('读取记忆失败，响应缺少轮次数组'); // 输出稳定错误而不渲染未知数据。
        } // 结束响应结构保护分支。
        renderMemoryTurns(payload.turns); // 仅在取得完整数组后替换页面上的历史快照。
        setMemoryStatus('已加载当前会话记忆', 'complete'); // 明确当前面板显示的是刚刚读取的服务器快照。
    } catch (error) { // 捕获连接失败、HTTP 失败和 JSON 解析失败。
        memoryTurns.replaceChildren(); // 读取失败时清除可能已经过期的视觉历史，避免用户误信旧快照。
        memoryTurnCount.textContent = '读取失败'; // 用计数位置明确显示当前不能得出可靠轮次数。
        setMemoryStatus(error.message, 'error'); // 以稳定文本展示记忆操作失败原因。
    } finally { // 无论请求成功或失败都恢复用户对记忆面板的控制。
        refreshMemoryButton.disabled = false; // 允许用户在网络恢复后再次主动查询。
        clearMemoryButton.disabled = false; // 恢复显式清空操作入口。
    } // 结束记忆查询收尾逻辑。
} // 结束记忆查询函数。

async function clearMemory() { // 删除当前会话已保存的历史窗口，并在完成后读取清空后的快照。
    refreshMemoryButton.disabled = true; // 清空期间阻止并发刷新读取到竞态中的旧窗口。
    clearMemoryButton.disabled = true; // 清空期间防止用户重复发送 DELETE 请求。
    setMemoryStatus('正在清空记忆', 'loading'); // 明确告知用户当前操作只影响已保存历史。
    try { // 将 DELETE 失败转换为可见面板状态并恢复操作按钮。
        const response = await fetch(memoryApiUrl(), {method: 'DELETE'}); // 调用记忆管理控制器的幂等清空接口。
        if (!response.ok) { // 非成功 HTTP 状态表示清空动作没有可靠完成。
            throw new Error('清空记忆失败，HTTP ' + response.status); // 保留 HTTP 状态码帮助定位服务端问题。
        } // 结束清空 HTTP 状态保护分支。
        await refreshMemory(); // 清空后重新 GET，确保页面展示服务端实际快照而不是本地猜测。
    } catch (error) { // 捕获网络和 HTTP 失败。
        setMemoryStatus(error.message, 'error'); // 向用户展示清空失败而不伪造已清空状态。
    } finally { // 无论清空成功还是失败都释放按钮。
        refreshMemoryButton.disabled = false; // 恢复后续主动刷新操作。
        clearMemoryButton.disabled = false; // 恢复后续显式清空操作。
    } // 结束清空收尾逻辑。
} // 结束会话记忆清空函数。

function handleEvent(event) {
    if (event.type === 'text') {
        output.textContent += event.content;
        currentRequestReceivedAnswer = true; // 最终文本到达说明当前请求具备成功保存并刷新记忆的必要条件。
    } else if (event.type === 'tool_start') { // 收到 Action 时创建一张处于运行中的工具卡片。
        handleToolStart(event); // 把工具名称、调用编号和参数写入轨迹区域。
    } else if (event.type === 'tool_end') { // 收到 Observation 时查找并完成同一次调用卡片。
        handleToolEnd(event); // 用工具执行结果更新对应卡片的状态和内容。
    } else if (event.type === 'error') {
        setStatus(event.content, 'error');
        currentRequestReportedError = true; // 记录 Agent 已明确失败，complete 到达时不得把失败误判为成功。
    } else if (event.type === 'complete') {
        if (!currentRequestReportedError) { // 只有未收到 error 的协议终止才显示成功完成状态。
            setStatus('完成', 'complete'); // 更新主输出区域的成功状态。
        } // 结束 SSE 成功终止显示分支。
        if (!currentRequestReportedError && currentRequestReceivedAnswer) { // 只有成功最终回答后才读取最新跨请求记忆。
            void refreshMemory(); // 异步刷新不阻塞 SSE 帧处理与流关闭。
        } // 结束成功回答后的记忆自动刷新分支。
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
    currentRequestReportedError = false; // 新请求开始时清除上一轮 Agent 失败标记。
    currentRequestReceivedAnswer = false; // 新请求开始时清除上一轮最终回答到达标记。
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
refreshMemoryButton.addEventListener('click', refreshMemory); // 绑定只查询当前会话历史的显式刷新交互。
clearMemoryButton.addEventListener('click', clearMemory); // 绑定显式删除当前会话历史的交互。
messageInput.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        sendMessage();
    }
});

void refreshMemory(); // 页面加载后主动读取一次历史，以区分尚未加载和真正没有历史两种状态。
