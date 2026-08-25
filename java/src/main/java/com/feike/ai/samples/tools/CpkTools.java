package com.feike.ai.samples.tools;

import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class CpkTools {

    private static final Logger log = LoggerFactory.getLogger(CpkTools.class);

    /**
     * 样例数据（直径测量，目标 24.11，公差 ±0.09）：
     * [24.072, 24.115, 24.143, 24.089, 24.126, 24.106, 24.078, 24.152, 24.119, 24.087,
     * 24.094, 24.131, 24.069, 24.120, 24.101, 24.138, 24.083, 24.110, 24.097, 24.146,
     * 24.118, 24.088, 24.126, 24.103, 24.094]
     * 子组大小：5
     * USL：24.20
     * LSL：24.00
     * 预期结果：avg=24.108200，整体σ=0.02348226，组内σ=0.02534640，
     * CP=1.3151，CPU=1.2073，CPL=1.4230，CPK=1.2073（CPU&lt;CPL，CPK 取 CPU）
     */
    @Tool(description = "计算样本数据的CPK")
    public static DeviceItemCPKDTO getSubgroupCPKInfo(
            @ToolParam(description = "样本数据") List<Double> data,
            @ToolParam(description = "子组大小") int subgroupSize,
            @ToolParam(description = "USL") Double usl,
            @ToolParam(description = "LSL") Double lsl) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("样本数据为空，无法计算CPK");
        }
        // 添加数据量检查
        if (data.size() < 2) {
            throw new IllegalArgumentException("样本数据量不足，至少需要2个数据点才能计算标准差和CPK");
        }
        if (subgroupSize <= 0) {
            throw new IllegalArgumentException("子组大小 不能小于等于0");
        }

        // 1.0 整体平均值
        double overallMean = calculateMean(data);
        // 1.1 计算整体标准差
        double stdDevAll = calculateStandardDeviation(data,overallMean);

        // 2.0 计算子组平均值和标准差
        // 子组大小小于等于1，使用整体标准差计算
        double pooledStdDev = stdDevAll;
        if (subgroupSize > 1 ){
            List<Double> subgroupMeans = new ArrayList<>();
            List<Double> subgroupStds = new ArrayList<>();

            // 划分子组  方式2：数据量小时，合并最后一组数据  24.11118128
            List<List<Double>> adjustSubgroups = adjustSubgroups(data,subgroupSize);
            // 划分子组, 方式3: 最后一组独立，不合并
            //List<List<Double>> adjustSubgroups = splitSubgroups(data,subgroupSize);
            for (List<Double> subgroup : adjustSubgroups){
                double subgroupMean = calculateMean(subgroup);
                double subgroupStd = calculateStandardDeviation(subgroup, subgroupMean);
                subgroupMeans.add(subgroupMean);
                subgroupStds.add(subgroupStd);
            }

            // 计算每个子组的均值和标准差 划分子组  方式1：：数据量大时 直接舍弃最后一组数据
            /*for (int i = 0; i < data.size() / subgroupSize; i++) {
                List<Double> subgroup = data.subList(i * subgroupSize, (i + 1) * subgroupSize);
                double subgroupMean = calculateMean(subgroup);
                double subgroupStd = calculateStandardDeviation(subgroup, subgroupMean);
                subgroupMeans.add(subgroupMean);
                subgroupStds.add(subgroupStd);
            }*/

            // 计算整体均值和标准差
            // 使用子组的平均值
//            overallMean = calculateMean(subgroupMeans);
            // 计算方差
            double sumSquaredStds = subgroupStds.stream().mapToDouble(std -> std * std).sum();
            // 子组标准差
            pooledStdDev = Math.sqrt(sumSquaredStds / subgroupMeans.size());
            // 使用c4进行校准
//            pooledStdDev = pooledStdDev/0.940;
        }

        if (subgroupSize == 1 ){
            pooledStdDev = movingRange(data);
        }

        Double cp = null;
        Double cpu = null;
        Double cpl = null;
        Double cpk = null;
        if (usl == null){
            cpl = (overallMean - lsl) / (3 * pooledStdDev);
            cpk = cpl;
        } else if (lsl == null) {
            cpu = (usl - overallMean) / (3 * pooledStdDev);
            cpk = cpu;
        } else {
            // 4. 计算CPK(基于组内标准差计算σ)
            cp = (usl - lsl) / (6 * pooledStdDev);
            cpu = (usl - overallMean) / (3 * pooledStdDev);
            cpl = (overallMean - lsl) / (3 * pooledStdDev);
            cpk = Math.min(cpu, cpl);
        }

        // 计算直方图数据
        double[] dataArr = convert(data);
        /*// 计算List<Double>的最小值
        double min = data.stream().min(Double::compare).orElseThrow(() -> new IllegalArgumentException("List cannot be empty"));
        //double min = data.stream().mapToDouble(Double::doubleValue).min();
        double max = data.stream().max(Double::compare).orElseThrow(() -> new IllegalArgumentException("List cannot be empty"));
        if (usl > max) {
            max = usl;
        }
        if (lsl < min) {
            min = lsl;
        }*/
        // 计算动态坐标轴范围（考虑规格限和±4σ）
        // double axisMin = Math.min(mean - 4 * stdDev, LSL - 0.1 * (USL - LSL));
        // double axisMax = Math.max(mean + 4 * stdDev, USL + 0.1 * (USL - LSL));

        double min = (usl == null || lsl == null) ? overallMean - 4 * stdDevAll : Math.min(overallMean - 4 * stdDevAll, lsl - 0.1 * (usl - lsl));
        double max = (usl == null || lsl == null) ? overallMean + 4 * stdDevAll : Math.max(overallMean + 4 * stdDevAll, usl + 0.1 * (usl - lsl));

        // 组数
//        int numBins = calculateNumberOfBins2(data.length);
        int numBins = data.size() > 10000 ? calculateNumBins(dataArr,stdDevAll,min,max) : scottBinSize(data.size(),stdDevAll,min,max);

        // 组距
        double binWidth = calculateBinWidth(dataArr, numBins,min,max);

        // 直方图：组边界点、频次、组中点、直方图数据
        double[] histX = calculateBinWidths(dataArr, numBins,binWidth,min);
        // 频次
        int[] histY = calculateFrequencyCount(dataArr, numBins,binWidth,min);
        // X轴中心点
        double[] centerPoints = calculateCenterPoint(histX);
        List<List<BigDecimal>> histogramData = getHistData(centerPoints,histY);

        // log.info("直方图数据：{}", dto);
        // 拟合曲线
        // DataXY fittingData = gaussianFitting(centerPoints,convertIntToDoubleWithStream(dto.getHistY()));
        DataXY fittingData = normalDistributionFitting(min,max,overallMean,stdDevAll);
        // 根据子组大小拟合曲线
//        double[] dataXMean = calculateSubgroups(dto.getHistX(), subgroupSize);
//        double[] dataYMean = calculateSubgroups(convertIntToDoubleWithStream(dto.getHistY()), subgroupSize);
//        DataXY fittingDataMean = gaussianFitting(dataXMean,dataYMean);
        DataXY fittingDataMean = normalDistributionFitting(min,max,overallMean,pooledStdDev);

        return new DeviceItemCPKDTO(
                round(overallMean, 6), data.size(),
                round(pooledStdDev, 8), round(stdDevAll, 8),
                round(cp, 4), round(cpu, 4), round(cpl, 4), round(cpk, 4),
                min, max, numBins, binWidth,
                histX, histY, histogramData,
                fittingData.dataX(), fittingData.dataY(), fittingData.dataXY(),
                fittingDataMean.dataX(), fittingDataMean.dataY(), fittingDataMean.dataXY());
    }

    /**
     * 计算均值（工具方法）
     */
    private static double calculateMean(List<Double> data) {
        return data.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 计算标准差（σ）（工具方法，样本标准差）
     * @author : F.j
     * @date : 2025/5/22 17:30
     * @param data: 样本数据
     * @param mean: 平均值
     * @return : double
     */
    private static double calculateStandardDeviation(List<Double> data,double mean) {
        double sumSquaredDiff = data.stream()
                .mapToDouble(x -> Math.pow(x - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / (data.size() - 1));
    }

    /**
     * 划分子组（合并最后一组）
     * 调整子组：合并最后一组不完整的数据
     */
    private static List<List<Double>> adjustSubgroups(List<Double> data, int targetSize) {
        List<List<Double>> subgroups = new ArrayList<>();
        int i = 0;
        while (i < data.size()) {
            int end = Math.min(i + targetSize, data.size());
            List<Double> subgroup = data.subList(i, end);

            // 如果最后一组不完整，且不是唯一一组，则合并到前一组
            if (subgroup.size() < targetSize && subgroups.size() > 0) {
                List<Double> lastCompleteSubgroup = subgroups.get(subgroups.size() - 1);
                List<Double> mergedSubgroup = new ArrayList<>(lastCompleteSubgroup);
                mergedSubgroup.addAll(subgroup);
                subgroups.set(subgroups.size() - 1, mergedSubgroup); // 替换前一组合并后的数据
            } else {
                subgroups.add(new ArrayList<>(subgroup));
            }
            i = end;
        }
        return subgroups;
    }

    private static double movingRange(List<Double> data) {
        // 子组大小== 1，使用 移动极差法（Moving Range, MR）计算
        // 1. 计算移动极差（MR）
        //double[] movingRanges = new double[data.size() - 1];
        List<Double> movingRanges = new ArrayList<>();
        for (int i = 0; i < data.size() - 1; i++) {
            movingRanges.add(Math.abs(data.get(i + 1) - data.get(i)));
        }

        // 2. 计算平均移动极差（MR_bar）
        double mrBar = calculateMean(movingRanges);

        // 移动极差与标准差的关系(常数d2取决于移动极差长度，此处为2)
        // 当移动极差基于相邻2个点时,d2≈1.128. 若用更长的极差（如3个点），需查表调整
        // 3. 估计标准差（σ = MR_bar / d2，d2≈1.128）
        // 子组标准差
        return mrBar / 1.128;
    }

    public static double[] convert(List<Double> list) {
        return list.stream()
                .mapToDouble(Double::doubleValue)
                .toArray();
    }

    /**
     * double 类型保留小数 四舍五入
     */
    public static String round(Double value, int scale) {
        if (value == null || value.isNaN() || value.isInfinite()){
            return null;
        }
        BigDecimal bd = new BigDecimal(Double.toString(value));
        bd = bd.setScale(scale, RoundingMode.HALF_UP);
        return bd.toString();
    }

    /**
     *
     * 使用 Freedman-Diaconis 规则计算直方图的组数
     * @param data 数据集
     * @param stdDevAll 整体的标准差
     * @param min 最小值
     * @param max 最大值
     * @return 推荐的组数
     */
    public static int calculateNumBins(double[] data ,double stdDevAll, double min, double max) {
        if (data == null || data.length < 2) {
            return 1;
        }

        // 计算四分位距 (IQR)
        double q1 = calculateQuantile(data, 0.25);
        double q3 = calculateQuantile(data, 0.75);
        double iqr = q3 - q1;

        // 处理 IQR 为 0 的情况（数据可能全部相同或高度集中）
        if (iqr == 0) {
            // 回退到标准差方法
            if (stdDevAll > 0) {
                iqr = 1.34 * stdDevAll; // 使用 1.34 倍标准差作为近似 IQR
            } else {
                // 数据完全相同，返回 1 组
                return 1;
            }
        }

        // 计算数据范围
        //double min = Arrays.stream(data).min().getAsDouble();
        //double max = Arrays.stream(data).max().getAsDouble();
        double range = max - min;

        // 防止范围为 0
        if (range <= 0) {
            return 1;
        }

        // Freedman-Diaconis 公式: binWidth = 2 * IQR * n^(-1/3)
        double binWidth = 2 * iqr / Math.pow(data.length, 1.0/3);

        // 计算组数
        int numBins = (int) Math.ceil(range / binWidth);

        // 确保至少有一个组
        return Math.max(1, numBins);
    }

    /**
     * 使用 Scott 规则计算直方图组数
     * @param n 样本个数
     * @param stdDev 输入数据数组
     * @param min 样本最小值
     * @param max 样本最大值
     * @return 推荐的组数
     */
    public static int scottBinSize(int n,double stdDev,double min, double max) {
        double h = 3.49 * stdDev / Math.pow(n, 1.0/3.0); // Scott 公式计算组距
        if (h == 0) {
            return 1; // 如果标准差为0，所有数据相同，只需1组
        }
        double dataRange = max - min;
        return (int) Math.ceil(dataRange / h);
    }

    /**
     * 计算数据集的指定分位数
     * @param data 数据集
     * @param quantile 分位数值 (0.0-1.0)
     * @return 分位数值
     */
    private static double calculateQuantile(double[] data, double quantile) {
        double[] sortedData = Arrays.copyOf(data, data.length);
        Arrays.sort(sortedData);

        if (quantile <= 0) return sortedData[0];
        if (quantile >= 1) return sortedData[sortedData.length - 1];

        double index = quantile * (sortedData.length - 1);
        int lowerIndex = (int) index;
        int upperIndex = lowerIndex + 1;

        if (upperIndex >= sortedData.length) {
            return sortedData[lowerIndex];
        }

        double fraction = index - lowerIndex;
        return sortedData[lowerIndex] + fraction * (sortedData[upperIndex] - sortedData[lowerIndex]);
    }

    /**
     * 计算组距
     * @author : F.j
     * @date : 2025/5/12 14:03
     * @param data: 样本数据
     * @param numBins: 组数
     * @return : double
     */
    public static double calculateBinWidth(double[] data, int numBins,double min, double max) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Data must not be empty.");
        double epsilon = 1e-10;  // 防止浮点误差
        double width = (max + epsilon - min) / numBins;
        return Math.max(width, 0.0001); // 设置最小组距为 0.01
    }

    /**
     * 计算每组的频数
     * @author : F.j
     * @date : 2025/5/12 14:05
     * @param data: 样本数据
     * @param numBins: 组数
     * @param binWidth: 组距
     * @return : int[]
     */
    public static int[] calculateFrequencyCount(double[] data, int numBins, double binWidth,double min) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Data must not be empty.");
        int[] frequency = new int[numBins];
        for (double value : data) {
            int binIndex = (int) Math.floor((value - min) / binWidth);
            // 强制最大值落在最后一组
            if (binIndex >= numBins) binIndex = numBins - 1;
            // 添加对负索引的处理
            if (binIndex < 0) binIndex = 0;
            if (binIndex < frequency.length) {
                frequency[binIndex]++;
            }
        }
        return frequency;
    }

    /**
     * 正态分布概率密度函数（PDF）：f(x) = (1/(σ√(2π))) · e^(-(x-μ)²/(2σ²))。
     */
    private static double normalDistribution(double x, double mean, double sigma) {
        return (1 / (sigma * Math.sqrt(2 * Math.PI))) * Math.exp(-Math.pow(x - mean, 2) / (2 * sigma * sigma));
    }

    private static DataXY normalDistributionFitting(double min, double max, double mean, double sigma){
        if (Double.isNaN(sigma)){
            log.error("正态分布拟合失败,参数 sigma:{}",sigma);
            return new DataXY(List.of(), List.of(), List.of());
        }
        List<BigDecimal> dataX = new ArrayList<>();
        List<BigDecimal> dataY = new ArrayList<>();
        List<List<BigDecimal>> listXY = new ArrayList<>();
        /*
         * 问题：如果 min 和 max 的差值非常小（例如接近零），则 (max - min) / 100 可能趋近于零，导致 x 几乎不增加，从而造成无限循环。
         */
        double step = Math.max((max - min) / 100, 0.0001); // 设置最小步长为 0.01
        for (double x = min; x <= max; x += step) {
            try {
                double y = normalDistribution(x, mean, sigma);
                BigDecimal tempX = new BigDecimal(x).setScale(6, RoundingMode.HALF_UP);
                BigDecimal tempY = new BigDecimal(y).setScale(6, RoundingMode.HALF_UP);
                dataX.add(tempX);
                dataY.add(tempY);
                // System.out.printf("x = %.2f, y = %.2f%n", x, y);
                List<BigDecimal> temp = new ArrayList<>();
                temp.add(tempX);
                temp.add(tempY);
                listXY.add(temp);
            } catch (Exception e){
                log.error("正态分布拟合失败,参数 x:{},min:{},max：{},mean:{},sigma:{},errorMsg:{}",x,min,max,mean,sigma,e.getMessage());
                //log.error("正态分布拟合失败",e);
            }

        }
        return new DataXY(dataX, dataY, listXY);
    }

    /**
     * 计算中心点
     */
    private static double[] calculateCenterPoint(double[] data) {
        // 计算第1个点和第2个点的中心，第2个点和第3个点的中心点，依此类推
        double[] centerPoints = new double[data.length - 1];
        for (int i = 0; i < data.length - 1; i++) {
            centerPoints[i] = Double.parseDouble(round((data[i] + data[i + 1]) / 2,4));
        }
        return centerPoints;
    }

    /**
     * 生成直方图 X 轴区间点（组边界）：min 起、步长 binWidth，共 numBins+1 个点，供 calculateCenterPoint 取组中点。
     */
    private static double[] calculateBinWidths(double[] data, int numBins, double binWidth, double min) {
        double[] edges = new double[numBins + 1];
        for (int i = 0; i <= numBins; i++) {
            edges[i] = min + i * binWidth;
        }
        return edges;
    }

    /**
     * 生成直方图数据：[区间中心点, 频次] 二元组列表。
     */
    private static List<List<BigDecimal>> getHistData(double[] centerPoints, int[] histY) {
        List<List<BigDecimal>> data = new ArrayList<>();
        for (int i = 0; i < centerPoints.length; i++) {
            List<BigDecimal> point = new ArrayList<>();
            point.add(new BigDecimal(centerPoints[i]));
            point.add(new BigDecimal(histY[i]));
            data.add(point);
        }
        return data;
    }

    /**
     * CPK 计算结果 DTO。
     */
    public record DeviceItemCPKDTO(
            @Schema(description = "样本均值") String avg,
            @Schema(description = "样本N：样本的总个数") Integer total,
            @Schema(description = "标准差（组内σ）") String standardDeviation,
            @Schema(description = "标准差（整体σ）") String standardDeviationAll,
            @Schema(description = "（规格上限-规格下限）/（6σ）") String cp,
            @Schema(description = "（规格上限-样本均值）/（3σ）") String cpu,
            @Schema(description = "（样本均值-规格下限）/（3σ）") String cpl,
            @Schema(description = "MIN{(规格上限-样本均值)/(3σ),（样本均值-规格下限）/（3σ）}") String cpk,
            @Schema(description = "最小值") Double min,
            @Schema(description = "最大值") Double max,
            @Schema(description = "组数") Integer numBins,
            @Schema(description = "组距") Double binWidth,
            @Schema(description = "直方图x 区间点") double[] histX,
            @Schema(description = "直方图y") int[] histY,
            @Schema(description = "直方图数据") List<List<BigDecimal>> histogramData,
            @Schema(description = "拟合X") List<BigDecimal> fittingX,
            @Schema(description = "拟合Y") List<BigDecimal> fittingY,
            @Schema(description = "拟合XY") List<List<BigDecimal>> fittingXY,
            @Schema(description = "均值拟合X") List<BigDecimal> avgFittingX,
            @Schema(description = "均值拟合Y") List<BigDecimal> avgFittingY,
            @Schema(description = "均值拟合XY") List<List<BigDecimal>> avgFittingXY
    ) {}

    /**
     * 正态分布拟合曲线的 XY 数据。
     */
    private record DataXY(
            @Schema(description = "X轴数据") List<BigDecimal> dataX,
            @Schema(description = "Y轴数据") List<BigDecimal> dataY,
            @Schema(description = "XY数据") List<List<BigDecimal>> dataXY
    ) {}


}
