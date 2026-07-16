package com.quiktech.backend.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.quiktech.backend.entity.Inventory;
import com.quiktech.backend.entity.Order;
import com.quiktech.backend.entity.PurchaseOrder;
import com.quiktech.backend.entity.PurchaseOrderItem;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.repository.InventoryRepository;
import com.quiktech.backend.repository.OrderRepository;
import com.quiktech.backend.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.of("+07:00"));

    // Chặn export quá lớn — toàn bộ entity + workbook được giữ trong heap nên dễ OOM
    private static final long MAX_EXPORT_ROWS = 10_000;

    // ── Orders Excel ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportOrdersExcel(Long storeId, LocalDate from, LocalDate to) {
        Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
        Instant toInstant   = to   != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.now();

        long count = orderRepository.countForExport(storeId, fromInstant, toInstant);
        if (count > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    "Export exceeds " + MAX_EXPORT_ROWS + " rows (" + count + "). Please narrow the date range");
        }

        List<Order> orders = orderRepository.findForExport(storeId, fromInstant, toInstant);

        String[] headers = {"Mã đơn hàng", "Khách hàng", "Trạng thái",
                "Tiền hàng", "Giảm giá", "Thuế", "Tổng tiền",
                "Đã thanh toán", "Còn nợ", "Phương thức TT", "Ngày tạo"};

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Đơn hàng");

            CellStyle headerStyle = createHeaderStyle(wb);
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Order o : orders) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(o.getOrderCode());
                row.createCell(1).setCellValue(o.getCustomer() != null ? o.getCustomer().getName() : "Khách lẻ");
                row.createCell(2).setCellValue(o.getStatus().name());
                row.createCell(3).setCellValue(o.getSubtotal().doubleValue());
                row.createCell(4).setCellValue(o.getDiscount().doubleValue());
                row.createCell(5).setCellValue(o.getTax().doubleValue());
                row.createCell(6).setCellValue(o.getTotalAmount().doubleValue());
                row.createCell(7).setCellValue(o.getPaidAmount().doubleValue());
                row.createCell(8).setCellValue(o.getDebtAmount().doubleValue());
                row.createCell(9).setCellValue(o.getPaymentMethod());
                row.createCell(10).setCellValue(DATE_FMT.format(o.getCreatedAt()));
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate orders Excel", e);
        }
    }

    // ── Inventory Excel ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportInventoryExcel(Long storeId) {
        long count = inventoryRepository.countByStoreIdAndDeletedAtIsNull(storeId);
        if (count > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    "Export exceeds " + MAX_EXPORT_ROWS + " rows (" + count + "). Please export by warehouse or contact support");
        }

        List<Inventory> items = inventoryRepository.findByStoreIdWithDetails(storeId);

        String[] headers = {"Sản phẩm", "SKU", "Kho", "Số lượng", "Đơn vị"};

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tồn kho");

            CellStyle headerStyle = createHeaderStyle(wb);
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Inventory inv : items) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(inv.getProduct().getName());
                row.createCell(1).setCellValue(inv.getProduct().getSku() != null ? inv.getProduct().getSku() : "");
                row.createCell(2).setCellValue(inv.getWarehouse().getName());
                row.createCell(3).setCellValue(inv.getQuantity().doubleValue());
                row.createCell(4).setCellValue(inv.getProduct().getUnit() != null ? inv.getProduct().getUnit().getName() : "");
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate inventory Excel", e);
        }
    }

    // ── Purchase Order PDF ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportPurchaseOrderPdf(Long storeId, UUID publicId) {
        PurchaseOrder po = purchaseOrderRepository.findForExport(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4);
            PdfWriter.getInstance(doc, out);
            doc.open();

            // ── Header ─────────────────────────────────────────────────────
            com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            com.lowagie.text.Font boldFont  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            com.lowagie.text.Font normFont  = FontFactory.getFont(FontFactory.HELVETICA, 10);

            doc.add(new Paragraph("PHIẾU NHẬP HÀNG", titleFont));
            doc.add(Chunk.NEWLINE);

            doc.add(new Paragraph("Cửa hàng: " + po.getStore().getName(), boldFont));
            doc.add(new Paragraph("Nhà cung cấp: " + po.getSupplier().getName(), normFont));
            doc.add(new Paragraph("Kho: " + po.getWarehouse().getName(), normFont));
            doc.add(new Paragraph("Mã phiếu: " + po.getOrderCode(), normFont));
            doc.add(new Paragraph("Ngày tạo: " + DATE_FMT.format(po.getCreatedAt()), normFont));
            doc.add(new Paragraph("Trạng thái: " + po.getStatus().name(), normFont));
            doc.add(Chunk.NEWLINE);

            // ── Items table ────────────────────────────────────────────────
            float[] widths = {0.5f, 3f, 1.5f, 1.5f, 1.5f, 1.5f};
            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);

            addTableHeader(table, boldFont, "STT", "Sản phẩm", "SKU", "Số lượng", "Đơn giá", "Thành tiền");

            List<PurchaseOrderItem> poItems = po.getPurchaseOrderItems();
            for (int i = 0; i < poItems.size(); i++) {
                PurchaseOrderItem item = poItems.get(i);
                table.addCell(new Phrase(String.valueOf(i + 1), normFont));
                table.addCell(new Phrase(item.getProduct().getName(), normFont));
                table.addCell(new Phrase(item.getProduct().getSku() != null ? item.getProduct().getSku() : "", normFont));
                table.addCell(new Phrase(item.getQuantity().toPlainString(), normFont));
                table.addCell(new Phrase(item.getUnitPrice().toPlainString(), normFont));
                table.addCell(new Phrase(item.getTotalPrice().toPlainString(), normFont));
            }
            doc.add(table);
            doc.add(Chunk.NEWLINE);

            // ── Footer totals ───────────────────────────────────────────────
            doc.add(new Paragraph("Tổng tiền: " + po.getTotalAmount().toPlainString() + " VND", boldFont));
            doc.add(new Paragraph("Đã thanh toán: " + po.getPaidAmount().toPlainString() + " VND", normFont));
            doc.add(new Paragraph("Còn nợ: " + po.getDebtAmount().toPlainString() + " VND", normFont));
            if (po.getNote() != null && !po.getNote().isBlank()) {
                doc.add(new Paragraph("Ghi chú: " + po.getNote(), normFont));
            }

            doc.close();
            return out.toByteArray();
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate purchase order PDF", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void addTableHeader(PdfPTable table, com.lowagie.text.Font font, String... cols) {
        for (String col : cols) {
            PdfPCell cell = new PdfPCell(new Phrase(col, font));
            cell.setBackgroundColor(Color.LIGHT_GRAY);
            cell.setPadding(4);
            table.addCell(cell);
        }
    }
}
