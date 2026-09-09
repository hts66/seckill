package com.example.seckill.cloud.activity;

import com.example.seckill.cloud.api.OrderResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀发消息路径的行为契约：请求线程不等回执，回执到达后按语义决定重发还是补偿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillEngineConfirmTest {
    private static final long ITEM_ID = 7L;
    private static final long USER_ID = 42L;
    private static final String PATH = "valid-path";
    private static final String RESULT_KEY = "seckill:result:" + USER_ID + ":" + ITEM_ID;

    @Mock private JdbcClient jdbc;
    @Mock private StringRedisTemplate redis;
    @Mock private RabbitTemplate rabbit;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private JdbcClient.StatementSpec statementSpec;
    @Mock private JdbcClient.MappedQuerySpec<SeckillEngine.Item> querySpec;

    private SeckillEngine engine;
    /** 捕获发送时挂上的 CorrelationData，测试里手动完成它的 future 来模拟 broker 回执。 */
    private final AtomicReference<CorrelationData> published = new AtomicReference<>();
    private final AtomicInteger sends = new AtomicInteger();
    /** 每次 Lua 脚本调用的 KEYS；补偿脚本用 3 个 key，扣减脚本只用 2 个。 */
    private final List<List<String>> scriptKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        engine = new SeckillEngine(jdbc, redis, rabbit);

        LocalDateTime now = LocalDateTime.now();
        SeckillEngine.Item item = new SeckillEngine.Item(ITEM_ID, 1L, 2L, "商品", "标题", null,
                new BigDecimal("99.00"), new BigDecimal("9.90"), 100, 1, "活动", "描述",
                now.minusHours(2), now.minusHours(1), now.plusHours(1));

        when(jdbc.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(eq(SeckillEngine.Item.class))).thenReturn(querySpec);
        when(querySpec.optional()).thenReturn(Optional.of(item));

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seckill:path:" + USER_ID + ":" + ITEM_ID)).thenReturn(PATH);
        // 1 = 扣减成功，走到发消息分支；补偿脚本的返回值引擎不关心。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            scriptKeys.add(invocation.getArgument(1));
            return 1L;
        });

        doAnswer(invocation -> {
            sends.incrementAndGet();
            published.set(invocation.getArgument(3, CorrelationData.class));
            return null;
        }).when(rabbit).convertAndSend(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ORDER_KEY),
                any(Object.class), any(CorrelationData.class));
    }

    /** execute 返回后消息已发出，但回执还没到——请求线程没有等待任何回执。 */
    @Test
    void executeReturnsPendingWithoutWaitingForConfirm() {
        OrderResultView view = engine.execute(USER_ID, ITEM_ID, null, PATH);

        assertThat(view.status()).isZero();
        assertThat(view.orderNo()).isNull();
        CorrelationData correlation = published.get();
        assertThat(correlation).isNotNull();
        assertThat(correlation.getFuture()).isNotDone();
        // PENDING 必须先于消息发送写入，否则会覆盖消费者已写的 SUCCESS。
        verify(valueOps).set(eq(RESULT_KEY), eq("PENDING"), any(Duration.class));
        assertNoCompensation();
    }

    /** ack 且未 return：消息已进队列，不做任何补偿也不重发。 */
    @Test
    void ackLeavesReservationIntact() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);

        published.get().getFuture().complete(new CorrelationData.Confirm(true, null));

        assertNothingFurtherHappens();
    }

    /** basic.return：broker 收下但路由不到队列，没有消费者会处理，必须立即补偿。 */
    @Test
    void unroutableMessageCompensatesImmediately() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);
        CorrelationData correlation = published.get();

        correlation.setReturned(new ReturnedMessage(null, 312, "NO_ROUTE",
                RabbitTopology.EXCHANGE, RabbitTopology.ORDER_KEY));
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));

        awaitCompensation();
        // 路由失败是确定信号，不该浪费一次重发。
        verifySendCount(1);
    }

    /** nack 是歧义信号（可能是连接断开时的合成 nack，消息其实已落盘），先用同一个 eventId 重发。 */
    @Test
    void nackRepublishesWithSameEventIdBeforeCompensating() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);
        CorrelationData first = published.get();
        String eventId = first.getId();

        first.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));

        awaitNextPublish(first);
        assertThat(published.get().getId())
                .as("重发必须复用 eventId，消费者才能靠 uk_event 幂等去重")
                .isEqualTo(eventId);
        assertNoCompensation();
    }

    /** 重发次数耗尽后才补偿，避免无限重试让 reservation 永远悬着。 */
    @Test
    void compensatesAfterRepublishAttemptsExhausted() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);

        for (int attempt = 1; attempt < 3; attempt++) {
            CorrelationData current = published.get();
            current.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            awaitNextPublish(current);
            assertNoCompensation();
        }
        published.get().getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));

        awaitCompensation();
        // 共发 3 次（首发 + 2 次重发），第 3 次仍失败才补偿。
        verifySendCount(3);
    }

    /** 兜底扫描：结果已不是 PENDING 说明消息其实送达并被消费，既不能补偿也不该重发。 */
    @Test
    void sweepSkipsReservationsAlreadySettled() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);
        when(rabbit.getUnconfirmed(anyLong())).thenReturn(List.of(published.get()));
        when(valueOps.get(RESULT_KEY)).thenReturn("SUCCESS:order-1");

        engine.sweepUnconfirmed();

        assertNothingFurtherHappens();
    }

    /** 兜底扫描：仍是 PENDING 且回执始终没来，交给重发链路。 */
    @Test
    void sweepRepublishesStillPendingReservations() {
        engine.execute(USER_ID, ITEM_ID, null, PATH);
        CorrelationData first = published.get();
        when(rabbit.getUnconfirmed(anyLong())).thenReturn(List.of(first));
        when(valueOps.get(RESULT_KEY)).thenReturn("PENDING");

        engine.sweepUnconfirmed();

        awaitNextPublish(first);
        assertThat(published.get().getId()).isEqualTo(first.getId());
        assertNoCompensation();
    }

    /** 没有待确认消息时 getUnconfirmed 返回 null，不能 NPE。 */
    @Test
    void sweepToleratesNullFromGetUnconfirmed() {
        when(rabbit.getUnconfirmed(anyLong())).thenReturn(null);

        engine.sweepUnconfirmed();

        assertNoCompensation();
    }

    /**
     * 发送本身抛异常（broker 卡住导致 channel 检出超时）时消息肯定没进 broker，
     * 既不会有回执也不会进 getUnconfirmed，必须就地补偿并如实告知用户失败。
     */
    @Test
    void sendFailureCompensatesInlineAndReportsFailure() {
        doThrow(new AmqpTimeoutException("no channel available"))
                .when(rabbit).convertAndSend(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ORDER_KEY),
                        any(Object.class), any(CorrelationData.class));

        OrderResultView view = engine.execute(USER_ID, ITEM_ID, null, PATH);

        assertThat(view.status()).as("不能谎报排队中，库存已经退回").isEqualTo(4);
        assertThat(compensationCalls()).as("必须就地补偿").hasSize(1);
    }

    private void awaitNextPublish(CorrelationData previous) {
        await().atMost(5, TimeUnit.SECONDS).until(() -> published.get() != previous);
    }

    /**
     * 等过重发的最长退避窗口，确认既没有补偿也没有再次发送。
     * 重发是延迟调度的，所以这里必须真的等而不能立即断言。
     */
    private void assertNothingFurtherHappens() {
        CorrelationData current = published.get();
        int sentSoFar = sendCount();
        sleepBriefly();
        assertNoCompensation();
        assertThat(published.get()).as("不应重发").isSameAs(current);
        assertThat(sendCount()).as("发送次数不应增加").isEqualTo(sentSoFar);
    }

    private void awaitCompensation() {
        await().atMost(5, TimeUnit.SECONDS).until(() -> !compensationCalls().isEmpty());
    }

    private void assertNoCompensation() {
        assertThat(compensationCalls()).as("不应发生库存补偿").isEmpty();
    }

    /** 补偿脚本带 3 个 KEYS（stock / users / result），据此区分于只带 2 个的扣减脚本。 */
    private List<List<String>> compensationCalls() {
        return scriptKeys.stream().filter(keys -> keys.size() == 3 && keys.contains(RESULT_KEY)).toList();
    }

    private int sendCount() {
        return sends.get();
    }

    private void verifySendCount(int expected) {
        assertThat(sendCount()).isEqualTo(expected);
    }

    /** 重发退避最长 500ms×(attempt)，留足余量确保「什么都没发生」不是因为等得不够久。 */
    private static void sleepBriefly() {
        try {
            Thread.sleep(1_500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
