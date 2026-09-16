package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Product;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Specification cho {@link Product} — build dynamic predicate theo đúng params có giá trị.
 *
 * <p>Thay thế cho pattern {@code :param IS NULL OR col = :param} trong {@code @Query}.
 * Pattern đó khiến PostgreSQL sinh <b>generic plan</b> (không biết giá trị thật của param)
 * và thường bỏ qua index. Specification chỉ thêm predicate khi param khác {@code null},
 * nên mỗi combination params tạo ra một query riêng biệt — PostgreSQL plan được tối ưu.
 *
 * <p>{@code fts_match} là custom Hibernate function được đăng ký trong {@code CustomFunctions},
 * render thành {@code search_vector @@ plainto_tsquery('simple', unaccent(?))} — dùng GIN index.
 */
public class ProductSpec {

    private ProductSpec() {}

    /**
     * @param businessId       bắt buộc — luôn lọc theo business
     * @param searchTerm       {@code null} = bỏ qua FTS; non-null = thêm predicate {@code search_vector @@}
     * @param isActive         {@code null} = không lọc; non-null = thêm predicate {@code is_active = ?}
     * @param categoryPublicId {@code null} = không lọc; non-null = JOIN category và lọc theo {@code public_id}
     */
    public static Specification<Product> filter(Long businessId, String searchTerm, Boolean isActive, UUID categoryPublicId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("business").get("id"), businessId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (isActive != null) {
                predicates.add(cb.equal(root.get("isActive"), isActive));
            }

            // root.get("category") tạo INNER JOIN — đúng với ngữ nghĩa lọc theo category cụ thể
            if (categoryPublicId != null) {
                predicates.add(cb.equal(root.get("category").get("publicId"), categoryPublicId));
            }

            // fts_match render thành: search_vector @@ plainto_tsquery('simple', unaccent(?)) → hit GIN index
            if (searchTerm != null) {
                predicates.add(cb.isTrue(
                    cb.function("fts_match", Boolean.class, root.get("searchVector"), cb.literal(searchTerm))
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
