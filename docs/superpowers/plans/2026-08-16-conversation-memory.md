# Conversation Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `dodo-agent-learn` 增加进程内、按 `conversationId` 隔离、最多保留最近 5 轮的跨请求会话记忆，并提供查询、清空与前端查看能力。

**Architecture:** 使用手写 `ConversationMemory` 端口隔离存储机制，首个适配器采用线程安全的 `InMemoryConversationMemory`。`ManualReactAgent` 在运行开始时读取不可变历史快照，将其按 System → 历史 User/Assistant → 当前 User 的顺序交给模型；仅在正常回答成功后原子地提交当前问答。工具调用轨迹只属于单次运行，不进入跨请求记忆。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI 1.1、Project Reactor、JUnit 5、Mockito、MockMvc、原生 HTML/CSS/JavaScript。

---

## Task 1：定义会话轮次领域对象

**Files:**
- Create: `src/main/java/com/jaycong/dodo/memory/ConversationTurn.java`
- Create: `src/test/java/com/jaycong/dodo/memory/ConversationTurnTest.java`

- [ ] **Step 1：先写非法内容测试**

验证用户问题或助手回答为 `null`、空串、纯空白时抛出 `IllegalArgumentException`。

- [ ] **Step 2：运行测试并确认失败**

Run: `mvn -Dtest=ConversationTurnTest test`

Expected: 因 `ConversationTurn` 尚不存在而编译失败。

- [ ] **Step 3：实现最小领域对象**

创建：

```java
public record ConversationTurn(String userContent, String assistantContent) {
    public ConversationTurn {
        // 分别校验 userContent 与 assistantContent 必须包含可见文本。
    }
}
```

导入包和注解可不写注释；其余新增有效代码逐行写中文注释，并解释不可持久化空轮次的原因。

- [ ] **Step 4：补充并通过正常构造测试**

验证字段保持原值，运行 `mvn -Dtest=ConversationTurnTest test`，预期全部通过。

- [ ] **Step 5：提交本任务**

```bash
git add src/main/java/com/jaycong/dodo/memory/ConversationTurn.java src/test/java/com/jaycong/dodo/memory/ConversationTurnTest.java
git commit -m "feat: define conversation turn"
```

## Task 2：实现线程安全的窗口记忆

**Files:**
- Create: `src/main/java/com/jaycong/dodo/memory/ConversationMemory.java`
- Create: `src/main/java/com/jaycong/dodo/memory/InMemoryConversationMemory.java`
- Create: `src/test/java/com/jaycong/dodo/memory/InMemoryConversationMemoryTest.java`

- [ ] **Step 1：编写端口契约与失败测试**

端口固定为：

```java
List<ConversationTurn> get(String conversationId);
void append(String conversationId, ConversationTurn turn);
boolean clear(String conversationId);
```

测试覆盖：新会话为空、追加后顺序正确、不同会话隔离、超过 5 轮删除最旧轮次、返回快照不可修改、清空返回值语义、非法 `conversationId` 与空轮次被拒绝。

- [ ] **Step 2：运行测试并确认失败**

Run: `mvn -Dtest=InMemoryConversationMemoryTest test`

- [ ] **Step 3：实现最小线程安全适配器**

使用 `@Component`、`ConcurrentMap<String, ConversationWindow>` 与常量 `MAX_TURNS = 5`。每个窗口内部以同步临界区实现 `appendAndTrim` 和 `snapshot`；快照使用 `List.copyOf`，不得泄漏内部可变集合。

核心伪代码：

```text
append(conversationId, turn):
    校验参数
    window = windows.computeIfAbsent(conversationId)
    synchronized window:
        追加 turn
        while size > 5:
            删除最旧 turn

get(conversationId):
    校验 conversationId
    找不到窗口则返回空不可变列表
    synchronized window:
        返回当前轮次的不可变副本

clear(conversationId):
    校验 conversationId
    从 map 移除整个窗口并返回是否存在
```

- [ ] **Step 4：补充并发测试并通过**

多个线程向同一会话追加后，断言没有并发异常、窗口不超过 5 轮且轮次对象完整；不同会话互不阻塞语义无需依赖执行时序断言。

Run: `mvn -Dtest=InMemoryConversationMemoryTest test`

- [ ] **Step 5：提交本任务**

```bash
git add src/main/java/com/jaycong/dodo/memory src/test/java/com/jaycong/dodo/memory
git commit -m "feat: add in-memory conversation window"
```

## Task 3：在 Agent 启动时加载跨请求历史

**Files:**
- Modify: `src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- Modify: `src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`
- Modify: `src/test/java/com/jaycong/dodo/controller/ChatControllerTest.java`
- Create: `src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java`

- [ ] **Step 1：新增历史消息顺序测试**

构造已有两轮历史的假记忆，发起第三次请求，捕获传给模型的消息并断言顺序严格为：System、历史 User、历史 Assistant、历史 User、历史 Assistant、当前 User。断言历史中没有 ToolCall 或 ToolResponse。

- [ ] **Step 2：新增读取异常测试**

当 `memory.get` 抛出异常时，断言模型没有被调用，SSE 只出现 `error` 与 `complete`，任务注册表最终释放。

- [ ] **Step 3：运行新测试并确认失败**

Run: `mvn -Dtest=ManualReactAgentMemoryTest test`

- [ ] **Step 4：注入 ConversationMemory 并初始化上下文**

调整构造器及既有测试装配。运行开始伪代码：

```text
stream(conversationId, question):
    注册任务
    在 boundedElastic 工作线程中：
        historySnapshot = memory.get(conversationId)
        messages = [system]
        对 historySnapshot 每一轮追加 user 与 assistant
        追加当前 user
        创建只服务本次请求的 ReactRunContext(messages)
        进入 ReAct 循环
    若读取失败：发送 error、complete 并清理任务
```

历史必须在每次请求开始时只读一次，确保运行期间执行 DELETE 不会修改当前上下文。

- [ ] **Step 5：迁移所有构造调用并通过回归测试**

Run: `mvn -Dtest=ManualReactAgentMemoryTest,ManualReactAgentTest,ChatControllerTest test`

- [ ] **Step 6：提交本任务**

```bash
git add src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java src/test/java/com/jaycong/dodo/controller/ChatControllerTest.java
git commit -m "feat: load conversation history into agent"
```

## Task 4：只在成功终止时提交问答

**Files:**
- Modify: `src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- Modify: `src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java`

- [ ] **Step 1：先写成功提交测试**

正常回答后断言恰好追加一个 `ConversationTurn(question, answer)`，并且保存发生在 `text` 和 `complete` 事件之前。

- [ ] **Step 2：写所有禁止提交的终止路径测试**

覆盖模型异常、工具异常、达到最大步数、空答案、主动取消、同会话并发请求被拒绝；每种情况都断言 `append` 未调用。

- [ ] **Step 3：写保存失败测试**

当 `append` 抛出异常时，断言不发送答案 `text`，只发送“会话记忆保存失败”错误与 `complete`，任务最终释放。

- [ ] **Step 4：运行测试并确认失败**

Run: `mvn -Dtest=ManualReactAgentMemoryTest test`

- [ ] **Step 5：修改唯一正常终止序列**

核心伪代码：

```text
finishSuccessfully(conversationId, context, output, question, answer):
    若 answer 为空：走普通错误终止并返回
    若 context.tryFinish() 失败：返回
    释放会话任务占用
    try:
        memory.append(conversationId, ConversationTurn(question, answer))
    catch error:
        发送 memory-save error
        发送 complete
        关闭 Reactor 流
        返回
    发送 text(answer)
    发送 complete
    关闭 Reactor 流
```

注意：取得 `tryFinish()` 的终止所有权后，不可再调用内部同样执行 `tryFinish()` 的普通错误方法，否则第二次 CAS 会令错误事件也无法发出。该分支必须直接完成错误协议。

- [ ] **Step 6：通过定向与完整 Agent 测试**

Run: `mvn -Dtest=ManualReactAgentMemoryTest,ManualReactAgentTest test`

- [ ] **Step 7：提交本任务**

```bash
git add src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java
git commit -m "feat: persist successful agent turns"
```

## Task 5：提供记忆查询与清空 API

**Files:**
- Create: `src/main/java/com/jaycong/dodo/controller/ConversationMemoryController.java`
- Create: `src/test/java/com/jaycong/dodo/controller/ConversationMemoryControllerTest.java`

- [ ] **Step 1：编写 MockMvc 契约测试**

覆盖：

```http
GET /api/agent/conversations/{conversationId}/memory
=> {"conversationId":"...","turns":[...]}

DELETE /api/agent/conversations/{conversationId}/memory
=> {"cleared":true|false}
```

还要验证空历史、特殊但合法的路径变量、存储异常映射为稳定服务端错误响应。

- [ ] **Step 2：运行测试并确认失败**

Run: `mvn -Dtest=ConversationMemoryControllerTest test`

- [ ] **Step 3：实现薄控制器**

控制器只负责 HTTP 映射和响应 DTO，不复制窗口逻辑。DTO 可作为控制器内部 record。所有有效代码遵守中文逐行注释规则。

清空语义：若 Agent 已加载历史后发生 DELETE，只清除持久记忆，不取消运行、不修改其快照；该运行若成功，其问答将成为清空后的第一轮。

- [ ] **Step 4：通过控制器与上下文测试**

Run: `mvn -Dtest=ConversationMemoryControllerTest,ChatControllerTest test`

- [ ] **Step 5：提交本任务**

```bash
git add src/main/java/com/jaycong/dodo/controller/ConversationMemoryController.java src/test/java/com/jaycong/dodo/controller/ConversationMemoryControllerTest.java
git commit -m "feat: expose conversation memory api"
```

## Task 6：增加前端记忆面板

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/com/jaycong/dodo/controller/StaticPageTest.java`

- [ ] **Step 1：先扩展静态页面契约测试**

断言页面含记忆面板、刷新按钮、清空按钮、GET/DELETE API 路径拼装，以及使用 `textContent` 渲染用户和助手内容的安全约束。

- [ ] **Step 2：运行测试并确认失败**

Run: `mvn -Dtest=StaticPageTest test`

- [ ] **Step 3：实现记忆面板**

行为要求：

- 当前 `conversationId` 变化或请求成功结束后刷新记忆。
- “刷新记忆”只查询，不启动 Agent。
- “清空记忆”调用 DELETE，随后刷新面板。
- 空历史、加载中和请求失败均有明确状态。
- 用户内容与助手内容只通过 DOM `textContent` 写入，不使用 `innerHTML`。
- 面板不展示推理链和本次工具调用内部消息。

- [ ] **Step 4：通过静态页面与完整 Web 测试**

Run: `mvn -Dtest=StaticPageTest,ConversationMemoryControllerTest,ChatControllerTest test`

- [ ] **Step 5：提交本任务**

```bash
git add src/main/resources/static/index.html src/test/java/com/jaycong/dodo/controller/StaticPageTest.java
git commit -m "feat: add conversation memory panel"
```

## Task 7：完善第三阶段教程文档并整体验收

**Files:**
- Create: `tutorials/stages/03-conversation-memory.md`
- Modify only if required: `README.md`

- [ ] **Step 1：运行完整测试并记录基线**

Run: `mvn clean test`

Expected: BUILD SUCCESS，且 0 failures、0 errors。

- [ ] **Step 2：执行注释与秘密信息审计**

检查本阶段所有新增/修改 Java、HTML、JavaScript 有效代码是否符合 `AGENTS.md`；导入包与注解无需注释。确认配置与文档未包含真实 API Key。

Run: `rg -n "sk-[A-Za-z0-9_-]{12,}|api[_-]?key\s*[:=]\s*[^$<{ ]" .`

Expected: 不出现真实密钥；示例必须使用环境变量或明显占位符。

- [ ] **Step 3：编写可脱离未来代码阅读的阶段文档**

文档至少包含：

- 阶段目标、需求边界与不做事项。
- 跨请求与单次请求上下文的区别。
- 核心类职责、依赖方向和时序图。
- 5 轮滑动窗口、线程安全与不可变快照原理。
- 成功提交、失败不提交、取消不提交的终止矩阵。
- GET/DELETE API 与清空并发语义。
- 完整核心伪代码：读取历史、构造消息、运行 ReAct、成功保存、裁剪窗口、查询和清空。
- 测试策略、手工验证步骤、常见问题与下一阶段演进方向。

- [ ] **Step 4：执行文档占位符和差异检查**

Run: `rg -n "TODO|TBD|待定|以后实现|implement later" tutorials/stages/03-conversation-memory.md`

Expected: 无未完成占位符。

Run: `git diff --check`

Expected: 无空白错误。

- [ ] **Step 5：再次执行最终测试**

Run: `mvn clean test`

Expected: BUILD SUCCESS，且 0 failures、0 errors。不得用旧测试结果代替最终验证。

- [ ] **Step 6：只提交第三阶段文档及必要索引**

```bash
git add tutorials/stages/03-conversation-memory.md README.md
git commit -m "docs: explain conversation memory stage"
```

若 `README.md` 无需修改，则不得为凑提交而改动或暂存它。

## 最终人工验收场景

- [ ] 使用同一 `conversationId` 请求“我叫小明”，再问“我叫什么”，回答能利用上一请求信息。
- [ ] 使用另一 `conversationId` 询问姓名，不能读取前一个会话的内容。
- [ ] 连续完成 6 轮后，GET 接口只返回最近 5 轮且顺序正确。
- [ ] 发起会触发天气工具的请求，GET 只看到用户问题和最终回答，看不到工具参数与工具结果。
- [ ] 制造模型错误或取消请求，GET 中没有失败轮次。
- [ ] DELETE 后 GET 返回空数组；随后一次成功运行成为新的第一轮。
- [ ] SSE 仍按 `tool_start`、`tool_end`、`text`、`complete` 协议工作，原有取消和同会话互斥能力没有回归。
