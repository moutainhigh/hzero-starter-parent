package org.hzero.export.filler.v2;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.streaming.SXSSFCell;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hzero.export.exporter.ExcelExporter;
import org.hzero.export.vo.ExportColumn;
import org.springframework.util.Assert;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单Sheet导出：一种数据类型一个Sheet页
 *
 * @author xcxcxcxcx
 */
public class SingleSheetFillerV2 extends ExcelFillerV2 {

    public static final String FILLER_TYPE = "single-sheet";

    private CellStyle oddTitleCellStyle;
    private CellStyle evenTitleCellStyle;

    public SingleSheetFillerV2(ExportColumn root) {
        super(root);
    }

    @Override
    public String getFillerType() {
        return FILLER_TYPE;
    }

    @Override
    public void createSheetAndTitle0(SXSSFWorkbook workbook) {
        setOddTitleCellStyle(workbook);
        setEventTitleCellStyle(workbook);
        List<ExportColumn> titleColumns = new LinkedList<>();
        collectAllTitleColumn(titleColumns, getRootExportColumn());
        String title = StringUtils.defaultIfBlank(getRootExportColumn().getTitle(), getRootExportColumn().getName());
        SXSSFSheet sheet = workbook.createSheet(title);
        sheet.setDefaultColumnWidth(20);
        createTitleRow(sheet, getRootExportColumn().getExcelSheet().rowOffset(), getRootExportColumn().getExcelSheet().colOffset(), titleColumns);
    }

    @Override
    protected void fillData0(SXSSFSheet sheet, List<?> data) {
        // 处理每行数据
        int rows = 0;
        for (int i = 0, len = data.size(); i < len; i++) {
            Assert.isTrue(sheet.getLastRowNum() < ExcelExporter.MAX_ROW, "export.too-many-data");

            SXSSFRow row = sheet.createRow(sheet.getLastRowNum() + 1);
            fillRow(sheet.getWorkbook(), row, getRootExportColumn().getExcelSheet().colOffset(), data.get(i), getRootExportColumn().getChildren());
            int count = fillChildren(sheet, data.get(i), row.getLastCellNum(), getRootExportColumn());
            for (int c=1;c < count;c++) {
                fillRow(sheet.getWorkbook(), sheet.getRow(row.getRowNum()+c), getRootExportColumn().getExcelSheet().colOffset(), data.get(i), getRootExportColumn().getChildren());
            }
            rows += count + 1;
            if (rows >= 100) {
                rows = 0;
                try {
                    sheet.flushRows(100);
                } catch (IOException e) {
                    logger.error("flush rows error.", e);
                }
            }
        }
    }

    private int fillChildren(SXSSFSheet sheet, Object data, int colOffset, ExportColumn exportClass) {
        int maxChildDataCount = 0;
        int lastRowNum = sheet.getLastRowNum();
        int rowIndex = lastRowNum;
        List<ExportColumn> exportColumns = getChildrenCheckedAndHasChild(exportClass);
        for (ExportColumn child : exportColumns) {
            List<Object> childData = getChildData(data, child);
            List<ExportColumn> childColumns = child.getChildren();
            int checkedCount = countChildrenChecked(child);
            int innerMaxChildCount = 0;
            for (int i = 0; i < childData.size(); i++) {
                SXSSFRow row = null;
                row = sheet.getRow(rowIndex);
                if (row == null) {
                    row = sheet.createRow(rowIndex);
                }
                fillRow(sheet.getWorkbook(), row, colOffset, childData.get(i), childColumns);
                int childCount = fillChildren(sheet, childData.get(i), colOffset + checkedCount, child);
                for (int c=1;c < childCount;c++) {
                    fillRow(sheet.getWorkbook(), sheet.getRow(rowIndex + c), colOffset, childData.get(i), childColumns);
                }
                innerMaxChildCount += childCount > 0 ? childCount : 1;
                rowIndex++;
            }
            // 重置
            rowIndex = lastRowNum;
            colOffset += checkedCount;
            if (innerMaxChildCount > maxChildDataCount) {
                maxChildDataCount = innerMaxChildCount;
            }
        }
        return maxChildDataCount;
    }

    private void collectAllTitleColumn(List<ExportColumn> titleColumns, ExportColumn exportClass) {
        if (exportClass.isChecked() && exportClass.hasChildren()) {
            titleColumns.add(exportClass);
            if (CollectionUtils.isNotEmpty(exportClass.getChildren())) {
                for (ExportColumn ec : exportClass.getChildren()) {
                    collectAllTitleColumn(titleColumns, ec);
                }
            }
        }
    }

    /**
     * 创建标题行
     */
    private void createTitleRow(SXSSFSheet sheet, int rowOffset, int colOffset, List<ExportColumn> titleColumns) {
        SXSSFRow titleRow = sheet.createRow(rowOffset);
        titleRow.setHeight((short) 350);

        CellStyle titleRowStyle = sheet.getWorkbook().createCellStyle();
        titleRowStyle.setLocked(true);
        titleRow.setRowStyle(titleRowStyle);

        for (int i = 0; i < titleColumns.size(); i++) {
            for (ExportColumn column : titleColumns.get(i).getChildren()) {
                if (column.isChecked() && !column.hasChildren()) {
                    SXSSFCell cell = titleRow.createCell(colOffset);
                    if (i % 2 == 0) {
                        cell.setCellStyle(oddTitleCellStyle);
                    } else{
                        cell.setCellStyle(evenTitleCellStyle);
                    }
                    // 值
                    fillCellValue(sheet.getWorkbook(), cell, column.getTitle(), Collections.emptyList(), column.getExcelColumn(), true);
                    // 宽度
                    setCellWidth(sheet, column.getType(), colOffset, column.getExcelColumn().width());

                    colOffset++;
                }
            }
        }
    }

    /**
     * 生成行数据
     */
    private void fillRow(SXSSFWorkbook workbook, SXSSFRow row, int colOffset, Object rowData, List<ExportColumn> columns) {
        int cells = 0;
        for (ExportColumn column : columns) {
            if (column.isChecked() && !column.hasChildren()) {
                Object cellValue = null;
                try {
                    cellValue = FieldUtils.readField(rowData, column.getName(), true);
                } catch (Exception ev) {
                    logger.error("get value error.", ev);
                }
                SXSSFCell cell = row.createCell(colOffset + cells);
                fillCellValue(workbook, cell, cellValue, rowData, column.getExcelColumn(), false);
                cells++;
            }
        }
    }

    private List<ExportColumn> getChildrenCheckedAndHasChild(ExportColumn root) {
        if (root != null && CollectionUtils.isNotEmpty(root.getChildren())) {
            return root.getChildren().stream().filter(column -> column.isChecked() && column.hasChildren()).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private int countChildrenChecked(ExportColumn root) {
        if (root != null && CollectionUtils.isNotEmpty(root.getChildren())) {
            long count = root.getChildren().stream().filter(column -> column.isChecked() && !column.hasChildren()).count();
            return Integer.parseInt(String.valueOf(count));
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getChildData(Object data, ExportColumn child) {
        String getter = "get" + child.getName();
        Method[] methods = data.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equalsIgnoreCase(getter)) {
                try {
                    method.setAccessible(true);
                    return (List<Object>) method.invoke(data) == null?Collections.emptyList():(List<Object>) method.invoke(data);
                } catch (Exception e) {
                    logger.error("get child data error.", e);
                }
            }
        }
        return Collections.emptyList();
    }

    private void setOddTitleCellStyle(SXSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setColor(Font.COLOR_NORMAL);
        font.setBold(true);

        oddTitleCellStyle = workbook.createCellStyle();
        oddTitleCellStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.index);
        oddTitleCellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        oddTitleCellStyle.setFont(font);
        oddTitleCellStyle.setAlignment(HorizontalAlignment.CENTER);
        oddTitleCellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        oddTitleCellStyle.setLocked(true);
    }


    private void setEventTitleCellStyle(SXSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setColor(Font.COLOR_NORMAL);
        font.setBold(true);

        evenTitleCellStyle = workbook.createCellStyle();
        evenTitleCellStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.index);
        evenTitleCellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        evenTitleCellStyle.setFont(font);
        evenTitleCellStyle.setAlignment(HorizontalAlignment.CENTER);
        evenTitleCellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        oddTitleCellStyle.setLocked(true);
    }

}
