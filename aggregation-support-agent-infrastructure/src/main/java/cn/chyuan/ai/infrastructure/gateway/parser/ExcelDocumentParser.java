package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExcelDocumentParser implements IDocumentParser {

    private final DataFormatter dataFormatter = new DataFormatter();

    @Override
    public ParsedDocumentVO parse(byte[] content, String fileName, String mimeType) {
        log.info("解析Excel文档: {}", fileName);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            List<ParsedDocumentVO.DocumentSection> sections = new ArrayList<>();
            int position = 0;

            for (Sheet sheet : workbook) {
                StringBuilder sectionText = new StringBuilder();
                sectionText.append("Sheet: ").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = dataFormatter.formatCellValue(cell);
                        if (value != null && !value.isBlank()) {
                            cells.add(value.trim());
                        }
                    }
                    if (!cells.isEmpty()) {
                        sectionText.append(String.join("\t", cells)).append("\n");
                    }
                }
                String sheetText = sectionText.toString().trim();
                if (!sheetText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    text.append(sheetText);
                    sections.add(ParsedDocumentVO.DocumentSection.builder()
                            .title(sheet.getSheetName())
                            .content(sheetText)
                            .level(1)
                            .position(position++)
                            .build());
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("format", "excel");
            metadata.put("sheetCount", workbook.getNumberOfSheets());
            metadata.put("charCount", text.length());

            return ParsedDocumentVO.builder()
                    .fileName(fileName)
                    .extension(getFileExtension(fileName))
                    .mimeType(mimeType)
                    .textContent(text.toString())
                    .sections(sections)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Excel文档解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String mimeType, String fileName) {
        String ext = getFileExtension(fileName);
        if (".xls".equals(ext) || ".xlsx".equals(ext)) {
            return true;
        }
        return mimeType != null && (
                mimeType.startsWith("application/vnd.ms-excel")
                        || mimeType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        );
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot).toLowerCase() : "";
    }
}
