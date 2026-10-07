package com.cabinet.config;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 种子数据：测试账号、供应商、商品、点位、柜机货道与一个临期批次 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductBatchRepository batchRepository;
    private final ChannelStockRepository channelStockRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) return;

        String pwd = passwordEncoder.encode("cabinet123");
        userRepository.save(AppUser.builder().username("replenisher").password(pwd)
                .role("REPLENISHER").displayName("补货员小张").build());
        userRepository.save(AppUser.builder().username("operator").password(pwd)
                .role("OPERATOR").displayName("运营小王").build());
        userRepository.save(AppUser.builder().username("finance").password(pwd)
                .role("FINANCE").displayName("财务小李").build());
        userRepository.save(AppUser.builder().username("service").password(pwd)
                .role("CUSTOMER_SERVICE").displayName("客服小赵").build());
        userRepository.save(AppUser.builder().username("admin").password(pwd)
                .role("ADMIN").displayName("管理员").build());

        Supplier fresh = supplierRepository.save(Supplier.builder()
                .code("SUP001").name("鲜食达供应链").contactPerson("陈经理")
                .contactPhone("13800000001").paymentTermDays(30).build());
        Supplier drink = supplierRepository.save(Supplier.builder()
                .code("SUP002").name("清泉饮品").contactPerson("刘经理")
                .contactPhone("13800000002").paymentTermDays(15).build());

        Product sandwich = productRepository.save(Product.builder()
                .sku("P-SANDWICH").name("火腿三明治").category("鲜食")
                .barcode("6900001000011").tempZone(TempZone.CHILLED).shelfLifeDays(3)
                .supplier(fresh).costPrice(new BigDecimal("4.50"))
                .retailPrice(new BigDecimal("8.80")).unitWeightGram(120).build());
        Product milk = productRepository.save(Product.builder()
                .sku("P-MILK").name("鲜牛奶 250ml").category("乳品")
                .barcode("6900001000028").tempZone(TempZone.CHILLED).shelfLifeDays(7)
                .supplier(fresh).costPrice(new BigDecimal("2.80"))
                .retailPrice(new BigDecimal("5.50")).unitWeightGram(260).build());
        Product water = productRepository.save(Product.builder()
                .sku("P-WATER").name("矿泉水 550ml").category("饮品")
                .barcode("6900001000035").tempZone(TempZone.AMBIENT).shelfLifeDays(365)
                .supplier(drink).costPrice(new BigDecimal("1.00"))
                .retailPrice(new BigDecimal("2.50")).unitWeightGram(580).build());

        Point community = pointRepository.save(Point.builder()
                .code("PT-SQ").name("阳光花园社区东门").type(LocationType.COMMUNITY)
                .address("阳光路 88 号").build());
        Point office = pointRepository.save(Point.builder()
                .code("PT-OF").name("云谷科技园 B 座").type(LocationType.OFFICE)
                .address("云谷路 1 号").build());
        Point hospital = pointRepository.save(Point.builder()
                .code("PT-HP").name("市中心医院门诊楼").type(LocationType.HOSPITAL)
                .address("健康路 23 号").build());

        Cabinet c1 = cabinetRepository.save(Cabinet.builder()
                .cabinetNo("CAB-001").point(community).model("C5-Refrigerated")
                .tempZone(TempZone.CHILLED)
                .tempMin(0.0).tempMax(8.0).lastTempC(4.0)
                .status(CabinetStatus.NORMAL).build());
        Cabinet c2 = cabinetRepository.save(Cabinet.builder()
                .cabinetNo("CAB-002").point(office).model("C3-Ambient")
                .tempZone(TempZone.AMBIENT)
                .tempMin(0.0).tempMax(35.0).lastTempC(24.0)
                .status(CabinetStatus.NORMAL).build());
        Cabinet c3 = cabinetRepository.save(Cabinet.builder()
                .cabinetNo("CAB-003").point(hospital).model("C5-Refrigerated")
                .tempZone(TempZone.CHILLED)
                .tempMin(0.0).tempMax(8.0).lastTempC(5.0)
                .status(CabinetStatus.NORMAL).build());

        Channel c1a01 = channelRepository.save(Channel.builder().cabinet(c1).code("A01")
                .plannedProduct(sandwich).capacity(12).build());
        Channel c1a02 = channelRepository.save(Channel.builder().cabinet(c1).code("A02")
                .plannedProduct(milk).capacity(20).build());
        Channel c2a01 = channelRepository.save(Channel.builder().cabinet(c2).code("A01")
                .plannedProduct(water).capacity(30).build());
        Channel c3a01 = channelRepository.save(Channel.builder().cabinet(c3).code("A01")
                .plannedProduct(milk).capacity(20).build());

        // 一个即将临期的批次（供定时扫描演示）：已在 CAB-003/A01 上架 6 盒，扫描即停售并生成任务
        ProductBatch seedBatch = batchRepository.save(ProductBatch.builder()
                .batchNo("B-MILK-SEED").product(milk).supplier(fresh)
                .productionDate(LocalDate.now().minusDays(6))
                .expiryDate(LocalDate.now().plusDays(1))
                .receivedQty(6).costPrice(milk.getCostPrice())
                .status(BatchStatus.IN_STOCK).build());
        channelStockRepository.save(ChannelStock.builder()
                .channel(c3a01).batch(seedBatch).product(milk)
                .qtyOnHand(6).qtyQuarantined(0).build());

        // 常规在库：CAB-001 三明治 5 份、牛奶 8 盒；CAB-002 水 10 瓶
        ProductBatch bSandwich = batchRepository.save(ProductBatch.builder()
                .batchNo("B-SANDWICH-SEED").product(sandwich).supplier(fresh)
                .productionDate(LocalDate.now().minusDays(1))
                .expiryDate(LocalDate.now().plusDays(2))
                .receivedQty(5).costPrice(sandwich.getCostPrice())
                .status(BatchStatus.IN_STOCK).build());
        channelStockRepository.save(ChannelStock.builder()
                .channel(c1a01).batch(bSandwich).product(sandwich)
                .qtyOnHand(5).qtyQuarantined(0).build());
        ProductBatch bMilk = batchRepository.save(ProductBatch.builder()
                .batchNo("B-MILK-FRESH").product(milk).supplier(fresh)
                .productionDate(LocalDate.now().minusDays(1))
                .expiryDate(LocalDate.now().plusDays(6))
                .receivedQty(8).costPrice(milk.getCostPrice())
                .status(BatchStatus.IN_STOCK).build());
        channelStockRepository.save(ChannelStock.builder()
                .channel(c1a02).batch(bMilk).product(milk)
                .qtyOnHand(8).qtyQuarantined(0).build());
        ProductBatch bWater = batchRepository.save(ProductBatch.builder()
                .batchNo("B-WATER-SEED").product(water).supplier(drink)
                .productionDate(LocalDate.now().minusDays(30))
                .expiryDate(LocalDate.now().plusDays(300))
                .receivedQty(10).costPrice(water.getCostPrice())
                .status(BatchStatus.IN_STOCK).build());
        channelStockRepository.save(ChannelStock.builder()
                .channel(c2a01).batch(bWater).product(water)
                .qtyOnHand(10).qtyQuarantined(0).build());

        log.info("种子数据初始化完成，测试账号统一密码 cabinet123");
    }
}
