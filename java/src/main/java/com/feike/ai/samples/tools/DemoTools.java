package com.feike.ai.samples.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 无副作用演示工具。拷进业务项目时把实现换成真实服务，并保证幂等或可重试。
 */
@Component
public class DemoTools {

    /**
     * 查询城市天气；仅返回写死的演示数据，避免样例依赖外部天气 API。
     *
     * @param city 城市名，例如北京
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
     * 两数求和，用来演示多工具编排。
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
