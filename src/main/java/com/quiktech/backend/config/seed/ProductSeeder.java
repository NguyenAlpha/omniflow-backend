package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.Product;
import com.quiktech.backend.entity.Unit;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.CategoryRepository;
import com.quiktech.backend.repository.ProductRepository;
import com.quiktech.backend.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(5)
@RequiredArgsConstructor
public class ProductSeeder implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;

    // Chạy khi SEED_ENABLED=true VÀ product.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${product.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        Map<String, Unit> units = unitRepository.findByBusinessIdIsNullAndDeletedAtIsNull()
                .stream()
                .collect(Collectors.toMap(Unit::getName, u -> u));

        seedProduct("Business One", "SP001",    "Mì tôm Hảo Hảo",   "Thực phẩm",      "Gói",  new BigDecimal("3500"),  new BigDecimal("5000"),  units);
        seedProduct("Business One", "SP002",    "Nước suối Lavie",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP003",    "Xịt khử mùi AXE Indigo Haze",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP004",    "Cà phê sữa MacCoffee Café Phố Gold",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP005",    "Thùng 48 bịch sữa Vinamilk có đường",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP006",    "Nước xả Downy lan tiên & trà trắng",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP007",    "Cà phê sữa đá Ông Bầu",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP008",    "6 lon nước tăng lực Sting dâu",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP009",    "Xúc xích Mỹ Le Gourmet",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0010",    "Nước xả Downy hương nắng mai",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0011",    "Băng quần ban đêm Kotex Max Protect L - XL",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0012",    "Kem đánh răng P/S trà xanh",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0013",    "Lốc 4 chai sữa chua ít đường Betagen",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0014",    "Lốc 4 chai sữa chua ít đường Betagen 85ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0015",    "Xúc xích cocktail xông khói C.P gói 250g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0016",    "Xúc xích cocktail xông khói C.P gói 250g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0017",    "24 chai sữa pha sẵn Ensure Original vani 237ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0018",    "Chả lụa bì ớt xiêm xanh G Kitchen cây 450g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0019",    "Hộp 6 cây kem ốc quế vani socola & dâu socola Merino 60g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0020",    "Kem sầu riêng Merino hộp 260g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0021",    "Bánh bao không nhân Thọ Phát 300g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0022",    "Thùng 48 hộp sữa chua YoMost dâu 170ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0023",    "Chả giò da xốp tôm và thịt Cầu Tre gói 400g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0024",    "Bò viên gân Kitkool gói 200g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0025",    "Tôm viên Fcook Hoa Doanh gói 200g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0026",    "Bột giặt Lix Extra hương hoa 5.5kg",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0027",    "Bột giặt Lix sạch thơm 24h 5.5kg",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0028",    "Xúc xích Vealz C.P gói 500g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0029",    "Thùng 48 hộp sữa NutiFood Grow Plus+ vani 180ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0030",    "Mực viên Hoa Doanh gói 200g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0031",    "Cá viên cốm non Fcook Hoa Doanh gói 250g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0032",    "Bánh giò nhân thịt Thọ Phát 150g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0033",    "Sữa rửa mặt Senka dưỡng ẩm 120g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0034",    "Bột giặt IZI HOME ngát hương 6kg",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0035",    "Kem sữa dừa Merino hộp 260g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0036",    "Mực trứng Nhật Minh gói 500g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0037",    "Vây cá hồi đông lạnh SG Food 500g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0038",    "Thùng 36 hộp sữa đậu nành Fami nguyên chất 200ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0039",    "Nước giặt Lix sạch thơm ngàn hoa 3.2kg",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0040",    "Set quà Meiji hồng",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0041",    "Xịt khử mùi Nivea Men Black & White Invisible 150ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0042",    "Xịt khử mùi toàn thân Romano Gentleman 150ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0043",    "Thùng 10 gói giấy rút đa năng Lency 4 lớp 1280 tờ",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0044",    "Lăn khử mùi hương nước hoa Enchanteur Deluxe Charming 50ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0045",    "3 cuộn túi đựng rác màu TBP 55x65cm (1kg)",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0046",    "Nước xả Comfort quyến rũ 3.1 lít",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0047",    "Kem tẩy lông Cléo da thường 50g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0048",    "Kem tẩy lông Cléo da nhạy cảm 50g",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0049",    "Sữa tắm Puri hương hoa lavender 1kg",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business One", "SP0050",    "Thùng 24 chai sữa trái cây Nutriboost dâu 297ml",   "Đồ uống",        "Chai", new BigDecimal("5000"),  new BigDecimal("8000"),  units);
        seedProduct("Business Two", "SP001",    "Bút bi Thiên Long", "Văn phòng phẩm", "Cái",  new BigDecimal("3000"),  new BigDecimal("5000"),  units);
        seedProduct("Business Two", "SP002",    "Chuột không dây",   "Đồ điện tử",     "Cái",  new BigDecimal("150000"), new BigDecimal("200000"), units);
    }

    private void seedProduct(String businessName, String sku, String name,
                             String categoryName, String unitName,
                             BigDecimal costPrice, BigDecimal sellingPrice,
                             Map<String, Unit> units) {
        var business = businessRepository.findByNameAndDeletedAtIsNull(businessName)
                .orElseThrow(() -> new IllegalStateException("Business not found: " + businessName + " — run BusinessSeeder first"));

        var category = categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(business.getId(), categoryName)
                .orElseThrow(() -> new IllegalStateException("Category not found: " + categoryName + " — run CategorySeeder first"));

        var unit = units.get(unitName);
        if (unit == null) throw new IllegalStateException("Unit not found: " + unitName + " — run UnitSeeder first");

        productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(business.getId(), sku)
                .orElseGet(() -> productRepository.save(Product.builder()
                        .business(business)
                        .sku(sku)
                        .name(name)
                        .category(category)
                        .unit(unit)
                        .costPrice(costPrice)
                        .sellingPrice(sellingPrice)
                        .publicId(UUID.randomUUID())
                        .build()));
    }
}
