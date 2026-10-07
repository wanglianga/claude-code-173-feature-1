package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 补货服务：记录柜机编号、商品批次、保质期、补货数量、货道位置、温度要求与补货照片；
 * 上架校验放错货道、温区不符、批次过期；形成货道批次库存与批次台账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestockService {

    private final RestockOrderRepository restockOrderRepository;
    private final RestockItemRepository restockItemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository batchRepository;
    private final AppUserRepository userRepository;
    private final ChannelStockRepository stockRepository;
    private final BatchLedgerService ledgerService;
    private final AlertService alertService;

    @Transactional
    public RestockOrder arrive(String cabinetNo, String replenisherUsername,
                               String photoUrls, String remark) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetNo));
        AppUser user = userRepository.findByUsername(replenisherUsername)
                .orElseThrow(() -> ApiException.notFound("补货员不存在"));

        RestockOrder order = RestockOrder.builder()
                .orderNo(genNo("RS"))
                .cabinet(cabinet)
                .replenisher(user)
                .arrivedAt(LocalDateTime.now())
                .photoUrls(photoUrls)
                .status(RestockStatus.IN_PROGRESS)
                .remark(remark)
                .build();
        return restockOrderRepository.save(order);
    }

    /**
     * 上架一条补货明细。
     */
    @Transactional
    public RestockItem placeItem(String orderNo, String sku, String batchNo,
                                 LocalDate productionDate, LocalDate expiryDate,
                                 Integer qty, String actualChannelCode,
                                 String photoUrl, Double cabinetTempC) {
        RestockOrder order = restockOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("补货单不存在: " + orderNo));
        if (order.getStatus() != RestockStatus.IN_PROGRESS) {
            throw ApiException.conflict("补货单已结束，不能再上架");
        }
        Cabinet cabinet = order.getCabinet();
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> ApiException.badRequest("商品不存在: " + sku));
        if (qty == null || qty <= 0) {
            throw ApiException.badRequest("补货数量必须大于 0");
        }

        // 批次：不存在则随补货登记（含保质期与成本快照）
        ProductBatch batch = batchRepository.findByBatchNo(batchNo).orElseGet(() -> {
            ProductBatch b = ProductBatch.builder()
                    .batchNo(batchNo)
                    .product(product)
                    .supplier(product.getSupplier())
                    .productionDate(productionDate)
                    .expiryDate(expiryDate)
                    .receivedQty(0)
                    .costPrice(product.getCostPrice())
                    .status(BatchStatus.IN_STOCK)
                    .build();
            return batchRepository.save(b);
        });
        if (batch.getExpiryDate() == null) {
            batch.setExpiryDate(expiryDate);
        }
        // 限流曝光：下架任务逾期未处理的批次禁止再次上架/曝光，必须先由运营解除
        if (batch.isExposureBlocked()) {
            throw ApiException.badRequest("批次 " + batchNo
                    + " 因下架任务逾期已被限制曝光，禁止再次上架，请先处置该批次任务");
        }
        batch.setReceivedQty(batch.getReceivedQty() + qty);
        if (batch.getStatus() != BatchStatus.IN_STOCK) {
            batch.setStatus(BatchStatus.IN_STOCK);
        }
        batchRepository.save(batch);

        // 货道校验：放错货道 / 不存在
        Channel channel = channelRepository
                .findByCabinetIdAndCode(cabinet.getId(), actualChannelCode)
                .orElse(null);
        PlacementWarning warning = PlacementWarning.NONE;
        String warningDetail = null;
        if (channel == null) {
            warning = PlacementWarning.UNKNOWN_CHANNEL;
            warningDetail = "柜机无此货道: " + actualChannelCode;
        } else if (channel.getPlannedProduct() != null
                && !channel.getPlannedProduct().getId().equals(product.getId())) {
            warning = PlacementWarning.WRONG_CHANNEL;
            warningDetail = String.format("货道 %s 计划商品为 %s，实际放入 %s",
                    actualChannelCode, channel.getPlannedProduct().getSku(), sku);
            alertService.raise(cabinet, AlertType.WRONG_CHANNEL, "WARN", warningDetail,
                    "RESTOCK_ITEM", null);
        }

        // 温区校验
        TempZone required = product.getTempZone();
        if (required != cabinet.getTempZone()) {
            warning = warning == PlacementWarning.NONE
                    ? PlacementWarning.TEMP_MISMATCH : warning;
            warningDetail = (warningDetail == null ? "" : warningDetail + "; ")
                    + "商品要求温区 " + required + " 与柜机温区 " + cabinet.getTempZone() + " 不符";
            alertService.raise(cabinet, AlertType.TEMP_ALARM, "WARN",
                    "补货温区不符: " + sku, "RESTOCK_ITEM", null);
        }

        // 保质期校验：已过期禁止上架
        if (expiryDate != null && !expiryDate.isAfter(LocalDate.now())) {
            warning = PlacementWarning.BATCH_EXPIRED;
            warningDetail = (warningDetail == null ? "" : warningDetail + "; ")
                    + "批次已过期，禁止上架";
            throw ApiException.badRequest("批次 " + batchNo + " 已过期(" + expiryDate + ")，禁止上架");
        }

        RestockItem item = RestockItem.builder()
                .restockOrder(order)
                .batch(batch)
                .product(product)
                .channel(channel)
                .actualChannelCode(actualChannelCode)
                .qty(qty)
                .requiredTempZone(required)
                .cabinetTempC(cabinetTempC != null ? cabinetTempC : cabinet.getLastTempC())
                .photoUrl(photoUrl)
                .warning(warning)
                .warningDetail(warningDetail)
                .build();
        restockItemRepository.save(item);

        // 形成货道批次库存（未知货道不入库，挂账等待纠正）
        if (channel != null) {
            ChannelStock stock = stockRepository
                    .findByChannelIdAndBatchId(channel.getId(), batch.getId())
                    .orElseGet(() -> ChannelStock.builder()
                            .channel(channel)
                            .batch(batch)
                            .product(product)
                            .qtyOnHand(0)
                            .qtyQuarantined(0)
                            .build());
            stock.setQtyOnHand(stock.getQtyOnHand() + qty);
            stockRepository.save(stock);
            ledgerService.record(batch, cabinet, LedgerType.RESTOCK_IN, qty,
                    batch.getCostPrice().multiply(BigDecimal.valueOf(qty)),
                    "RESTOCK_ORDER", order.getId(),
                    "补货上架 " + actualChannelCode + " x" + qty);
        }
        return item;
    }

    @Transactional
    public RestockOrder complete(String orderNo, String remark) {
        RestockOrder order = restockOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("补货单不存在"));
        List<RestockItem> items = restockItemRepository.findByRestockOrderId(order.getId());
        boolean mismatch = items.stream().anyMatch(i -> i.getWarning() != PlacementWarning.NONE);
        order.setStatus(mismatch ? RestockStatus.COMPLETED_MISMATCH : RestockStatus.COMPLETED);
        order.setCompletedAt(LocalDateTime.now());
        if (remark != null) order.setRemark(remark);
        return restockOrderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public List<RestockItem> items(Long orderId) {
        return restockItemRepository.findByRestockOrderId(orderId);
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }
}
