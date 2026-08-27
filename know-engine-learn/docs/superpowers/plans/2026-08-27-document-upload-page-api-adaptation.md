# 文档上传页接口适配实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让文档上传页按最新 `DocumentController#uploadFile` multipart 参数发送请求，并正确解析 `ApiResponse` 响应。

**架构：** 仅修改静态上传页。表单用 `FormData` 发送 Controller 支持的字段；`XMLHttpRequest` 在 HTTP 成功后继续检查响应体的 `code`，并显示 `message`。页面不保留依赖历史返回数据的切片流程。

**技术栈：** HTML、CSS、原生 JavaScript、PowerShell 静态契约检查、Maven。

---

## 文件结构

- 修改：`src/main/resources/static/upload.html` — 上传表单、接口请求及结果展示。
- 创建：`src/test/resources/upload-page-api-contract.ps1` — 对上传页接口契约进行可重复的静态检查。

### 任务 1：建立接口契约回归检查

**文件：**

- 创建：`src/test/resources/upload-page-api-contract.ps1`
- 测试：`src/test/resources/upload-page-api-contract.ps1`

- [ ] **步骤 1：编写失败的测试**

```powershell
$page = Get-Content -Raw 'src/main/resources/static/upload.html'
foreach ($requiredText in @("formData.append('version'", 'response.code === 0', 'response.message')) {
    if ($page -notmatch [regex]::Escape($requiredText)) { throw "缺少接口契约：$requiredText" }
}
if ($page -match 'accessibleBy') { throw '页面不应提交 accessibleBy' }
if ($page -match '/api/document/split/') { throw '页面不应调用非上传接口的切片端点' }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`powershell -ExecutionPolicy Bypass -File src/test/resources/upload-page-api-contract.ps1`

预期：失败并提示缺少 `formData.append('version'` 或 `response.code === 0`。

- [ ] **步骤 3：保留脚本作为回归检查**

```powershell
$page = Get-Content -Raw 'src/main/resources/static/upload.html'
foreach ($requiredText in @("formData.append('version'", 'response.code === 0', 'response.message')) {
    if ($page -notmatch [regex]::Escape($requiredText)) { throw "缺少接口契约：$requiredText" }
}
if ($page -match 'accessibleBy') { throw '页面不应提交 accessibleBy' }
if ($page -match '/api/document/split/') { throw '页面不应调用非上传接口的切片端点' }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`powershell -ExecutionPolicy Bypass -File src/test/resources/upload-page-api-contract.ps1`

预期：退出码为 0，无输出。

### 任务 2：适配上传表单与响应处理

**文件：**

- 修改：`src/main/resources/static/upload.html`
- 测试：`src/test/resources/upload-page-api-contract.ps1`

- [ ] **步骤 1：补充版本号表单控件**

```html
<div class="form-group">
    <label for="version">文档版本</label>
    <input type="text" id="version" name="version" value="1.0.0" placeholder="例如：1.0.0">
</div>
```

- [ ] **步骤 2：提交 Controller 支持的可选版本字段**

```javascript
formData.append('version', document.getElementById('version').value.trim() || '1.0.0');
```

- [ ] **步骤 3：按统一响应包装处理业务结果并移除旧切片流程**

```javascript
const response = JSON.parse(xhr.responseText);
if (response.code !== 0) {
    showResult('error', '上传失败', response.message || '服务端未返回失败原因');
    return;
}
showResult('success', '上传成功！', response.message || '文档已上传。');
setTimeout(() => { window.location.href = '/document.html'; }, 1500);
```

- [ ] **步骤 4：运行测试验证通过**

运行：`powershell -ExecutionPolicy Bypass -File src/test/resources/upload-page-api-contract.ps1`

预期：退出码为 0，无输出。

### 任务 3：验证项目完整性

**文件：**

- 修改：无
- 测试：`src/test/resources/upload-page-api-contract.ps1`

- [ ] **步骤 1：运行页面接口契约检查**

运行：`powershell -ExecutionPolicy Bypass -File src/test/resources/upload-page-api-contract.ps1`

预期：退出码为 0，无输出。

- [ ] **步骤 2：运行 Maven 测试**

运行：`./mvnw test`

预期：构建成功，退出码为 0。
