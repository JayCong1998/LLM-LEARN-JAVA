package com.jaycong.dodo.tool; // 将计算器与其他 Agent 本地工具放在同一边界包中。

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 使用 BigDecimal 提供可复现的四则运算工具。
 * 所有可预期业务错误都返回文本 Observation，避免单个错误参数终止整个 ReAct 循环。
 */
@Component
public class CalculatorTool { // 定义可被模型选择并由手写循环实际执行的计算能力。

    private static final int DIVISION_SCALE = 8; // 固定除法保留八位小数，使无限小数具有稳定输出。

    @Tool(name = "calculator", description = "执行 ADD、SUBTRACT、MULTIPLY、DIVIDE 四种精确十进制运算")
    public String calculate(CalculationRequest request) { // 接收 Spring AI 从工具 JSON 参数反序列化出的结构化请求。
        if (request == null) { // 防止模型完全省略工具参数导致空指针异常。
            return "计算失败：计算参数不能为空"; // 把缺失请求转换成模型可继续处理的 Observation。
        } // 结束空请求保护分支。
        if (request.left() == null || request.right() == null) { // 检查两个操作数是否都由模型提供。
            return "计算失败：操作数不能为空"; // 返回稳定校验信息，而不是让 BigDecimal 运算抛出异常。
        } // 结束操作数缺失保护分支。
        if (request.operator() == null || request.operator().isBlank()) { // 检查模型是否提供了运算符。
            return "计算失败：运算符不能为空"; // 返回模型可以据此修正调用的明确原因。
        } // 结束运算符缺失保护分支。
        String operator = request.operator().trim().toUpperCase(Locale.ROOT); // 统一大小写和首尾空格，扩大合法输入兼容性。
        if ("DIVIDE".equals(operator) && BigDecimal.ZERO.compareTo(request.right()) == 0) { // 在执行除法前显式识别所有数值形式的零。
            return "计算失败：除数不能为零"; // 把数学错误转换成稳定 Observation，允许模型继续总结。
        } // 结束除零保护分支。
        BigDecimal result = switch (operator) { // 根据规范化运算符选择唯一的精确十进制计算分支。
            case "ADD" -> request.left().add(request.right()); // 执行精确加法。
            case "SUBTRACT" -> request.left().subtract(request.right()); // 执行精确减法。
            case "MULTIPLY" -> request.left().multiply(request.right()); // 执行精确乘法。
            case "DIVIDE" -> request.left().divide(request.right(), DIVISION_SCALE, RoundingMode.HALF_UP); // 使用固定精度和四舍五入执行除法。
            default -> null; // 用 null 标记未知运算符，随后生成包含原始值的错误 Observation。
        }; // 结束四则运算选择表达式。
        if (result == null) { // 检查 switch 是否遇到了不受支持的运算符。
            return "计算失败：不支持的运算符 " + operator; // 告诉模型可用能力与当前输入不匹配。
        } // 结束未知运算符分支。
        return result.stripTrailingZeros().toPlainString(); // 移除无意义小数零并避免科学计数法，得到适合自然语言回答的结果。
    } // 结束计算工具方法。

    public record CalculationRequest( // 使用不可变结构描述模型必须提供的计算参数。
            BigDecimal left, // 保存左操作数并保留完整十进制精度。
            BigDecimal right, // 保存右操作数并保留完整十进制精度。
            String operator) { // 保存四种英文运算符之一并结束记录组件列表。
    } // 结束计算请求记录类型。
} // 结束计算器工具定义。
