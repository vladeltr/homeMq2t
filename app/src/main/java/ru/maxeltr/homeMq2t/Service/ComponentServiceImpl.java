/*
 * The MIT License
 *
 * Copyright 2025 Maxim Eltratov <<Maxim.Eltratov@ya.ru>>..
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
package ru.maxeltr.homeMq2t.Service;

import io.netty.handler.codec.mqtt.MqttQoS;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.apache.commons.lang3.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import ru.maxeltr.homeMq2t.Model.Msg;

/**
 *
 * @author Maxim Eltratov <<Maxim.Eltratov@ya.ru>>
 */
public class ComponentServiceImpl implements ComponentService {

    private static final Logger logger = LoggerFactory.getLogger(ComponentServiceImpl.class);

    private ServiceMediator mediator;

    private List<Object> pluginComponents;

    @Autowired
    private ThreadPoolTaskScheduler threadPoolTaskScheduler;

    @Autowired
    private PeriodicTrigger pollingPeriodicTrigger;

    private ScheduledFuture<?> pollingScheduledFuture;

    public ComponentServiceImpl(List<Object> pluginComponents) {
        this.pluginComponents = pluginComponents;
    }

    @Override
    public void setMediator(ServiceMediator mediator) {
        this.mediator = mediator;
    }

    @PostConstruct
    public void postConstruct() {
        //this.components = this.loader.loadComponents(this.appProperties.getComponentPath());

        logger.debug("Postconstruc ComponentService = {}", this.pluginComponents);

        for (Object component : this.pluginComponents) {
            for (Class componentInterface : component.getClass().getInterfaces()) {
                if (componentInterface.getSimpleName().equals(CallbackComponent.class.getSimpleName())) {

                    try {
                        //((CallbackComponent) component).setCallback((Consumer<String>) (String data) -> callback(data));
                        Method method = component.getClass().getMethod("setCallback", Consumer.class);
                        method.invoke(component, (Consumer<String>) (String data) -> {
                            callback(data);
                        });
                    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException ex) {
                        logger.warn("Couid not set callback to component={}", component, ex);
                    }

                }
            }

            logger.debug("Loaded {}", component);

        }

        this.startPolling();

        //this.future = taskScheduler.schedule(new RunnableTask(), periodicTrigger);
    }

    public void callback(String data) {
        logger.debug("Callback data={}", data);
    }

    @Override
    public void process(Msg msg, String componentNumber) {
        logger.debug("Process message. Component number={}, msg={} ", componentNumber, msg);
        if (msg.getType().equalsIgnoreCase(MediaType.TEXT_PLAIN_VALUE)) {
            if (msg.getData().equalsIgnoreCase("updateAll")) {
                logger.debug("Update readings of all components");
                this.stopPolling();
                this.startPolling();
            }
        }
    }

    @Override
    public void startPolling() {
        if (this.pollingScheduledFuture == null) {
            logger.info("Start polling components task.");
            this.pollingScheduledFuture = this.threadPoolTaskScheduler.schedule(new PollingTask(), this.pollingPeriodicTrigger);
        } else {
            logger.warn("Could not start polling components task. Previous polling components task was not stopped.");
        }
    }

    @Override
    public void stopPolling() {
        if (this.pollingScheduledFuture != null && !this.pollingScheduledFuture.isCancelled()) {
            this.pollingScheduledFuture.cancel(false);
            this.pollingScheduledFuture = null;
            logger.info("Polling components task has been stopped");
        }
    }

    class PollingTask implements Runnable {

        @Override
        public void run() {
            logger.debug("Start/resume polling");
            Msg.Builder builder;
            for (Object ob : pluginComponents) {
                logger.debug("Component in polling task {}", ob);
//                    String data = component.getData();
//                    logger.info("Component={}. Get data={}.", component.getName(), data);
//
//                    builder = new Msg.Builder("onPolling");
//                    builder.data(data);
//                    builder.type(appProperties.getComponentPubDataType(component.getName()));
//                    builder.timestamp(String.valueOf(Instant.now().toEpochMilli()));
//
//                    String topic = appProperties.getComponentPubTopic(component.getName());
//                    MqttQoS qos = MqttQoS.valueOf(appProperties.getComponentPubQos(component.getName()));
//                    boolean retain = Boolean.getBoolean(appProperties.getComponentPubRetain(component.getName()));
//                    publish(builder, topic, qos, retain);

            }

            logger.debug("Pause polling");
        }
    }

    @Async("processExecutor")
    private void publish(Msg.Builder msg, String topic, MqttQoS qos, boolean retain) {
        logger.info("Message passes to publish. Message={}, topic={}, qos={}, retain={}", msg, topic, qos, retain);
        this.mediator.publish(msg.build(), topic, qos, retain);
    }

}
