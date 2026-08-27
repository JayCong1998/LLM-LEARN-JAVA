$page = Get-Content -Raw 'src/main/resources/static/upload.html'

foreach ($requiredText in @("formData.append('version'", 'response.code === 0', 'response.message')) {
    if ($page -notmatch [regex]::Escape($requiredText)) {
        throw "缺少接口契约：$requiredText"
    }
}

if ($page -match 'accessibleBy') {
    throw '页面不应提交 accessibleBy'
}

if ($page -match '/api/document/split/') {
    throw '页面不应调用非上传接口的切片端点'
}
