package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.catalog.ProductUpsertRequest;
import com.quiktech.backend.dto.response.catalog.PriceHistoryResponse;
import com.quiktech.backend.dto.response.catalog.ProductDetailResponse;
import com.quiktech.backend.dto.response.catalog.ProductImportResponse;
import com.quiktech.backend.dto.response.catalog.ProductResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.annotation.Auditable;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quiktech.backend.repository.ProductSpec;
import org.springframework.data.jpa.domain.Specification;

import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

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

        if (productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, request.sku()).isPresent()) {
            log.warn("Create product failed: SKU already exists: businessId={}, sku={}", businessId, request.sku());
            throw new IllegalArgumentException("SKU already exists in this business");
        }

        Category category = resolveCategory(businessId, request.categoryPublicId());
        Unit unit = resolveUnit(businessId, request.unitPublicId());

        // getReferenceById: JPA proxy — không SELECT, chỉ dùng ID cho FK lastModifiedByUser
        User userRef = userRepository.getReferenceById(currentUser.userId());

        Product product = Product.builder()
                .business(business)
                .sku(request.sku())
                .name(request.name())
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

        productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, request.sku())
                .filter(p -> !p.getPublicId().equals(publicId))
                .ifPresent(p -> {
                    log.warn("Update product failed: SKU already exists: businessId={}, sku={}", businessId, request.sku());
                    throw new IllegalArgumentException("SKU already exists in this business");
                });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        recordPriceHistoryIfChanged(product, request.costPrice(), request.sellingPrice(), userRef);

        product.setSku(request.sku());
        product.setName(request.name());
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

    @Transactional
    public ProductResponse setStatus(Long businessId, UUID publicId, boolean isActive, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Product product = findProductOrThrow(businessId, publicId);
        product.setIsActive(isActive);
        product.setUpdatedAt(Instant.now());
        Product saved = productRepository.save(product);
        log.info("Product status updated: publicId={}, isActive={}", publicId, isActive);
        return toResponse(saved);
    }

    @Auditable(action = "DELETE_PRODUCT", entityType = "PRODUCT")
    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Product product = findProductOrThrow(businessId, publicId);
        product.setDeletedAt(Instant.now());
        productRepository.save(product);
        log.info("Product deleted: publicId={}, sku={}", publicId, product.getSku());
    }

    @Transactional
    public ProductImportResponse importCsv(Long businessId, MultipartFile file, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);
        User userRef = userRepository.getReferenceById(currentUser.userId());

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                errors.add("File is empty");
                return new ProductImportResponse(0, 0, errors);
            }

            // Expected columns: sku,name,description,categoryName,unitName,costPrice,sellingPrice,minStockLevel,isActive
            String line;
            int rowNum = 1;
            while ((line = reader.readLine()) != null) {
                rowNum++;
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 8) {
                    errors.add("Row " + rowNum + ": insufficient columns (expected at least 8)");
                    skipped++;
                    continue;
                }
                try {
                    String sku = cols[0].trim();
                    String name = cols[1].trim();
                    String description = cols[2].trim();
                    String categoryName = cols[3].trim();
                    String unitName = cols[4].trim();
                    BigDecimal costPrice = new BigDecimal(cols[5].trim());
                    BigDecimal sellingPrice = new BigDecimal(cols[6].trim());
                    int minStockLevel = Integer.parseInt(cols[7].trim());
                    boolean isActive = cols.length > 8 ? Boolean.parseBoolean(cols[8].trim()) : true;

                    if (sku.isEmpty() || name.isEmpty() || unitName.isEmpty()) {
                        errors.add("Row " + rowNum + ": sku, name, and unitName are required");
                        skipped++;
                        continue;
                    }
                    if (productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku).isPresent()) {
                        errors.add("Row " + rowNum + ": SKU '" + sku + "' already exists, skipped");
                        skipped++;
                        continue;
                    }
                    subscriptionLimitService.checkProductLimit(businessId);

                    Category category = categoryName.isEmpty() ? null
                            : categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, categoryName).orElse(null);
                    Unit unit = unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, unitName)
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
