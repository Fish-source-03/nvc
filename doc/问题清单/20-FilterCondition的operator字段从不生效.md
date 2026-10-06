# 20 · FilterCondition 的 operator 字段从不生效

> **严重程度**：🟡 中
> **所属模块**：agent-qr-rag（StructuredFilterService、FilterCondition）
> **设计依据**：《系统详细设计说明书》§8.11.1 StructuredFilterService（按 operator 分派 GT/LT/LTE 等）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`FilterCondition` 定义了完整的比较运算符常量（`OP_GT` / `OP_GTE` / `OP_LT` / `OP_LTE`）与对应的 `operator` 字段，但 `StructuredFilterService` 在分派过滤条件时**只按 `fieldType`（NUMBER/DATE/ENUM/STRING）分支，从不读取 `operator`**。

后果：一个形如"小于 5000"的条件，会被当作一个**闭区间**处理——下限 `parseMinNumber(condition)` 与上限 `parseMaxNumber(condition)` 都取 `condition.getValue()`，即退化为 `field = 5000` 的等值查询，与用户意图完全不符。

该缺陷目前被文档 12 掩盖（`FilterCondition` 从未被构造，链路不可达），但一旦按方案文档接入 `FilterConditionExtractor`，它会立即导致**所有带运算符的条件被错误解析**。

---

## 二、推断依据

### 依据 1：`operator` 字段与常量已定义

`agent-qr-rag/src/main/java/org/example/agent_qr/rag/filter/FilterCondition.java`

```java
:29    private String operator;                 // 字段已定义
:42    public static final String OP_GT  = "GT";
:43    public static final String OP_GTE = "GTE";
:44    public static final String OP_LT  = "LT";
:45    public static final String OP_LTE = "LTE";
```

### 依据 2：分派逻辑只读 fieldType

`agent-qr-rag/.../filter/StructuredFilterService.java:107-125`

```java
private List<Long> dispatchCondition(FilterCondition condition) {
    return switch (condition.getFieldType()) {          // ← 只按 fieldType 分派
        case "NUMBER" -> {
            BigDecimal min = parseMinNumber(condition);
            BigDecimal max = parseMaxNumber(condition);
            yield chunkStructuredFilterMapper.selectChunkIdsByNumberRange(
                    condition.getFieldName(), min, max);
        }
        case "DATE" -> { ... }
        case "ENUM", "STRING" -> chunkStructuredFilterMapper.selectChunkIdsByStringValue(
                condition.getFieldName(), condition.getValue());
        default -> List.<Long>of();
    };
}
```

`condition.getOperator()` 在整个方法体内**未被调用**。

### 依据 3：上下限取值均回退到 value，导致区间退化

`StructuredFilterService.java:127-140`

```java
private BigDecimal parseMinNumber(FilterCondition c) {
    try {
        return new BigDecimal(c.getMinValue() != null ? c.getMinValue() : c.getValue());
    } catch (Exception e) {
        return BigDecimal.ZERO;
    }
}

private BigDecimal parseMaxNumber(FilterCondition c) {
    try {
        return new BigDecimal(c.getMaxValue() != null ? c.getMaxValue() : c.getValue());
    } catch (Exception e) {
        return new BigDecimal("999999999");
    }
}
```

当 `minValue` / `maxValue` 均为 null（由 LLM 提取器产出的典型形态：只有 value + operator）时：

- `min` = value，`max` = value → SQL 条件为 `field BETWEEN value AND value`，即**等值匹配**；
- "大于 10000"（OP_GT）会被解析为 `field = 10000`；
- "小于 5000"（OP_LT）同样被解析为 `field = 5000`。

注：`parseMaxNumber` 的 `999999999` 兜底只在**数值解析抛异常**时触发，不是 LT 的正常路径——本缺陷的典型表现是"区间退化为等值"，而非"上限变成 999999999"。

### 依据 4：无测试覆盖

`agent-qr-rag/src/test` 不存在，方案文档 §7.1 规划的 `FilterConditionExtractorTest` 未创建。该类缺陷（运算符语义错误）无法被编译或静态检查发现。

---

## 三、影响范围

1. **当前**：因 `FilterCondition` 从未被构造（文档 12），该缺陷**尚未产生实际影响**——属于"潜伏缺陷"。
2. **接入提取器后立即爆发**：`doc/未来补充/结构化字段过滤SQL-LLM自动提取启用方案.md` 的 Step 0 就是实现 `FilterConditionExtractor`。若不先修本缺陷，灰度开启后会立刻出现"问大于 1 万，返回恰好等于 1 万的记录"这类错误，且**结果看起来是正常返回而非报错**，难以定位。
3. **误导实施排期**：方案文档假设 `StructuredFilterService` 已就绪（"基础设施已就绪"），实际它的运算符支持是缺失的——**补 `FilterConditionExtractor` 的前提条件比方案文档描述的更多一条**。

---

## 四、修复方向

1. **让 `dispatchCondition` 读取 `operator`**：NUMBER 类型应按操作符生成正确的区间或比较条件：
   - `OP_GT` → `(value, +∞)`，即 `min = value`（SQL 用 `>` 而非 `>=`，需 Mapper 支持）
   - `OP_GTE` → `[value, +∞)`
   - `OP_LT` → `(-∞, value)`
   - `OP_LTE` → `(-∞, value]`
   - 无 operator（区间查询）→ 维持现有 `[minValue, maxValue]` 语义
   **注意**：当前 `selectChunkIdsByNumberRange` 是闭区间语义，需评估是否新增支持开区间的 SQL 方法。
2. **补 `operator` 的必填校验**：`FilterConditionExtractor` 产出条件时应保证 operator 合法，非法值应显式报错而非静默退化。
3. **补测试**：至少覆盖 GT/GTE/LT/LTE 四种运算符的边界值。

---

## 五、核查边界

- 静态分析，未运行过滤、未实测"大于/小于"类条件的返回结果。
- 未确认 `selectChunkIdsByNumberRange` 的 SQL 是开区间还是闭区间（`BETWEEN` 为闭区间，但需实际查看 SQL 确认）——修复时需先核对该细节。
- 该缺陷目前无实际影响（链路不可达），严重程度取决于文档 12 的修复排期。
