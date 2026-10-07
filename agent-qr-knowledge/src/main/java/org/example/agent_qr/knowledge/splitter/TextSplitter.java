package org.example.agent_qr.knowledge.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本切片器，将长文本按语义边界切割为适合向量化的短片段。
 * <p>
 * 核心策略：先按空行（段落）分割，对长段落使用滑动窗口裁剪，
 * 在断点附近优先选择自然断句位置，最后合并过短的片段。
 * </p>
 * <p>
 * 批次 06 · 任务 6.1.5（问题 10 第三层）：新增<b>表格感知切片</b>。
 * 表格（{@code [TBL]}…{@code [/TBL]} 标记块，或无标记的裸 Markdown 表格——
 * 后者来自 {@code DocxParser.tableToMarkdown()}）是原子语义单元，
 * 不再被滑动窗口拦腰截断：
 * </p>
 * <ul>
 *   <li>表格长度 ≤ {@code rag.chunk-size} → 作为整体进入单个切片；</li>
 *   <li>表格超长 → 按行拆分，<b>每个片段重复表头</b>，并用
 *       {@code [TBL:n/m]} / {@code [/TBL:n/m]} 标注片段序号（设计方案第三层）。</li>
 * </ul>
 * <p>
 * 与设计方案的一点实现差异：设计方案描述的是"提取表格 → 占位符骨架 → 还原"三步，
 * 本实现改为<b>按表格边界直接分段</b>（表格段与文本段各自处理后按原顺序拼回），
 * 省去占位符还原，也避免占位符恰落在滑动窗口断点上被切碎的风险。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Service
public class TextSplitter {

    /** 每个切片的最大字符数 */
    @Value("${rag.chunk-size:500}")
    private int chunkSize;

    /** 相邻切片之间的重叠字符数 */
    @Value("${rag.chunk-overlap:50}")
    private int chunkOverlap;

    /** 是否启用表格感知切片（批次 06 · 任务 6.1.5） */
    @Value("${rag.table-aware-split:true}")
    private boolean tableAwareSplit;

    /** 单个表格片段最多保留的数据行数（不含表头/分隔行）；≤0 表示不限制 */
    @Value("${rag.table-max-rows-per-chunk:15}")
    private int tableMaxRowsPerChunk;

    /** 过短切片阈值，小于此长度的切片将合并到前一个切片 */
    private static final int SHORT_CHUNK_THRESHOLD = 100;

    /** 表格块标记（与 {@code PdfParser} 的约定一致） */
    private static final String TABLE_START = "[TBL]";
    private static final String TABLE_END = "[/TBL]";

    /** 带标记的表格块：{@code [TBL]…[/TBL]}（跨行，非贪婪） */
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            Pattern.quote(TABLE_START) + "(.*?)" + Pattern.quote(TABLE_END), Pattern.DOTALL);

    /** 裸 Markdown 表格成块的最小行数（表头 + 至少一行） */
    private static final int MIN_TABLE_LINES = 2;

    /**
     * 将文本分割为切片列表。
     *
     * @param text 原始文本
     * @return 切片列表
     */
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<String> chunks;
        if (tableAwareSplit) {
            // 表格感知路径：按"文本段 / 表格段"分段，各自切片后按原顺序拼回
            chunks = new ArrayList<>();
            for (Segment segment : preprocessTables(text)) {
                if (segment.table()) {
                    chunks.addAll(splitLargeTable(segment.content()));
                } else {
                    chunks.addAll(splitPlainText(segment.content()));
                }
            }
        } else {
            // 关闭表格感知时保持原行为
            chunks = splitPlainText(text);
        }

        // 合并过短片段
        chunks = mergeShortChunks(chunks);

        log.debug("文本切片完成: 总字符数={}, 切片数={}", text.length(), chunks.size());
        return chunks;
    }

    // ==================== 表格感知（批次 06 · 任务 6.1.5） ====================

    /**
     * 把文本切分为有序的"文本段 / 表格段"序列。
     * <p>
     * 表格段有两种来源：显式 {@code [TBL]…[/TBL]} 标记（PdfParser 产出），
     * 以及连续 ≥{@value #MIN_TABLE_LINES} 行、每行以 {@code |} 开头的裸 Markdown 表格
     * （DocxParser 产出，无标记）。
     * </p>
     *
     * @param text 原始文本
     * @return 有序分段列表
     */
    private List<Segment> preprocessTables(String text) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = TABLE_BLOCK.matcher(text);
        int pos = 0;
        while (matcher.find()) {
            appendTextSegments(segments, text.substring(pos, matcher.start()));
            segments.add(new Segment(true, matcher.group(0)));
            pos = matcher.end();
        }
        appendTextSegments(segments, text.substring(pos));
        return segments;
    }

    /**
     * 在纯文本中识别裸 Markdown 表格块，拆成文本段与表格段。
     */
    private void appendTextSegments(List<Segment> segments, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int plainStart = 0;
        int i = 0;
        while (i < text.length()) {
            int lineEnd = text.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            if (looksLikeMarkdownTableLine(text.substring(i, lineEnd))) {
                int runStart = i;
                int runEnd = i;
                int runLines = 0;
                int cursor = i;
                while (cursor < text.length()) {
                    int e = text.indexOf('\n', cursor);
                    if (e < 0) {
                        e = text.length();
                    }
                    if (!looksLikeMarkdownTableLine(text.substring(cursor, e))) {
                        break;
                    }
                    runLines++;
                    runEnd = e;
                    if (e >= text.length()) {
                        break;
                    }
                    cursor = e + 1;
                }
                if (runLines >= MIN_TABLE_LINES) {
                    appendPlain(segments, text.substring(plainStart, runStart));
                    segments.add(new Segment(true, text.substring(runStart, runEnd)));
                    plainStart = runEnd;
                    i = runEnd;
                    continue;
                }
            }
            if (lineEnd >= text.length()) {
                break;
            }
            i = lineEnd + 1;
        }
        appendPlain(segments, text.substring(plainStart));
    }

    private void appendPlain(List<Segment> segments, String text) {
        if (text != null && !text.isEmpty()) {
            segments.add(new Segment(false, text));
        }
    }

    /**
     * 判断一行是否像 Markdown 表格行：以 {@code |} 开头且至少含两个 {@code |}。
     */
    private boolean looksLikeMarkdownTableLine(String line) {
        String trimmed = line.trim();
        return trimmed.length() >= 3
                && trimmed.charAt(0) == '|'
                && trimmed.indexOf('|', 1) > 0;
    }

    /**
     * 表格切片：整体保留；超长时按行拆分并重复表头。
     *
     * @param tableBlock 表格段（可能带 {@code [TBL]} / {@code [/TBL]} 标记）
     * @return 表格切片列表（按片段顺序）
     */
    List<String> splitLargeTable(String tableBlock) {
        boolean marked = tableBlock.startsWith(TABLE_START);
        String body = tableBlock;
        if (marked) {
            int bodyStart = TABLE_START.length();
            int bodyEnd = tableBlock.endsWith(TABLE_END)
                    ? tableBlock.length() - TABLE_END.length()
                    : tableBlock.length();
            body = tableBlock.substring(bodyStart, bodyEnd);
        }

        List<String> rows = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                rows.add(line.trim());
            }
        }
        if (rows.isEmpty()) {
            return List.of(tableBlock);
        }

        String header = rows.get(0);
        String separator = null;
        int dataStart = 1;
        if (rows.size() > 1 && isSeparatorRow(rows.get(1))) {
            separator = rows.get(1);
            dataStart = 2;
        }

        // 未超长 → 整个表格作为一个切片
        if (tableBlock.length() <= chunkSize) {
            return List.of(tableBlock);
        }

        int headerLength = header.length() + (separator == null ? 0 : separator.length() + 1);
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = headerLength;
        for (int i = dataStart; i < rows.size(); i++) {
            String row = rows.get(i);
            boolean tooManyRows = tableMaxRowsPerChunk > 0 && current.size() >= tableMaxRowsPerChunk;
            boolean tooLong = !current.isEmpty() && currentLength + row.length() + 1 > chunkSize;
            if (tooManyRows || tooLong) {
                groups.add(current);
                current = new ArrayList<>();
                currentLength = headerLength;
            }
            current.add(row);
            currentLength += row.length() + 1;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        // 行数不足以拆出多个片段（如单行超长）→ 保持整表不拆
        if (groups.size() <= 1) {
            return List.of(tableBlock);
        }

        List<String> fragments = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("[TBL:").append(i + 1).append('/').append(groups.size()).append("]\n");
            sb.append(header).append('\n');
            if (separator != null) {
                sb.append(separator).append('\n');
            }
            for (String row : groups.get(i)) {
                sb.append(row).append('\n');
            }
            sb.append("[/TBL:").append(i + 1).append('/').append(groups.size()).append(']');
            fragments.add(sb.toString());
        }
        return fragments;
    }

    /**
     * 判断是否为 Markdown 分隔行（形如 {@code | --- | --- |}）。
     */
    private boolean isSeparatorRow(String row) {
        String normalized = row.replace(" ", "");
        if (!normalized.startsWith("|") || !normalized.endsWith("|")) {
            return false;
        }
        return normalized.matches("\\|[-:|]+\\|");
    }

    /**
     * 普通文本切片：沿用原有逻辑（按空行分段 + 长段落滑动窗口）。
     */
    private List<String> splitPlainText(String text) {
        String[] paragraphs = text.split("\n\n");
        List<String> chunks = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > chunkSize) {
                // 长段落使用滑动窗口切片
                chunks.addAll(splitLongText(trimmed));
            } else {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }

    /**
     * 文本段 / 表格段（批次 06 · 任务 6.1.5）。
     *
     * @param table   true 表示表格段
     * @param content 段内容（表格段含 {@code [TBL]} 标记或裸 Markdown 表格）
     */
    private record Segment(boolean table, String content) {
    }

    // ==================== 原有实现（保持行为不变） ====================

    /**
     * 使用滑动窗口对长文本进一步切片。
     * <p>
     * 窗口大小 = chunkSize，步长 = chunkSize - chunkOverlap。
     * 在窗口末尾附近寻找自然断点以获得更语义化的切片。
     * </p>
     *
     * @param text 长文本
     * @return 切片列表
     */
    List<String> splitLongText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // 如果还没到文本末尾，在 end 附近寻找更好的断点
            if (end < length) {
                end = findBreakPoint(text, end);
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 已到达文本末尾，退出循环
            if (end >= length) {
                break;
            }

            // 下一次起始位置 = 当前结束位置 - 重叠量
            // 同时保证 start 严格递增，防止死循环
            start = Math.max(start + 1, end - chunkOverlap);
        }

        return chunks;
    }

    /**
     * 在目标位置附近寻找最自然的语义断点。
     * <p>
     * 在 {@code [end-50, end]} 范围内搜索断点，优先级如下：
     * 句号 &gt; 换行 &gt; 感叹号 &gt; 问号 &gt; 分号 &gt; 中文分号 &gt; 逗号 &gt; 空格。
     * 若找不到任何断点，返回原 end 值。
     * </p>
     *
     * @param text 文本
     * @param end  目标结束位置
     * @return 最优断点位置
     */
    int findBreakPoint(String text, int end) {
        int searchStart = Math.max(end - 50, 0);

        // 按优先级查找断点
        char[] breakChars = {'。', '\n', '！', '？', ';', '；', '，', ' '};
        for (char ch : breakChars) {
            int pos = text.lastIndexOf(ch, end);
            if (pos >= searchStart) {
                // 对于换行和空格，断点在之后
                return (ch == '\n' || ch == ' ') ? pos + 1 : pos + 1;
            }
        }

        return end;
    }

    /**
     * 合并过短的切片到前一个切片中。
     * <p>
     * 长度小于 100 字符的切片将被合并到它前面的切片末尾。
     * 如果第一个切片就过短，则保留原样。
     * </p>
     *
     * @param chunks 原始切片列表
     * @return 合并后的切片列表
     */
    List<String> mergeShortChunks(List<String> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String current = chunks.get(i);
            if (current.length() < SHORT_CHUNK_THRESHOLD && !result.isEmpty()) {
                // 合并到前一条
                int lastIndex = result.size() - 1;
                result.set(lastIndex, result.get(lastIndex) + "\n" + current);
            } else {
                result.add(current);
            }
        }

        return result;
    }
}
