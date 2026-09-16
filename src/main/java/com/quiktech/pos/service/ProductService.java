package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.catalog.ProductUpsertRequest;
import com.quiktech.pos.dto.response.catalog.PriceHistoryResponse;
import com.quiktech.pos.dto.response.catalog.ProductDetailResponse;
import com.quiktech.pos.dto.response.catalog.ProductImportResponse;
import com.quiktech.pos.dto.response.catalog.ProductResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.common.PagedResult;
import com.quiktech.pos.entity.*;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.annotation.Auditable;
import com.quiktech.pos.repository.*;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quiktech.pos.repository.ProductSpec;
import org.springframework.data.jpa.domain.Specification;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int MAX_IMPORT_ROWS = 1000;

    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final SubscriptionLimitService subscriptionLimitService;

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Long businessId, Boolean isActive, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        List<Product> products = isActive != null
                ? productRepository.findAllByBusinessIdAndIsActive(businessId, isActive)
                : productRepository.findAllByBusinessId(businessId);
        return products.stream().map(this::toResponse).toList();
    }

    /**
     * Tìm kiếm sản phẩm có phân trang, hỗ trợ lọc theo nhiều tiêu chí.
     *
     * <p>Dùng 2 query để tránh N+1 và đảm bảo index được dùng đúng:
     * <ol>
     *   <li><b>Specification query</b> — build dynamic predicate theo đúng params có giá trị,
     *       tránh pattern {@code OR NULL} làm PostgreSQL chọn generic plan bỏ qua index.
     *       Trả về {@code Page} với pagination metadata (totalElements, totalPages).</li>
     *   <li><b>Batch JOIN FETCH</b> — dùng IDs từ trang kết quả để load {@code category} và
     *       {@code unit} trong 1 query duy nhất, tránh N+1 lazy loading.</li>
     * </ol>
     *
     * <p>{@code searchTerm} blank được chuẩn hoá thành {@code null} trước khi vào Specification
     * để predicate FTS ({@code search_vector @@}) không được thêm vào — tránh gọi
     * {@code plainto_tsquery} với chuỗi rỗng.
     */
    @Transactional(readOnly = true)
    public PagedResult<ProductResponse> search(Long businessId, String searchTerm, Boolean isActive, UUID categoryPublicId, Pageable pageable, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        String term = (searchTerm == null || searchTerm.isBlank()) ? null : searchTerm;

        Specification<Product> spec = ProductSpec.filter(businessId, term, isActive, categoryPublicId);
        Page<Product> page = productRepository.findAll(spec, pageable);

        if (page.isEmpty()) {
            return PagedResult.of(page.map(this::toResponse));
        }

        // Query 2: batch load category + unit cho đúng trang hiện tại
        List<Long> ids = page.getContent().stream().map(Product::getId).toList();
        Map<Long, Product> byId = productRepository.findAllWithCategoryAndUnit(ids)
                .stream().collect(Collectors.toMap(Product::getId, p -> p));

        return PagedResult.of(page.map(p -> toResponse(byId.getOrDefault(p.getId(), p))));
    }

    @Transactional(readOnly = true)
    public ProductResponse findBySku(Long businessId, String sku, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Product product = productRepository.findByBusinessIdAndSkuWithDetails(businessId, sku)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found with SKU: " + sku));
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse get(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return toDetailResponse(findProductDetailOrThrow(businessId, publicId));
    }

    @Auditable(action = "CREATE_PRODUCT", entityType = "PRODUCT")
    @Transactional
    public ProductResponse create(Long businessId, ProductUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        subscriptionLimitService.checkProductLimit(businessId);

        // Trim trước khi check unique + lưu: không trim thì " SKU01" và "SKU01" cùng tồn tại được
        // (unique check và partial unique index đều so sánh chuỗi thô)
        String sku = request.sku().trim();
        String name = request.name().trim();

        if (productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku).isPresent()) {
            log.warn("Create product failed: SKU already exists: businessId={}, sku={}", businessId, sku);
            throw new IllegalArgumentException("SKU already exists in this business");
        }

        Category category = resolveCategory(businessId, request.categoryPublicId());
        Unit unit = resolveUnit(businessId, request.unitPublicId());

        // getReferenceById: JPA proxy — không SELECT, chỉ dùng ID cho FK lastModifiedByUser
        User userRef = userRepository.getReferenceById(currentUser.userId());

        Product product = Product.builder()
                .business(business)
                .sku(sku)
                .name(name)
                .description(request.description())
                .category(category)
                .unit(unit)
                .costPrice(request.costPrice())
                .sellingPrice(request.sellingPrice())
                .minStockLevel(request.minStockLevel())
                .isActive(request.isActive())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .build();

        ProductResponse response = toResponse(productRepository.save(product));
        log.info("Product created: businessId={}, sku={}, name={}", businessId, response.sku(), response.name());
        return response;
    }

    @Auditable(action = "UPDATE_PRODUCT", entityType = "PRODUCT")
    @Transactional
    public ProductResponse update(Long businessId, UUID publicId, ProductUpsertRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        Product product = findProductOrThrow(businessId, publicId);

        // Trim trước khi check unique + lưu (xem chú thích ở create)
        String sku = request.sku().trim();
        String name = request.name().trim();

        productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)
                .filter(p -> !p.getPublicId().equals(publicId))
                .ifPresent(p -> {
                    log.warn("Update product failed: SKU already exists: businessId={}, sku={}", businessId, sku);
                    throw new IllegalArgumentException("SKU already exists in this business");
                });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        recordPriceHistoryIfChanged(product, request.costPrice(), request.sellingPrice(), userRef);

        product.setSku(sku);
        product.setName(name);
        product.setDescription(request.description());
        product.setCategory(resolveCategory(businessId, request.categoryPublicId()));
        product.setUnit(resolveUnit(businessId, request.unitPublicId()));
        product.setCostPrice(request.costPrice());
        product.setSellingPrice(request.sellingPrice());
        product.setMinStockLevel(request.minStockLevel());
        product.setIsActive(request.isActive());
        product.setLastModifiedByUser(userRef);
        product.setLastModifiedAt(Instant.now());

        Product saved = productRepository.save(product);
        log.info("Product updated: publicId={}, sku={}", publicId, saved.getSku());
        return toResponse(saved);
    }

    @Auditable(action = "SET_PRODUCT_STATUS", entityType = "PRODUCT")
    @Transactional
    public ProductResponse setStatus(Long businessId, UUID publicId, boolean isActive, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Product product = findProductOrThrow(businessId, publicId);
        product.setIsActive(isActive);
        product.setUpdatedAt(Instant.now());
        // Cập nhật trường sync như mọi mutation khác — client local-first dựa vào
        // lastModifiedAt/syncVersion để pull delta, đổi status mà không cập nhật thì client bỏ sót
        product.setLastModifiedByUser(userRepository.getReferenceById(currentUser.userId()));
        product.setLastModifiedAt(Instant.now());
        Product saved = productRepository.save(product);
        log.info("Product status updated: publicId={}, isActive={}", publicId, isActive);
        return toResponse(saved);
    }

    @Auditable(action = "DELETE_PRODUCT", entityType = "PRODUCT")
    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Product product = findProductOrThrow(businessId, publicId);

        // Chặn xóa khi còn tồn kho: sau soft-delete, hàng vẫn nằm trong kho
        // (inventory row còn nguyên) nhưng product không tra cứu được nữa
        // → lệch giữa tồn kho và catalog. Yêu cầu xuất/điều chỉnh hết tồn trước.
        if (product.getTotalStock() != null && product.getTotalStock().signum() > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete product with stock on hand; adjust inventory to zero first");
        }

        product.setDeletedAt(Instant.now());
        // Soft delete cũng là mutation — set trường sync để client local-first nhận được
        // tín hiệu "record đã xóa" khi sync delta được implement
        product.setLastModifiedByUser(userRepository.getReferenceById(currentUser.userId()));
        product.setLastModifiedAt(Instant.now());
        productRepository.save(product);
        log.info("Product deleted: publicId={}, sku={}", publicId, product.getSku());
    }

    @Auditable(action = "IMPORT_PRODUCTS", entityType = "PRODUCT")
    @Transactional
    public ProductImportResponse importCsv(Long businessId, MultipartFile file, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);
        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Lấy capacity còn lại MỘT LẦN trước vòng lặp (kèm lock subscription).
        // Không gọi checkProductLimit từng dòng: khi vượt limit, exception ném xuyên qua
        // proxy @Transactional đánh dấu transaction rollback-only dù có catch trong loop
        // → toàn bộ import bị rollback + request 500 (UnexpectedRollbackException).
        Long remainingCapacity = subscriptionLimitService.getRemainingProductCapacity(businessId);

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        // Commons CSV (RFC 4180): xử lý đúng tên/mô tả chứa dấu phẩy, ngoặc kép, xuống dòng
        // — split(",") cũ vỡ cột với dữ liệu như "Bàn phím, chuột combo"
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        // Expected columns: sku,name,description,categoryName,unitName,costPrice,sellingPrice,minStockLevel,isActive
        try (CSVParser parser = format.parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                errors.add("File is empty");
                return new ProductImportResponse(0, 0, errors);
            }
            it.next(); // bỏ qua dòng header

            int rowNum = 1;
            while (it.hasNext()) {
                CSVRecord record = it.next();
                rowNum++;
                // Giới hạn số dòng: import chạy trong 1 transaction, file quá lớn sẽ giữ
                // lock subscription + connection lâu và phình bộ nhớ persistence context
                if (rowNum - 1 > MAX_IMPORT_ROWS) {
                    errors.add("Import is limited to " + MAX_IMPORT_ROWS + " rows per file; remaining rows were ignored");
                    break;
                }
                if (record.size() < 8) {
                    errors.add("Row " + rowNum + ": insufficient columns (expected at least 8)");
                    skipped++;
                    continue;
                }
                try {
                    String sku = record.get(0);
                    String name = record.get(1);
                    String description = record.get(2);
                    String categoryName = record.get(3);
                    String unitName = record.get(4);
                    BigDecimal costPrice = new BigDecimal(record.get(5));
                    BigDecimal sellingPrice = new BigDecimal(record.get(6));
                    int minStockLevel = Integer.parseInt(record.get(7));
                    boolean isActive = record.size() <= 8 || Boolean.parseBoolean(record.get(8));

                    if (sku.isEmpty() || name.isEmpty() || unitName.isEmpty()) {
                        errors.add("Row " + rowNum + ": sku, name, and unitName are required");
                        skipped++;
                        continue;
                    }
                    // Validate sớm theo ràng buộc DB — nếu để DB từ chối lúc flush thì
                    // toàn bộ transaction import rollback thay vì báo lỗi từng dòng
                    if (sku.length() > 50 || name.length() > 200) {
                        errors.add("Row " + rowNum + ": sku max 50 characters, name max 200 characters");
                        skipped++;
                        continue;
                    }
                    if (costPrice.signum() < 0 || sellingPrice.signum() < 0 || minStockLevel < 0) {
                        errors.add("Row " + rowNum + ": costPrice, sellingPrice, and minStockLevel must not be negative");
                        skipped++;
                        continue;
                    }
                    if (productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku).isPresent()) {
                        errors.add("Row " + rowNum + ": SKU '" + sku + "' already exists, skipped");
                        skipped++;
                        continue;
                    }
                    if (remainingCapacity != null && imported >= remainingCapacity) {
                        errors.add("Row " + rowNum + ": product limit reached for your current plan; remaining rows were skipped");
                        skipped++;
                        break;
                    }

                    // Category không tồn tại → báo lỗi rõ ràng thay vì import lặng lẽ với category null
                    Category category = null;
                    if (!categoryName.isEmpty()) {
                        category = categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, categoryName)
                                .orElse(null);
                        if (category == null) {
                            errors.add("Row " + rowNum + ": category '" + categoryName + "' not found");
                            skipped++;
                            continue;
                        }
                    }
                    // Ưu tiên business unit, fallback system unit (trước đây system unit không dùng được khi import)
                    Unit unit = unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, unitName)
                            .or(() -> unitRepository.findByBusinessIdIsNullAndNameAndDeletedAtIsNull(unitName))
                            .orElseThrow(() -> new IllegalArgumentException("Unit '" + unitName + "' not found"));

                    Product product = Product.builder()
                            .business(business).sku(sku).name(name).description(description.isEmpty() ? null : description)
                            .category(category).unit(unit).costPrice(costPrice).sellingPrice(sellingPrice)
                            .minStockLevel(minStockLevel).isActive(isActive)
                            .publicId(UUID.randomUUID()).lastModifiedByUser(userRef).build();
                    productRepository.save(product);
                    imported++;
                } catch (Exception e) {
                    errors.add("Row " + rowNum + ": " + e.getMessage());
                    skipped++;
                }
            }
        } catch (Exception e) {
            errors.add("Failed to read file: " + e.getMessage());
        }

        log.info("Product CSV import: businessId={}, imported={}, skipped={}", businessId, imported, skipped);
        return new ProductImportResponse(imported, skipped, errors);
    }

    private void recordPriceHistoryIfChanged(Product product, BigDecimal newCostPrice, BigDecimal newSellingPrice, User changedBy) {
        boolean costChanged = product.getCostPrice().compareTo(newCostPrice) != 0;
        boolean sellingChanged = product.getSellingPrice().compareTo(newSellingPrice) != 0;
        if (!costChanged && !sellingChanged) return;
        log.info("Price changed for product publicId={}: cost {} → {}, selling {} → {}",
                product.getPublicId(), product.getCostPrice(), newCostPrice, product.getSellingPrice(), newSellingPrice);

        PriceHistory history = PriceHistory.builder()
                .business(product.getBusiness())
                .product(product)
                .oldCostPrice(product.getCostPrice())
                .newCostPrice(newCostPrice)
                .oldSellingPrice(product.getSellingPrice())
                .newSellingPrice(newSellingPrice)
                .changedBy(changedBy)
                .build();
        priceHistoryRepository.save(history);
    }

    // Scoped theo businessId để chống gán category của business khác vào product (IDOR qua body)
    private Category resolveCategory(Long businessId, UUID publicId) {
        if (publicId == null) return null;
        return categoryRepository.findByBusinessIdAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));
    }

    // Cho phép unit hệ thống (business IS NULL) hoặc unit của chính business — chặn unit của business khác
    private Unit resolveUnit(Long businessId, UUID publicId) {
        return unitRepository.findByBusinessIdOrSystemAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    // Scoped theo businessId để chống IDOR — product của business khác trả về 404
    private Product findProductOrThrow(Long businessId, UUID publicId) {
        return productRepository.findByBusinessIdAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));
    }

    private Product findProductDetailOrThrow(Long businessId, UUID publicId) {
        return productRepository.findByBusinessIdAndPublicIdWithPriceHistories(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getPublicId(), p.getBusiness().getId(),
                p.getSku(), p.getName(), p.getDescription(),
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getCategory() != null ? p.getCategory().getPublicId() : null,
                p.getCategory() != null ? p.getCategory().getName() : null,
                p.getUnit().getId(),
                p.getUnit().getPublicId(),
                p.getUnit().getName(),
                p.getUnit().getAbbreviation(),
                p.getCostPrice(), p.getSellingPrice(),
                p.getTotalStock(),
                p.getMinStockLevel(), p.getIsActive(),
                p.getSyncVersion(), p.getLastModifiedAt(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }

    private ProductDetailResponse toDetailResponse(Product p) {
        List<PriceHistoryResponse> priceHistory = p.getPriceHistories().stream()
                .sorted((h1, h2) -> h2.getChangedAt().compareTo(h1.getChangedAt())) // DESC
                .map(h -> new PriceHistoryResponse(
                        h.getId(), h.getBusiness().getId(), h.getProduct().getPublicId(),
                        h.getProduct().getName(),
                        h.getOldCostPrice(), h.getNewCostPrice(),
                        h.getOldSellingPrice(), h.getNewSellingPrice(),
                        h.getChangedBy().getUsername(),
                        h.getChangedAt()
                )).toList();

        return new ProductDetailResponse(
                toResponse(p), priceHistory
        );
    }
}
