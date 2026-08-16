# Next Session Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在学习项目根目录生成可直接复制的新会话交接提示词，并将 Git 远程地址更新为更名后的仓库。

**Architecture:** 交接文件只引用仓库内权威文档和提交历史，以相对路径保证跨电脑可移植；Git 远程配置属于本地仓库元数据，不写入提示词。提交前统一检查所有待提交文件、敏感信息和路径有效性。

**Tech Stack:** Markdown、Git、Maven、PowerShell。

---

### Task 1：生成 Agent 学习项目交接提示词

**Files:**
- Create: `dodo-agent-learn/NEXT_SESSION_PROMPT.md`

- [ ] **Step 1：写入自包含提示词**

提示词必须包含项目目标、必读文件、阶段进度、关键提交、开发规范、Task 6～7、恢复命令和 API Key 安全规则，并且只使用仓库相对路径。

- [ ] **Step 2：检查引用路径**

Run: `rg -n "dodo-agent-learn/|docs/superpowers/" dodo-agent-learn/NEXT_SESSION_PROMPT.md`

Expected: 必读路径均位于 `dodo-agent-learn/` 或 `docs/superpowers/`，不存在机器绝对路径。

- [ ] **Step 3：检查敏感信息与占位符**

Run: `rg -n "sk-[A-Za-z0-9_-]{12,}|TODO|TBD|待定" dodo-agent-learn/NEXT_SESSION_PROMPT.md`

Expected: 无真实密钥或未完成占位符。

### Task 2：更新远程并完成全量提交

**Files:**
- Include: `docs/superpowers/plans/2026-08-16-next-session-handoff.md`
- Include: `dodo-agent-learn/NEXT_SESSION_PROMPT.md`
- Include: 当前工作区其余已审查且安全的修改

- [ ] **Step 1：更新 origin**

Run: `git remote set-url origin https://github.com/JayCong1998/LLM-LEARN-JAVA.git`

Expected: `git remote -v` 的 fetch 与 push 都显示新地址。

- [ ] **Step 2：执行全量安全审计**

Run: `git status --short`，逐项检查所有待提交文件；对文本执行真实 API Key 模式扫描。

Expected: 所有文件均属于用户授权提交范围，且不包含真实密钥。

- [ ] **Step 3：提交全部工作区内容**

Run: `git add -A && git commit -m "docs: add next session handoffs"`

Expected: 提交成功，`git status --short` 无输出。

- [ ] **Step 4：验证提交和远程配置**

Run: `git show --stat --oneline HEAD` 与 `git remote -v`

Expected: 最新提交包含两份交接提示词和本计划，origin 指向 `LLM-LEARN-JAVA.git`。
