package com.github.yzqdev.pet_home.network;

import com.github.yzqdev.pet_home.ModConstants;
import com.github.yzqdev.pet_home.datagen.LangDefinition;
import com.github.yzqdev.pet_home.util.CitadelEntityData;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ServerPayloadHandler {
    private static final ServerPayloadHandler INSTANCE = new ServerPayloadHandler();
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 滑动窗口：该实体超过此毫秒未再收到包时，视为本段结束，下次收到时打汇总 */
    private static final long GAP_MS = 5_000;
    /** 同一实体持续刷屏时，每过此毫秒强制打一条汇总并重置，避免永远不输出 */
    private static final long MAX_BURST_MS = 30_000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final class PendingLog {
        final long firstTime;
        long lastTime;
        int count;

        PendingLog(long firstTime) {
            this.firstTime = firstTime;
            this.lastTime = firstTime;
            this.count = 1;
        }
    }

    private static final Map<Integer, PendingLog> pendingEntityLogs = new HashMap<>();

    public static ServerPayloadHandler getInstance() {
        return INSTANCE;
    }

    public static void handleData(final PropertiesMessage data, final IPayloadContext context) {

        context.enqueueWork(() -> {
                    int entityId = data.entityID();
                    long now = System.currentTimeMillis();

                    synchronized (pendingEntityLogs) {
                        PendingLog pending = pendingEntityLogs.get(entityId);
                        if (pending != null) {
                            long gap = now - pending.lastTime;
                            long burst = now - pending.firstTime;
                            if (gap > GAP_MS) {
                                // 本段已静默超过 GAP_MS，上一段结束：打汇总后重新开始
                                if (pending.count > 1) {
                                    LOGGER.info("PropertiesMessage entityId {}: {} times between {} - {}",
                                            entityId, pending.count,
                                            TIME_FMT.format(Instant.ofEpochMilli(pending.firstTime)),
                                            TIME_FMT.format(Instant.ofEpochMilli(pending.lastTime)));
                                }
                                pendingEntityLogs.remove(entityId);
                                pending = null;
                            } else if (burst > MAX_BURST_MS) {
                                // 持续刷屏超过 MAX_BURST_MS：强制打一条汇总并重置窗口
                                LOGGER.info("PropertiesMessage entityId {}: {} times between {} - {} (burst capped)",
                                        entityId, pending.count,
                                        TIME_FMT.format(Instant.ofEpochMilli(pending.firstTime)),
                                        TIME_FMT.format(Instant.ofEpochMilli(pending.lastTime)));
                                pendingEntityLogs.put(entityId, new PendingLog(now));
                                pending = null; // 已重置，下面不再处理
                            } else {
                                pending.lastTime = now;
                                pending.count++;
                                pending = null; // 本包只累计，不打印
                            }
                        }
                        if (pending == null && !pendingEntityLogs.containsKey(entityId)) {
                            LOGGER.info("{}", entityId);
                            pendingEntityLogs.put(entityId, new PendingLog(now));
                        }
                    }

                    var level = context.player().level();
                    Entity e = level.getEntity(data.entityID());
                    if (e instanceof LivingEntity && (data.propertyID().equals(ModConstants.entityDataTagUpdate))) {
                        CitadelEntityData.setCitadelTag((LivingEntity) e, data.compound());
                    }
                })
                .exceptionally(e -> {
                    // Handle exception
                    context.disconnect(Component.translatable(LangDefinition.network_failed, e.getMessage()));
                    return null;
                });
    }

}
