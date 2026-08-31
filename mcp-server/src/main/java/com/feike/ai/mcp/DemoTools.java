package com.feike.ai.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 无副作用演示工具（与主应用 DemoTools 对齐，便于对照学习）。
 */
@Component
public class DemoTools {

    /**
     * 查询城市天气；仅返回写死的演示数据。
     *
     * @param city 城市名
     * @return 模拟天气文案
     */
    @Tool(description = "查询指定城市的天气。演示用，返回模拟数据。")
    public String getWeather(@ToolParam(description = "城市名，例如 北京") String city) {
        if (city == null || city.isBlank()) {
            return "请提供城市名";
        }
        return switch (city.trim()) {
            case "北京" -> "北京：晴，25°C，北风 2 级";
            case "上海" -> "上海：多云，28°C，东南风 3 级";
            default -> city.trim() + "：阴，22°C";
        };
    }

    /**
     * 两数求和。
     *
     * @param a 第一个加数
     * @param b 第二个加数
     * @return 和
     */
    @Tool(description = "计算两个数字的和")
    public double add(
        @ToolParam(description = "第一个加数") double a,
        @ToolParam(description = "第二个加数") double b
    ) {
        return a + b;
    }
}
