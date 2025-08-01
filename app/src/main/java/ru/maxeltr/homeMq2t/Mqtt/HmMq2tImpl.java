/*
 * The MIT License
 *
 * Copyright 2023 Maxim Eltratov <<Maxim.Eltratov@ya.ru>>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ru.maxeltr.homeMq2t.Mqtt;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttReasonCodeAndPropertiesVariableHeader;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribePayload;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ru.maxeltr.homeMq2t.Service.ServiceMediator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.boot.CommandLineRunner;
import ru.maxeltr.homeMq2t.Config.AppProperties;

/**
 *
 * @author Maxim Eltratov <<Maxim.Eltratov@ya.ru>>
 */
public class HmMq2tImpl implements HmMq2t, CommandLineRunner {  //TODO separate to ConnectionManager, ReconnectionStrategy, PingScheduler, SubscriptionManager, PublishManager

    private static final Logger logger = LoggerFactory.getLogger(HmMq2tImpl.class);

    private EventLoopGroup workerGroup;

    private Channel channel;

    @Autowired
    private ThreadPoolTaskScheduler threadPoolTaskScheduler;

    @Autowired
    private PeriodicTrigger retransmitPeriodicTrigger;

    @Autowired
    private MqttAckMediator mqttAckMediator;

    private ServiceMediator serviceMediator;

    @Autowired
    private MqttChannelInitializer mqttChannelInitializer;

    @Value("${host:127.0.0.1}")
    private String host;

    @Value("${port:1883}")
    private Integer port;

    @Value("${connect-timeout:5000}")
    private Integer connectTimeout;

    @Value("${wait-disconnect-while-shutdown:1000}")
    private Integer waitDisconnect;

    @Value("${clean-session:true}")
    private boolean cleanSession;

    @Value("${reconnect:true}")
    private boolean reconnect;

    @Value("${reconnect-delay:3000}")
    private int reconnectDelay;

    @Value("${reconnect-delay-max:1800000}")
    private int reconnectDelayMax;

    @Value("${auto-connect:false}")
    private boolean autoConnect;

    @Autowired
    private AppProperties appProperties;

    private final AtomicInteger nextMessageId = new AtomicInteger(1);

    private final Map<String, MqttTopicSubscription> subscribedTopics = Collections.synchronizedMap(new LinkedHashMap<>());

    private final static AtomicBoolean connecting = new AtomicBoolean();

    private final static AtomicBoolean connected = new AtomicBoolean();

    private final static AtomicBoolean reconnecting = new AtomicBoolean();

    private static int reconnectAttempts = 0;

    private ScheduledFuture<?> retransmitScheduledFuture;

    @Override
    public void run(String... args) {
        logger.info("Start app with args={}.", Arrays.toString(args));
        if (this.autoConnect) {
            logger.info("Start auto connect.");
            this.connect();
        }
    }

    @Override
    public Promise<MqttConnAckMessage> connect() {
        logger.debug("Start connect method.");
        if (connecting.get() || connected.get()) {
            logger.warn("Connecting or connected already. connecting={}. connected={}. auhtFuture={}", connecting.get(), connected.get(), mqttAckMediator.getConnectFuture());
            return mqttAckMediator.getConnectFuture();
        }
        connecting.set(true);
        logger.info("Start connection attempt.");
        workerGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.handler(mqttChannelInitializer);

        Promise<MqttConnAckMessage> authFuture = new DefaultPromise<>(workerGroup.next());
        authFuture.addListener(f -> {
            if (f.isSuccess()) {
                connected.set(true);
                logger.debug("Connection accepted. CONNACK message has been received {}.", ((MqttConnAckMessage) f.get()).variableHeader());
                //perform post-connection operations here
                HmMq2tImpl.this.subscribe(appProperties.getSubscriptions());
                reconnectAttempts = 0;
                this.startRetransmitTask();
            }
            logger.debug("Connection attempt completed. authFuture isDone={}, isSuccess={}, isCancelled={}, future={}", f.isDone(), f.isSuccess(), f.isCancelled(), f);
            connecting.set(false);
        });
        mqttAckMediator.setConnectFuture(authFuture);

        bootstrap.remoteAddress(this.host, this.port);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.connectTimeout);
        ChannelFuture channelFuture = bootstrap.connect();
        channelFuture.addListener((ChannelFutureListener) f -> {
            HmMq2tImpl.this.channel = f.channel();
            logger.debug("Waiting for ConnAckMessage. ChannelFuture isDone={}, isSuccess={}, isCancelled={}, future={}", f.isDone(), f.isSuccess(), f.isCancelled(), f);
        });

        logger.info("Connecting to {} via port {}.", this.host, this.port);
        channelFuture.awaitUninterruptibly();
        if (channelFuture.isCancelled()) {
            logger.info("Connection attempt cancelled.");
            this.cancelConnect();
        } else if (!channelFuture.isSuccess()) {
            logger.info("Connection attempt failed {}.", channelFuture.cause().getMessage());
            this.cancelConnect();
        } else {
            logger.info("Connected to {} via port {}.", this.host, this.port);
        }

        return authFuture;

    }

    private void cancelConnect() {
        Promise<MqttConnAckMessage> authFuture = mqttAckMediator.getConnectFuture();
        if (authFuture != null && !authFuture.isDone()) {
            authFuture.cancel(true);
            logger.debug("Cancel auth future.");
        }
        connecting.set(false);
        connected.set(false);
    }

    @Override
    public void reconnect() {
        logger.debug("Start reconnect method.");
        if (!this.reconnect) {
            logger.info("Reconnect is not allowed by config.");
            return;
        }

        if (reconnecting.get() || connecting.get()) {
            logger.info("Unable to start reconnecting. The connection is being reconnected.");
            return;
        }

        reconnecting.set(true);
        reconnectAttempts = reconnectAttempts + 1;
        logger.info("Start reconnect! Attempt {}.", reconnectAttempts);

        this.disconnect(MqttReasonCodeAndPropertiesVariableHeader.REASON_CODE_OK);

        int timeout = this.reconnectDelay * reconnectAttempts;
        if (timeout > this.reconnectDelayMax) {
            timeout = this.reconnectDelayMax;
        }

        try {
            TimeUnit.MILLISECONDS.sleep(timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.info("InterruptedException while reconnect delay.", ex);
        }

        Promise<MqttConnAckMessage> reconnectFuture = this.connect();
        reconnectFuture.awaitUninterruptibly(this.connectTimeout);
        if (reconnectFuture.isCancelled()) {
            logger.info("Reconnection canceled.");
        } else if (!reconnectFuture.isSuccess()) {
            logger.info("Reconnection failed");
        } else {
            logger.info("Reconnection is successful.");
        }
        reconnecting.set(false);
    }

    private Optional<MqttPingScheduleHandler> getPingHandler() {
        return Optional.ofNullable(((MqttPingScheduleHandler) channel.pipeline().get("mqttPingHandler")));
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void disconnect(byte reasonCode) {
        Promise<MqttConnAckMessage> authFuture = mqttAckMediator.getConnectFuture();
        if (authFuture != null && !authFuture.isDone()) {
            authFuture.cancel(true);
        }
        connecting.set(false);

        this.getPingHandler().ifPresent(pingHandler -> pingHandler.stopPing());

        this.stopRetransmitTask();

        if (this.cleanSession) {
            this.mqttAckMediator.clear();
        }

        //unsubscribe because we subscribe again when we connect
        if (!this.cleanSession) {
            List<String> topics = this.subscribedTopics.keySet().stream().collect(Collectors.toList());
            logger.info("Unsubscribing from topics=[{}]", topics);
            Promise<MqttUnsubAckMessage> unSubscribeFuture = this.unsubscribe(topics);
            unSubscribeFuture.awaitUninterruptibly(this.connectTimeout);
        }
        this.subscribedTopics.clear();

        MqttFixedHeader mqttFixedHeader = new MqttFixedHeader(MqttMessageType.DISCONNECT, false, MqttQoS.AT_MOST_ONCE, false, 0);
        MqttReasonCodeAndPropertiesVariableHeader mqttDisconnectVariableHeader = new MqttReasonCodeAndPropertiesVariableHeader(reasonCode, MqttProperties.NO_PROPERTIES);
        MqttMessage message = new MqttMessage(mqttFixedHeader, mqttDisconnectVariableHeader);

        this.writeAndFlush(message);

        logger.info("Sent disconnection message reason={}, d={}, q={}, r={}.",
                mqttDisconnectVariableHeader.reasonCode(),
                message.fixedHeader().isDup(),
                message.fixedHeader().qosLevel(),
                message.fixedHeader().isRetain()
        );

        try {
            TimeUnit.MILLISECONDS.sleep(waitDisconnect);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.info("Disconnect message has been send. InterruptedException while timeout.", ex);
        }

        this.shutdown();

        connected.set(false);
    }

    private void shutdown() {
        if (this.channel != null) {
            this.channel.close();
            logger.info("Close channel");
        }

        this.workerGroup.shutdownGracefully().awaitUninterruptibly();
        logger.info("Shutdown gracefully");
    }

    @Override
    public Promise<MqttSubAckMessage> subscribe(List<MqttTopicSubscription> subscriptions) {
        int id = getNewMessageId();
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.SUBSCRIBE, false, MqttQoS.AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(id);
        MqttSubscribePayload payload = new MqttSubscribePayload(subscriptions);
        MqttSubscribeMessage message = new MqttSubscribeMessage(fixedHeader, variableHeader, payload);

        Promise<MqttSubAckMessage> subscribeFuture = new DefaultPromise<>(this.workerGroup.next());
        this.mqttAckMediator.add(id, subscribeFuture, message);
        subscribeFuture.addListener((FutureListener) (Future f) -> {
            HmMq2tImpl.this.handleSubAckMessage((MqttSubAckMessage) f.get());
        });

        ReferenceCountUtil.retain(message); //TODO is it nessesary?

        this.writeAndFlush(message);
        logger.info("Sent SUBSCRIBE message id={}, d={}, q={}, r={}.", message.variableHeader().messageId(), message.fixedHeader().isDup(), message.fixedHeader().qosLevel(), message.fixedHeader().isRetain());

        return subscribeFuture;
    }

    private void handleSubAckMessage(MqttSubAckMessage subAckMessage) {
        int id = subAckMessage.variableHeader().messageId();
        MqttSubscribeMessage subscribeMessage = this.mqttAckMediator.getMessage(id);
        /* if (subscribeMessage == null) {
            logger.warn("There is no stored SUBSCRIBE message for SUBACK message. May be it was acknowledged already.");
            //TODO resub?
            return;
        } */
        this.mqttAckMediator.remove(id);
        logger.info("Subscribe message id={} has been acknowledged.", id);

        List<MqttTopicSubscription> topics = subscribeMessage.payload().topicSubscriptions();
        List<Integer> subAckQos = subAckMessage.payload().grantedQoSLevels();
        if (subAckQos.size() != topics.size()) {
            logger.warn("Number of topics to subscribe is not match number of returned granted QOS. QoS size={}. Topics size={}", subAckQos.size(), topics.size());
            this.disconnect((byte) 1);  //TODO resub?
        } else {
            for (int i = 0; i < subAckQos.size(); i++) {
                if (subAckQos.get(i) == 128) {
                    logger.warn("Subscription on topic={} with Qos={} failed. Return code={}", topics.get(i).topicName(), topics.get(i).qualityOfService(), subAckQos.get(i));
                } else if (subAckQos.get(i) == topics.get(i).qualityOfService().value()) {
                    logger.info("Subscribed on topic={} with Qos={}.", topics.get(i).topicName(), topics.get(i).qualityOfService());
                } else {
                    logger.warn("Subscribed on topic={} with Qos={}. But granted Qos={}", topics.get(i).topicName(), topics.get(i).qualityOfService(), subAckQos.get(i));
                    //TODO resub with lower QoS?
                }
                this.subscribedTopics.put(topics.get(i).topicName(), topics.get(i));
            }
        }
        logger.info("Active topics list=[{}].", this.getSubscribedTopicAndQosAsString());
    }

    @Override
    public void publish(String topic, ByteBuf payload, MqttQoS qos, boolean retain) {
        switch (qos) {
            case MqttQoS.AT_MOST_ONCE -> {
                this.publishAtMostOnce(topic, payload, retain);
            }
            case MqttQoS.AT_LEAST_ONCE -> {
                this.publishAtLeastOnce(topic, payload, retain);
            }
            case MqttQoS.EXACTLY_ONCE -> {
                this.publishExactlyOnce(topic, payload, retain);
            }
        }
    }

    public void publishAtMostOnce(String topic, ByteBuf payload, boolean retain) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_MOST_ONCE, retain, 0);
        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, -1);
        MqttPublishMessage message = new MqttPublishMessage(fixedHeader, variableHeader, payload);

        this.writeAndFlush(message);
        logger.info("Sent publish message id={}, t={}, d={}, q={}, r={}.",
                message.variableHeader().packetId(),
                message.variableHeader().topicName(),
                message.fixedHeader().isDup(),
                message.fixedHeader().qosLevel(),
                message.fixedHeader().isRetain());
    }

    public void publishAtLeastOnce(String topic, ByteBuf payload, boolean retain) {
        int id = this.getNewMessageId();
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, retain, 0);
        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, id);
        MqttPublishMessage message = new MqttPublishMessage(fixedHeader, variableHeader, payload);

        Promise<? extends MqttMessage> publishFuture = new DefaultPromise<>(this.workerGroup.next());
        this.mqttAckMediator.add(id, publishFuture, message);
        publishFuture.addListener((FutureListener) (Future f) -> {
            HmMq2tImpl.this.handlePubAckMessage((MqttPubAckMessage) f.get());
        });

        ReferenceCountUtil.retain(message); //TODO is it nessesary?

        this.writeAndFlush(message);
        logger.info("Sent publish message id={}, t={}, d={}, q={}, r={}.",
                message.variableHeader().packetId(),
                message.variableHeader().topicName(),
                message.fixedHeader().isDup(),
                message.fixedHeader().qosLevel(),
                message.fixedHeader().isRetain()
        );
    }

    private void handlePubAckMessage(MqttPubAckMessage pubAckMessage) {
        int id = pubAckMessage.variableHeader().messageId();
        MqttPublishMessage publishMessage = this.mqttAckMediator.getMessage(id);
        /* if (publishMessage == null) {
            logger.warn("There is no stored PUBLISH message for PUBACK message. May be it was acknowledged already.");
             return;
        }*/
        this.mqttAckMediator.remove(id);
        logger.info("PublishMessage id={} has been acknowledged.", id);
        ReferenceCountUtil.release(publishMessage);
    }

    public void publishExactlyOnce(String topic, ByteBuf payload, boolean retain) {
        int id = this.getNewMessageId();
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.EXACTLY_ONCE, retain, 0);
        MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, id);
        MqttPublishMessage message = new MqttPublishMessage(fixedHeader, variableHeader, payload);

        Promise<? extends MqttMessage> publishFuture = new DefaultPromise<>(this.workerGroup.next());
        this.mqttAckMediator.add(id, publishFuture, message);
        publishFuture.addListener((FutureListener) (Future f) -> {
            HmMq2tImpl.this.handlePubRecMessage((MqttMessage) f.get());
        });

        ReferenceCountUtil.retain(message); //TODO is it nessesary?

        this.writeAndFlush(message);
        logger.info("Sent publish message id={}, t={}, d={}, q={}, r={}.",
                message.variableHeader().packetId(),
                message.variableHeader().topicName(),
                message.fixedHeader().isDup(),
                message.fixedHeader().qosLevel(),
                message.fixedHeader().isRetain()
        );
    }

    private void handlePubRecMessage(MqttMessage pubRecMessage) {
        int id = ((MqttMessageIdVariableHeader) pubRecMessage.variableHeader()).messageId();
        MqttPublishMessage publishMessage = this.mqttAckMediator.getMessage(id);
        /* if (publishMessage == null ) {
            logger.warn("There is no stored PUBLISH message for PUBREC message. May be it was acknowledged already.");
            return;
        } */
        this.mqttAckMediator.remove(id);
        logger.info("Publish message id={} has been acknowledged.", id);
        ReferenceCountUtil.release(publishMessage);
        this.sendPubRelMessage(id);
    }

    private void sendPubRelMessage(int id) {
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(id);
        MqttMessage pubrelMessage = new MqttMessage(fixedHeader, variableHeader);

        Promise<? extends MqttMessage> pubRelFuture = new DefaultPromise<>(this.workerGroup.next());
        this.mqttAckMediator.add(id, pubRelFuture, pubrelMessage);
        pubRelFuture.addListener((FutureListener) (Future f) -> {
            HmMq2tImpl.this.handlePubCompMessage((MqttMessage) f.get());
        });

        ReferenceCountUtil.retain(pubrelMessage); //TODO is it nessesary?

        this.writeAndFlush(pubrelMessage);
        logger.info("Sent PUBREL message id={}, d={}, q={}, r={}.",
                variableHeader.messageId(),
                pubrelMessage.fixedHeader().isDup(),
                pubrelMessage.fixedHeader().qosLevel(),
                pubrelMessage.fixedHeader().isRetain()
        );
    }

    private void handlePubCompMessage(MqttMessage pubCompMessage) {
        int id = ((MqttMessageIdVariableHeader) pubCompMessage.variableHeader()).messageId();
        MqttMessage pubrelMessage = this.mqttAckMediator.getMessage(id);
        /* if (pubrelMessage == null ) {
            logger.warn("There is no stored PUBREL message for PUBCOMP message. May be it was acknowledged already.");
            return;
        } */
        this.mqttAckMediator.remove(id);
        logger.info("PubRelMessage id={} has been acknowledged.", id);
        ReferenceCountUtil.release(pubrelMessage);

    }

    private ChannelFuture writeAndFlush(Object message) {
        if (this.channel == null) {
            logger.error("Cannot write and flush message. Channel is null");
            return null;
        }
        if (this.channel.isActive()) {
            return this.channel.writeAndFlush(message);
        }
        logger.error("Cannot write and flush message. Channel is closed.");
        return this.channel.newFailedFuture(new RuntimeException("Cannot write and flush message. Channel is closed."));
    }

    private int getNewMessageId() {
        int i = 0;
        this.nextMessageId.compareAndSet(0xffff, 1);
        int id = this.nextMessageId.getAndIncrement();

        while (this.mqttAckMediator.isContainId(id)) {
            if (this.nextMessageId.compareAndSet(0xffff, 1)) {
                ++i;
                if (i >= 2) {
                    this.mqttAckMediator.clear();
                    this.disconnect((byte) 1);
                }
            }
            id = this.nextMessageId.getAndIncrement();
        }

        return id;
    }

    @Override
    public Promise<MqttUnsubAckMessage> unsubscribe(List<String> topics) {
        int id = getNewMessageId();
        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.UNSUBSCRIBE, false, MqttQoS.AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(id);
        MqttUnsubscribePayload payload = new MqttUnsubscribePayload(topics);
        MqttUnsubscribeMessage unSubscribeMessage = new MqttUnsubscribeMessage(fixedHeader, variableHeader, payload);

        Promise<MqttUnsubAckMessage> unSubscribeFuture = new DefaultPromise<>(this.workerGroup.next());
        this.mqttAckMediator.add(id, unSubscribeFuture, unSubscribeMessage);
        unSubscribeFuture.addListener((FutureListener) (Future f) -> {
            HmMq2tImpl.this.handleUnSubAckMessage((MqttUnsubAckMessage) f.get());
        });

        ReferenceCountUtil.retain(unSubscribeMessage); //TODO is it nessesary?

        this.writeAndFlush(unSubscribeMessage);
        logger.info("Sent unsubscribe message id={}, d={}, q={}, r={}.",
                unSubscribeMessage.variableHeader().messageId(),
                unSubscribeMessage.fixedHeader().isDup(),
                unSubscribeMessage.fixedHeader().qosLevel(),
                unSubscribeMessage.fixedHeader().isRetain()
        );

        return unSubscribeFuture;
    }

    private void handleUnSubAckMessage(MqttUnsubAckMessage unSubAckMessage) {
        int id = unSubAckMessage.variableHeader().messageId();
        MqttUnsubscribeMessage unSubscribeMessage = this.mqttAckMediator.getMessage(id);
        this.mqttAckMediator.remove(id);
        this.subscribedTopics.keySet().removeAll(unSubscribeMessage.payload().topics());
        logger.info("Unsubscribe message id={} has been acknowledged.", id);
        logger.info("Clear active topics. List={}.", unSubscribeMessage.payload().topics());
        ReferenceCountUtil.release(unSubscribeMessage);
    }

    @Override
    public void setMediator(ServiceMediator serviceMediator) {
        this.serviceMediator = serviceMediator;
    }

    public String getSubscribedTopicAndQosAsString() {
        return this.subscribedTopics.keySet().stream().map(key -> this.subscribedTopics.get(key).toString()).collect(Collectors.joining("\\n ", "", ""));
    }

    private void startRetransmitTask() {
        if (this.retransmitScheduledFuture == null || this.retransmitScheduledFuture.isDone()) {
            logger.info("Start retransmit task.");
            this.retransmitScheduledFuture = this.threadPoolTaskScheduler.schedule(new RetransmitTask(), this.retransmitPeriodicTrigger);
        } else {
            logger.warn("Could not start retransmit task. Previous retransmit task was not stopped.");
        }
    }

    private void stopRetransmitTask() {
        if (this.retransmitScheduledFuture != null && !this.retransmitScheduledFuture.isCancelled()) {
            this.retransmitScheduledFuture.cancel(false);
            this.retransmitScheduledFuture = null;
            logger.info("Retransmit task has been stopped");
        }
    }

    class RetransmitTask implements Runnable {

        @Override
        public void run() {     //TODO syncronized?
            logger.info("Start retransmission");
            for (MqttMessage message : mqttAckMediator) {
                logger.info("message={}", message.variableHeader());
                MqttMessageType messageType = message.fixedHeader().messageType();
                switch (messageType) {
                    case MqttMessageType.PUBLISH -> {
                        MqttPublishMessage initialMessage = (MqttPublishMessage) message;
                        MqttQoS qos = initialMessage.fixedHeader().qosLevel();
                        if (qos == MqttQoS.AT_LEAST_ONCE || qos == MqttQoS.EXACTLY_ONCE) {
                            MqttFixedHeader fixedHeader = new MqttFixedHeader(
                                    initialMessage.fixedHeader().messageType(),
                                    true, //change Dup on true
                                    initialMessage.fixedHeader().qosLevel(),
                                    initialMessage.fixedHeader().isRetain(),
                                    initialMessage.fixedHeader().remainingLength()
                            );
                            MqttPublishMessage dupMessage = new MqttPublishMessage(fixedHeader, initialMessage.variableHeader(), initialMessage.payload());

                            writeAndFlush(dupMessage);
                            logger.info("Publish message has been retransmited. id={}, t={}, d={}, q={}, r={}",
                                    dupMessage.variableHeader().packetId(),
                                    dupMessage.variableHeader().topicName(),
                                    dupMessage.fixedHeader().isDup(),
                                    dupMessage.fixedHeader().qosLevel(),
                                    dupMessage.fixedHeader().isRetain()
                            );
                        }
                    }
                    case MqttMessageType.SUBSCRIBE -> {
                        writeAndFlush(message);
                        MqttSubscribeMessage initialMessage = (MqttSubscribeMessage) message;
                        logger.info("Subscribe message has been retransmited. id={}, q={}, r={}",
                                initialMessage.variableHeader().messageId(),
                                initialMessage.fixedHeader().qosLevel(),
                                initialMessage.fixedHeader().isRetain()
                        );
                    }
                    case MqttMessageType.UNSUBSCRIBE -> {
                        writeAndFlush(message);
                        MqttUnsubscribeMessage initialMessage = (MqttUnsubscribeMessage) message;
                        logger.info("Unsubscribe message has been retransmited. id={}, q={}, r={}",
                                initialMessage.variableHeader().messageId(),
                                initialMessage.fixedHeader().qosLevel(),
                                initialMessage.fixedHeader().isRetain()
                        );
                    }
                    case MqttMessageType.PUBREL -> {
                        writeAndFlush(message);
                        MqttMessageIdVariableHeader idVariableHeader = (MqttMessageIdVariableHeader) message.variableHeader();
                        logger.info("PubRel message has been retransmited. id={}, d={}, q={}, r={}",
                                idVariableHeader.messageId(),
                                message.fixedHeader().isDup(),
                                message.fixedHeader().qosLevel(),
                                message.fixedHeader().isRetain()
                        );
                    }

                }

            }
        }

    }

}
