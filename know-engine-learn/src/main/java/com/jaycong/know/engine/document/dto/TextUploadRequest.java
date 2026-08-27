package com.jaycong.know.engine.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 纯文本上传并自动切片的请求参数。
 */
@Data
public class TextUploadRequest {
    /** 上传后创建的文档标题。 */
    @NotBlank(message = "文档标题不能为空")
    private String title;
    /** 待切片的完整文本内容。 */
    @NotBlank(message = "文档内容不能为空")
    private String content;
    /** 文档描述。 */
    private String description;
    /** 单个片段的最大字符数；未传时使用服务默认值。 */
    @Positive(message = "切片大小必须为正数")
    private Integer chunkSize;
}
