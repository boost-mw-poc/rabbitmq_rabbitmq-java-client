// Copyright (c) 2007-2024 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.test;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.MetricsCollector;
import com.rabbitmq.client.impl.AbstractMetricsCollector;
import com.rabbitmq.client.impl.MicrometerMetricsCollector;
import com.rabbitmq.client.impl.OpenTelemetryMetricsCollector;
import com.rabbitmq.client.impl.StandardMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 */
public class MetricsCollectorTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    public static Object[] data() {
        // need to resort to a factory, as this method is called only once
        // if creating the collector instance, it's reused across the test methods
        // and this doesn't work (it cannot be reset)
        return new Object[]{new StandardMetricsCollectorFactory(), new MicrometerMetricsCollectorFactory(), new OpenTelemetryMetricsCollectorFactory()};
    }

    @BeforeEach
    public void reset() {
        // reset metrics
        otelTesting.clearMetrics();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void basicGetAndAck(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Connection connection = mock(Connection.class);
        when(connection.getId()).thenReturn("connection-1");
        Channel channel = mock(Channel.class);
        when(channel.getConnection()).thenReturn(connection);
        when(channel.getChannelNumber()).thenReturn(1);

        metrics.newConnection(connection);
        metrics.newChannel(channel);

        metrics.consumedMessage(channel, 1, true);
        metrics.consumedMessage(channel, 2, false);
        metrics.consumedMessage(channel, 3, false);
        metrics.consumedMessage(channel, 4, true);
        metrics.consumedMessage(channel, 5, false);
        metrics.consumedMessage(channel, 6, false);

        metrics.basicAck(channel, 6, false);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L);

        metrics.basicAck(channel, 3, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L);

        metrics.basicAck(channel, 6, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L+1L);

        metrics.basicAck(channel, 10, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L+1L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void basicConsumeAndAck(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Connection connection = mock(Connection.class);
        when(connection.getId()).thenReturn("connection-1");
        Channel channel = mock(Channel.class);
        when(channel.getConnection()).thenReturn(connection);
        when(channel.getChannelNumber()).thenReturn(1);

        metrics.newConnection(connection);
        metrics.newChannel(channel);

        String consumerTagWithAutoAck = "1";
        String consumerTagWithManualAck = "2";
        metrics.basicConsume(channel, consumerTagWithAutoAck, true);
        metrics.basicConsume(channel, consumerTagWithManualAck, false);

        metrics.consumedMessage(channel, 1, consumerTagWithAutoAck);
        assertThat(consumedMessages(metrics)).isEqualTo(1L);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(0L);

        metrics.consumedMessage(channel, 2, consumerTagWithManualAck);
        metrics.consumedMessage(channel, 3, consumerTagWithManualAck);
        metrics.consumedMessage(channel, 4, consumerTagWithAutoAck);
        metrics.consumedMessage(channel, 5, consumerTagWithManualAck);
        metrics.consumedMessage(channel, 6, consumerTagWithManualAck);

        metrics.basicAck(channel, 6, false);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L);

        metrics.basicAck(channel, 3, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L);

        metrics.basicAck(channel, 6, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L+1L);

        metrics.basicAck(channel, 10, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(1L+2L+1L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void basicConsumeAndNackReject(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Connection connection = mock(Connection.class);
        when(connection.getId()).thenReturn("connection-1");
        Channel channel = mock(Channel.class);
        when(channel.getConnection()).thenReturn(connection);
        when(channel.getChannelNumber()).thenReturn(1);

        metrics.newConnection(connection);
        metrics.newChannel(channel);

        String ctag = "1";
        metrics.basicConsume(channel, ctag, false);

        LongConsumer consumed = dtag -> metrics.consumedMessage(channel, dtag, ctag);
        long count = 10;
        LongStream.range(0, count).forEach(consumed::accept) ;
        assertThat(consumedMessages(metrics)).isEqualTo(count);
        assertThat(acknowledgedMessages(metrics)).isZero();

        metrics.basicReject(channel, 0, false);
        assertThat(acknowledgedMessages(metrics)).isZero();
        assertThat(rejectedMessages(metrics)).isEqualTo(1L);
        assertThat(requeuedMessages(metrics)).isZero();

        metrics.basicReject(channel, 1, true);
        assertThat(acknowledgedMessages(metrics)).isZero();
        assertThat(rejectedMessages(metrics)).isEqualTo(2L);
        assertThat(requeuedMessages(metrics)).isEqualTo(1L);

        metrics.basicNack(channel, 4, false);
        assertThat(acknowledgedMessages(metrics)).isZero();
        assertThat(rejectedMessages(metrics)).isEqualTo(2L + 3L);
        assertThat(requeuedMessages(metrics)).isEqualTo(1L);

        metrics.basicNack(channel, 7, true);
        assertThat(acknowledgedMessages(metrics)).isZero();
        assertThat(rejectedMessages(metrics)).isEqualTo(2L + 3L + 3L);
        assertThat(requeuedMessages(metrics)).isEqualTo(1L + 3L);

        metrics.basicAck(channel, 9, true);
        assertThat(acknowledgedMessages(metrics)).isEqualTo(2);
        assertThat(rejectedMessages(metrics)).isEqualTo(2L + 3L + 3L);
        assertThat(requeuedMessages(metrics)).isEqualTo(1L + 3L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void publishingAndPublishingFailures(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Channel channel = mock(Channel.class);

        assertThat(failedToPublishMessages(metrics)).isEqualTo(0L);
        assertThat(publishedMessages(metrics)).isEqualTo(0L);

        metrics.basicPublishFailure(channel, new IOException());
        assertThat(failedToPublishMessages(metrics)).isEqualTo(1L);
        assertThat(publishedMessages(metrics)).isEqualTo(0L);

        metrics.basicPublish(channel);
        assertThat(failedToPublishMessages(metrics)).isEqualTo(1L);
        assertThat(publishedMessages(metrics)).isEqualTo(1L);

        metrics.basicPublishFailure(channel, new IOException());
        assertThat(failedToPublishMessages(metrics)).isEqualTo(2L);
        assertThat(publishedMessages(metrics)).isEqualTo(1L);

        metrics.basicPublish(channel);
        assertThat(failedToPublishMessages(metrics)).isEqualTo(2L);
        assertThat(publishedMessages(metrics)).isEqualTo(2L);

        metrics.cleanStaleState();
        assertThat(failedToPublishMessages(metrics)).isEqualTo(2L);
        assertThat(publishedMessages(metrics)).isEqualTo(2L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void publishingAcknowledgements(MetricsCollectorFactory factory) {
        long anyDeliveryTag = 123L;
        AbstractMetricsCollector metrics = factory.create();
        Channel channel = mock(Channel.class);
        // begins with no messages acknowledged
        assertThat(publishAck(metrics)).isEqualTo(0L);
        // first acknowledgement gets tracked
        metrics.basicPublishAck(channel, anyDeliveryTag, false);
        assertThat(publishAck(metrics)).isEqualTo(1L);
        // second acknowledgement gets tracked
        metrics.basicPublishAck(channel, anyDeliveryTag, false);
        assertThat(publishAck(metrics)).isEqualTo(2L);
        // multiple deliveries aren't tracked
        metrics.basicPublishAck(channel, anyDeliveryTag, true);
        assertThat(publishAck(metrics)).isEqualTo(2);
        // cleaning stale state doesn't affect the metric
        metrics.cleanStaleState();
        assertThat(publishAck(metrics)).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void publishingNotAcknowledgements(MetricsCollectorFactory factory) {
        long anyDeliveryTag = 123L;
        AbstractMetricsCollector metrics = factory.create();
        Channel channel = mock(Channel.class);
        // begins with no messages not-acknowledged
        assertThat(publishNack(metrics)).isEqualTo(0L);
        // first not-acknowledgement gets tracked
        metrics.basicPublishNack(channel, anyDeliveryTag, false);
        assertThat(publishNack(metrics)).isEqualTo(1L);
        // second not-acknowledgement gets tracked
        metrics.basicPublishNack(channel, anyDeliveryTag, false);
        assertThat(publishNack(metrics)).isEqualTo(2L);
        // multiple deliveries aren't tracked
        metrics.basicPublishNack(channel, anyDeliveryTag, true);
        assertThat(publishNack(metrics)).isEqualTo(2L);
        // cleaning stale state doesn't affect the metric
        metrics.cleanStaleState();
        assertThat(publishNack(metrics)).isEqualTo(2L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void publishingUnrouted(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Channel channel = mock(Channel.class);
        // begins with no messages not-acknowledged
        assertThat(publishUnrouted(metrics)).isEqualTo(0L);
        // first unrouted gets tracked
        metrics.basicPublishUnrouted(channel);
        assertThat(publishUnrouted(metrics)).isEqualTo(1L);
        // second unrouted gets tracked
        metrics.basicPublishUnrouted(channel);
        assertThat(publishUnrouted(metrics)).isEqualTo(2L);
        // cleaning stale state doesn't affect the metric
        metrics.cleanStaleState();
        assertThat(publishUnrouted(metrics)).isEqualTo(2L);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void cleanStaleState(MetricsCollectorFactory factory) {
        AbstractMetricsCollector metrics = factory.create();
        Connection openConnection = mock(Connection.class);
        when(openConnection.getId()).thenReturn("connection-1");
        when(openConnection.isOpen()).thenReturn(true);

        Channel openChannel = mock(Channel.class);
        when(openChannel.getConnection()).thenReturn(openConnection);
        when(openChannel.getChannelNumber()).thenReturn(1);
        when(openChannel.isOpen()).thenReturn(true);

        Channel closedChannel = mock(Channel.class);
        when(closedChannel.getConnection()).thenReturn(openConnection);
        when(closedChannel.getChannelNumber()).thenReturn(2);
        when(closedChannel.isOpen()).thenReturn(false);

        Connection closedConnection = mock(Connection.class);
        when(closedConnection.getId()).thenReturn("connection-2");
        when(closedConnection.isOpen()).thenReturn(false);

        Channel openChannelInClosedConnection = mock(Channel.class);
        when(openChannelInClosedConnection.getConnection()).thenReturn(closedConnection);
        when(openChannelInClosedConnection.getChannelNumber()).thenReturn(1);
        when(openChannelInClosedConnection.isOpen()).thenReturn(true);

        metrics.newConnection(openConnection);
        metrics.newConnection(closedConnection);
        metrics.newChannel(openChannel);
        metrics.newChannel(closedChannel);
        metrics.newChannel(openChannelInClosedConnection);

        assertThat(connections(metrics)).isEqualTo(2L);
        assertThat(channels(metrics)).isEqualTo(2L+1L);

        metrics.cleanStaleState();

        assertThat(connections(metrics)).isEqualTo(1L);
        assertThat(channels(metrics)).isEqualTo(1L);
    }


    long publishAck(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getPublishAcknowledgedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getAckedPublishedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.acknowledged_published");
        }
    }

    long publishNack(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getPublishNotAcknowledgedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getNackedPublishedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.not_acknowledged_published");
        }
    }

    long publishUnrouted(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getPublishUnroutedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getUnroutedPublishedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.unrouted_published");
        }
    }

    long publishedMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getPublishedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getPublishedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.published");
        }
    }

    long failedToPublishMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getFailedToPublishMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getFailedToPublishMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.failed_to_publish");
        }
    }

    long consumedMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getConsumedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getConsumedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.consumed");
        }
    }

    long acknowledgedMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getAcknowledgedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getAcknowledgedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.acknowledged");
        }
    }

    long rejectedMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getRejectedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getRejectedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.rejected");
        }
    }

    long requeuedMessages(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getRequeuedMessages().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return (long)((MicrometerMetricsCollector) metrics).getRequeuedMessages().count();
        }
        else {
            return getOpenTelemetryCounterMeterValue("rabbitmq.requeued");
        }
    }

    long connections(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getConnections().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return ((MicrometerMetricsCollector) metrics).getConnections().get();
        }
        else {
            return ((OpenTelemetryMetricsCollector)metrics).getConnections().get();
        }
    }

    long channels(MetricsCollector metrics) {
        if (metrics instanceof StandardMetricsCollector) {
            return ((StandardMetricsCollector) metrics).getChannels().getCount();
        }
        else if (metrics instanceof MicrometerMetricsCollector) {
            return ((MicrometerMetricsCollector) metrics).getChannels().get();
        }
        else {
            return ((OpenTelemetryMetricsCollector)metrics).getChannels().get();
        }
    }

    interface MetricsCollectorFactory {
        AbstractMetricsCollector create();
    }

    static class StandardMetricsCollectorFactory implements MetricsCollectorFactory {
        @Override
        public AbstractMetricsCollector create() {
            return new StandardMetricsCollector();
        }
    }

    static class MicrometerMetricsCollectorFactory implements MetricsCollectorFactory {
        @Override
        public AbstractMetricsCollector create() {
            return new MicrometerMetricsCollector(new SimpleMeterRegistry());
        }
    }

    static class OpenTelemetryMetricsCollectorFactory implements MetricsCollectorFactory {
        @Override
        public AbstractMetricsCollector create() {
            return new OpenTelemetryMetricsCollector(otelTesting.getOpenTelemetry());
        }
    }

    static long getOpenTelemetryCounterMeterValue(String name) {
        // open telemetry metrics
        List<MetricData> metrics = otelTesting.getMetrics();
        // metric value
        return metrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getData().getPoints().stream())
            .map(point -> (LongPointData)point)
            .map(LongPointData::getValue)
            .mapToLong(value -> value)
            .sum();
    }
}
