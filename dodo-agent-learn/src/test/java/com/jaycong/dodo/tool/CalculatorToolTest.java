package com.jaycong.dodo.tool; // 将计算器测试放在对应工具包中。

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolTest { // 定义精确十进制计算和错误边界测试。

    private final CalculatorTool tool = new CalculatorTool(); // 创建真实计算器以验证实际业务规则。

    @Test
    void calculatesFourSupportedOperations() { // 验证四种教学运算都使用十进制精确语义。
        assertThat(tool.calculate(request("0.1", "0.2", "ADD"))).isEqualTo("0.3"); // 验证加法没有浮点误差。
        assertThat(tool.calculate(request("5", "2", "SUBTRACT"))).isEqualTo("3"); // 验证减法结果。
        assertThat(tool.calculate(request("1.5", "4", "MULTIPLY"))).isEqualTo("6"); // 验证乘法并移除无意义零。
        assertThat(tool.calculate(request("1", "3", "DIVIDE"))).isEqualTo("0.33333333"); // 验证除法采用八位小数规则。
    } // 结束受支持运算测试。

    @Test
    void returnsStableErrorsForInvalidRequests() { // 验证业务错误都转换为可回填给模型的 Observation。
        assertThat(tool.calculate(request("1", "0", "DIVIDE"))).isEqualTo("计算失败：除数不能为零"); // 验证除零保护。
        assertThat(tool.calculate(request("1", "2", "POWER"))).isEqualTo("计算失败：不支持的运算符 POWER"); // 验证未知运算符。
        assertThat(tool.calculate(null)).isEqualTo("计算失败：计算参数不能为空"); // 验证整个参数对象缺失。
        assertThat(tool.calculate(new CalculatorTool.CalculationRequest(null, BigDecimal.TWO, "ADD"))).isEqualTo("计算失败：操作数不能为空"); // 验证缺失操作数。
    } // 结束非法请求测试。

    private CalculatorTool.CalculationRequest request(String left, String right, String operator) { // 简化测试中的参数对象构造。
        return new CalculatorTool.CalculationRequest(new BigDecimal(left), new BigDecimal(right), operator); // 把文本操作数转换为精确十进制请求。
    } // 结束测试请求工厂方法。
} // 结束计算器工具测试类。
