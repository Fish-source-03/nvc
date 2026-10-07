package org.example.agent_qr.knowledge.splitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本切片器表格感知测试（批次 06 · 任务 6.1.5 / 6.1.6，问题 10 第三层）。
 * <p>
 * <b>拦截的缺陷</b>：{@code TextSplitter} 不感知表格边界——Markdown 表格行之间是
 * {@code \n} 而非 {@code \n\n}，整个表格被视为一个段落；一旦超过 {@code chunkSize}，
 * 滑动窗口会在表格中间截断，LLM 看到的是残缺的行列数据。
 * 该缺陷同时削弱了 {@code DocxParser.tableToMarkdown()}（已正确实现）的收益。
 * </p>
 *
 * @author agent-qr
 */
class TextSplitterTest {

    private TextSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new TextSplitter();
        ReflectionTestUtils.setField(splitter, "chunkSize", 300);
        ReflectionTestUtils.setField(splitter, "chunkOverlap", 50);
        ReflectionTestUtils.setField(splitter, "tableAwareSplit", true);
        ReflectionTestUtils.setField(splitter, "tableMaxRowsPerChunk", 15);
    }

    @Test
    @DisplayName("★ 表格长度不超过 chunkSize：整个表格作为一个切片，行/表头/分隔行完整保留")
    void split_shouldKeepTableIntact_whenTableFitsChunk() {
        String table = markedTable(3, "研发一部");
        String text = "表格前的说明文字。\n\n" + table + "\n\n表格后的说明文字。";

        List<String> chunks = splitter.split(text);

        assertThat(chunks)
                .as("表格必须完整出现在同一个切片中，且带标记")
                .anySatisfy(chunk -> assertThat(chunk).contains(table));
        assertThat(chunks)
                .as("表格内容不得被拆到两个切片（开标记与闭标记必须同片）")
                .noneMatch(chunk -> chunk.contains("[TBL]") && !chunk.contains("[/TBL]"));
    }

    @Test
    @DisplayName("★ 超长表格不被拦腰截断：按行拆分且每个片段都重复表头，带 [TBL:n/m] 片段标记")
    void split_shouldSplitOversizedTable_withHeaderRepeated() {
        int rows = 40;
        String table = markedTable(rows, "研发一部");
        String lines = table.substring("[TBL]\n".length(), table.length() - "[/TBL]".length());
        String body = lines.strip();
        List<String> dataRows = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.contains("研发一部")) {
                dataRows.add(line.trim());
            }
        }
        assertThat(dataRows).hasSize(rows);

        List<String> chunks = splitter.split(table);

        List<String> fragments = chunks.stream().filter(c -> c.contains("[TBL:")).toList();
        assertThat(fragments)
                .as("超长表格必须被拆成多个片段（修复前滑动窗口会把表格行拆散）")
                .hasSizeGreaterThan(1);

        for (String fragment : fragments) {
            assertThat(fragment)
                    .as("每个表格片段必须带上表头与分隔行（保留表头分段策略）")
                    .contains("| 序号 | 部门 | 备注 |")
                    .contains("| --- | --- | --- |");
            assertThat(fragment).matches("(?s).*\\[TBL:\\d+/" + fragments.size() + "\\].*");
        }

        // 数据行一行不丢、且不重复：全部出现在片段中
        for (String row : dataRows) {
            long occurrences = fragments.stream().filter(f -> f.contains(row)).count();
            assertThat(occurrences)
                    .as("数据行 %s 必须且只能出现一次", row)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("★ DOCX 收益：无标记的裸 Markdown 表格同样被识别，超长时按表头分段（第③层对 DOCX 生效）")
    void split_shouldRecognizeBareMarkdownTable_fromDocx() {
        // DocxParser.tableToMarkdown() 的产出形态：无 [TBL] 标记
        List<String> dataRows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            dataRows.add("| 员工" + i + " | 部门" + i + " | 备注说明文本" + i + " |");
        }
        String bareTable = "| 姓名 | 部门 | 备注 |\n| --- | --- | --- |\n" + String.join("\n", dataRows) + "\n";

        List<String> chunks = splitter.split("会议纪要如下：\n\n" + bareTable);

        List<String> tableChunks = chunks.stream()
                .filter(c -> c.contains("| 姓名 | 部门 | 备注 |"))
                .toList();
        assertThat(tableChunks)
                .as("裸 Markdown 表格也必须被识别并按表头分段（否则 DOCX 表格仍会被截断）")
                .hasSizeGreaterThan(1);
        for (String chunk : tableChunks) {
            assertThat(chunk).contains("| --- | --- | --- |");
        }
        for (String row : dataRows) {
            assertThat(tableChunks.stream().filter(c -> c.contains(row)).count())
                    .as("DOCX 表格数据行 %s 不得丢失或重复", row)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("回归：无表格文本的切片结果与关闭表格感知时完全一致（不改变既有切片行为）")
    void split_shouldKeepLegacyBehavior_forPlainText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("这是第").append(i).append("段普通文本，用于验证无表格时切片行为不变。")
              .append("句子需要足够长以便触发滑动窗口。\n\n");
        }
        String text = sb.toString();

        List<String> aware = splitter.split(text);
        ReflectionTestUtils.setField(splitter, "tableAwareSplit", false);
        List<String> legacy = splitter.split(text);

        assertThat(aware)
                .as("无表格时不得出现任何表格标记")
                .noneMatch(chunk -> chunk.contains("[TBL]"));
        assertThat(aware)
                .as("无表格文本的两条路径结果必须一致（仅表格处理被新增）")
                .isEqualTo(legacy);
    }

    @Test
    @DisplayName("回归：滑动窗口死循环修复不得回退（无断点文本 + overlap > chunkSize 也必须终止）")
    @Timeout(15)
    void split_shouldTerminate_forTextWithoutBreakPoints() {
        String text = "A".repeat(1000);

        // overlap > chunkSize：旧实现下 start 可能不前进，导致死循环
        ReflectionTestUtils.setField(splitter, "chunkSize", 100);
        ReflectionTestUtils.setField(splitter, "chunkOverlap", 200);
        List<String> chunks = splitter.split(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1))
                .as("最后一片必须覆盖到文本末尾")
                .endsWith("A");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(100));
    }

    @Test
    @DisplayName("表格前后文本正常切片，表格片段不与正文混淆")
    void split_shouldKeepSurroundingTextAlongsideTableFragments() {
        String table = markedTable(40, "财务部");
        String text = "前文段落内容需要足够长以避免被合并处理，这里补充一些填充说明文字。\n\n"
                + table + "\n\n后文段落同样需要足够的长度，保证它独立成为普通文本切片。";

        List<String> chunks = splitter.split(text);

        assertThat(chunks).anySatisfy(c -> assertThat(c).contains("前文段落内容"));
        assertThat(chunks).anySatisfy(c -> assertThat(c).contains("后文段落同样需要"));
        assertThat(chunks.stream().filter(c -> c.contains("| 序号 | 部门 | 备注 |")).toList())
                .hasSizeGreaterThan(1);
    }

    // ==================== 辅助 ====================

    /** 构造带 [TBL] 标记的 Markdown 表格（每行约 30 字符）。 */
    private String markedTable(int dataRows, String department) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TBL]\n");
        sb.append("| 序号 | 部门 | 备注 |\n");
        sb.append("| --- | --- | --- |\n");
        for (int i = 1; i <= dataRows; i++) {
            sb.append("| ").append(i).append(" | ").append(department).append(" | 备注说明文本").append(i).append(" |\n");
        }
        sb.append("[/TBL]");
        return sb.toString();
    }
}
