const conversationId = crypto.randomUUID();
const messageInput = document.querySelector('#message');
const output = document.querySelector('#output');
const status = document.querySelector('#status');
const sendButton = document.querySelector('#send');
const stopButton = document.querySelector('#stop');

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
    } else if (event.type === 'error') {
        setStatus(event.content, 'error');
    } else if (event.type === 'complete') {
        setStatus('完成', 'complete');
    }
}

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
