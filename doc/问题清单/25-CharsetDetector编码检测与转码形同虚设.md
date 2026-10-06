# 25 · CharsetDetector 编码检测与转码形同虚设

> **严重程度**：🟡 中
> **所属模块**：agent-qr-data-quality（CharsetDetector、EncodingRule）
> **设计依据**：《系统详细设计说明书》§17.5 字符集自动检测与转码
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

设计 §17.5 定义了四步流程：① juniversalchardet 检测编码 → ② 置信度 ≥ 0.8 时采纳 → ③ 转码为 UTF-8 → ④ 写入质检报告。

实际实现存在三处偏差：

1. **检测入口的输入类型错误**：`detect(String text)` 接收的是**已解码的 String**，方法内又用 JVM 默认字符集**重新编码为字节**再交给检测器——原始字节流的编码信息在传入前就已丢失，检测的实际上是"默认字符集"，而非文件真实编码。
2. **置信度分支未实现**：juniversalchardet 的 `UniversalDetector` 本身不暴露置信度（只返回 charset 名或 null），设计要求的"≥ 0.8 才采纳"没有对应实现。
3. **回退逻辑永远返回 UTF-8**：回退循环用 `new String(text.getBytes(charset), charset)` 与原串比较，而列表首项是 UTF-8——对任何合法 Java String，UTF-8 的编码/解码往返都无损，因此该循环**总是返回 UTF-8**，无法区分编码。

此外，§17.5 步骤 ③ "转码为 UTF-8"在代码中**完全没有实现**——全仓库检索 `转码` / `transcode` 零命中，实际只做"标记"，不做转换。

---

## 二、推断依据

### 依据 1：检测输入是 String，且经过二次编码

`agent-qr-data-quality/src/main/java/org/example/agent_qr/dataquality/util/CharsetDetector.java:34-44`

```java
public String detect(String text) {                    // ← 入参已是解码后的 String
    if (text == null || text.isEmpty()) {
        return "UTF-8";
    }

    byte[] bytes = text.getBytes(Charset.defaultCharset());   // ← 用 JVM 默认字符集重新编码

    UniversalDetector detector = new UniversalDetector(null);
    detector.handleData(bytes, 0, bytes.length);
    detector.end();  // (示意)
    detector.dataEnd();

    String detectedCharset = detector.getDetectedCharset();
```

编码检测的原理是分析**原始字节序列**的特征。此处字节来自"把已解码的 String 按默认字符集重新编码"，因此：

- 若文件原本是 GBK、上游用错误编码读成了 String，则该 String 内容已经损坏，此处再编码只是把损坏内容固化，检测无法还原真相；
- 在 UTF-8 的 JVM 上，重新编码的结果**总是 UTF-8 字节**，检测器自然报 UTF-8——即在最常见的部署环境下，该方法恒返回 UTF-8。

### 依据 2：回退循环无法区分编码

`CharsetDetector.java:48-63`

```java
for (String charset : FALLBACK_CHARSETS) {        // ["UTF-8", "GBK", "GB2312", "ISO-8859-1", "Windows-1252"]
    try {
        byte[] testBytes = text.getBytes(charset);
        String decoded = new String(testBytes, charset);
        if (text.equals(decoded)) {
            return charset;                        // ← 首次命中即返回，而 UTF-8 排第一
        }
    } catch (Exception ignored) { }
}
return "UTF-8";
```

首项 UTF-8 对任何合法 String 都能无损往返，因此 `text.equals(decoded)` 在第一次迭代即为 true，直接返回 `"UTF-8"`。循环的后续迭代永远不会执行，判别力为零。

### 依据 3：置信度分支不存在

```bash
grep -n "confidence\|0.8" CharsetDetector.java
# 仅命中 :42 的日志字符串 "confidence=high"（写死的字面量），无任何实际阈值判断
```

### 依据 4：转码环节完全缺失

```bash
grep -rn "转码\|transcode" agent-qr-data-quality agent-qr-etl agent-qr-common
# 零命中
```

§17.5 步骤 ③ 未实现。步骤 ④（写入质检报告）由 `EncodingRule` 的失败项部分覆盖，但只记录"疑似编码异常"，不做转换。

---

## 三、影响范围

1. **GBK/GB2312 数据源可能乱码入库**：非 UTF-8 编码的数据源在被读取时若未正确解码，错误会被一路带进 `CanonicalRecord` 与向量库，且质检环节**不会拦截**（检测恒返回 UTF-8）。
2. **脏数据污染检索**：乱码文本进入知识库后，其向量质量极差，且会以"相关结果"的形式出现在回答中。
3. **§17.5 能力虚标**：文档、测试方案中的"字符集自动检测与转码"实际只有检测的壳，没有转码。
4. **与文档 21 叠加**：数据源同步性能改造后吞吐提升，若编码问题未修，脏数据的产生速度也会同步提升。

---

## 四、修复方向

1. **检测应在字节层进行**：`detect` 的入参应改为 `byte[]`（或 `InputStream`），在**读取文件/流的第一时间**检测，而非在 String 阶段。
2. **上游读取时使用检测结果**：检测到编码后用该编码解码，再用 UTF-8 写入下游——即把 §17.5 的步骤 ②③ 真正串起来。
3. **重构回退逻辑**：若要保留启发式回退，应改用可判别的方式（如统计非法字节序列数量、检查 BOM、尝试解码后统计替换字符 `�` 的数量），而不是"编码/解码往返相等"（该条件恒真）。
4. **补测试**：至少覆盖 GBK / GB2312 / UTF-8-BOM 三类样本文件的检测正确性。

---

## 五、核查边界

- 静态分析，未构造真实编码样本文件验证检测结果。
- 未确认上游（`DataSyncEtlListener`、数据源连接器）读取文件时实际使用的编码——这决定本缺陷的实际影响面。若上游已固定用 UTF-8 读取，则 GBK 文件在进入本方法前就已损坏，本方法无论如何都无法挽救。
- juniversalchardet 的 `UniversalDetector` 在当前版本是否提供置信度 API，未核实（若提供，实现方式会不同）。
