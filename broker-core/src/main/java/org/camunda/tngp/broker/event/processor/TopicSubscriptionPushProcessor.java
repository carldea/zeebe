package org.camunda.tngp.broker.event.processor;

import static org.camunda.tngp.util.buffer.BufferUtil.cloneBuffer;

import java.util.concurrent.atomic.AtomicBoolean;

import org.agrona.DirectBuffer;
import org.camunda.tngp.broker.logstreams.BrokerEventMetadata;
import org.camunda.tngp.broker.logstreams.processor.MetadataFilter;
import org.camunda.tngp.broker.logstreams.processor.NoopSnapshotSupport;
import org.camunda.tngp.broker.transport.clientapi.SubscribedEventWriter;
import org.camunda.tngp.logstreams.log.LogStream;
import org.camunda.tngp.logstreams.log.LogStreamReader;
import org.camunda.tngp.logstreams.log.LoggedEvent;
import org.camunda.tngp.logstreams.processor.EventProcessor;
import org.camunda.tngp.logstreams.processor.StreamProcessor;
import org.camunda.tngp.logstreams.processor.StreamProcessorContext;
import org.camunda.tngp.logstreams.spi.SnapshotSupport;
import org.camunda.tngp.protocol.clientapi.EventType;
import org.camunda.tngp.protocol.clientapi.SubscriptionType;
import org.camunda.tngp.util.collection.LongRingBuffer;

public class TopicSubscriptionPushProcessor implements StreamProcessor, EventProcessor
{

    protected final BrokerEventMetadata metadata = new BrokerEventMetadata();
    protected final TopicSubscriptionEvent ack = new TopicSubscriptionEvent();

    protected LoggedEvent event;

    protected final int channelId;
    protected final long subscriberKey;
    protected long startPosition;
    protected final DirectBuffer name;
    protected final String nameString;
    protected DirectBuffer logStreamTopicName;
    protected int logStreamPartitionId;

    protected final SnapshotSupport snapshotSupport = new NoopSnapshotSupport();
    protected final SubscribedEventWriter channelWriter;

    protected LongRingBuffer pendingEvents;
    protected LongRingBuffer pendingAcks;
    protected AtomicBoolean enabled;

    public TopicSubscriptionPushProcessor(
            int channelId,
            long subscriberKey,
            long startPosition,
            DirectBuffer name,
            int prefetchCapacity,
            SubscribedEventWriter channelWriter)
    {
        this.channelWriter = channelWriter;
        this.channelId = channelId;
        this.subscriberKey = subscriberKey;
        this.startPosition = startPosition;
        this.name = cloneBuffer(name);
        this.nameString = name.getStringWithoutLengthUtf8(0, name.capacity());
        this.enabled = new AtomicBoolean(false);

        if (prefetchCapacity > 0)
        {
            this.pendingEvents = new LongRingBuffer(prefetchCapacity);
            this.pendingAcks = new LongRingBuffer(prefetchCapacity);
        }
    }

    @Override
    public void onOpen(StreamProcessorContext context)
    {

        final LogStreamReader logReader = context.getSourceLogStreamReader();

        final LogStream sourceStream = context.getSourceStream();
        this.logStreamTopicName = sourceStream.getTopicName();
        this.logStreamPartitionId = sourceStream.getPartitionId();

        setToStartPosition(logReader);
    }

    /**
     * @return the position at which this processor actually started. This may be different than the constructor argument
     */
    public long getStartPosition()
    {
        return startPosition;
    }

    protected void setToStartPosition(LogStreamReader logReader)
    {
        if (startPosition >= 0)
        {
            logReader.seek(startPosition);
        }
        else
        {
            logReader.seekToLastEvent();

            if (logReader.hasNext())
            {
                logReader.next();
            }
        }

        startPosition = logReader.getPosition();
    }

    @Override
    public SnapshotSupport getStateResource()
    {
        return snapshotSupport;
    }

    @Override
    public EventProcessor onEvent(LoggedEvent event)
    {
        this.event = event;
        return this;
    }

    @Override
    public void processEvent()
    {
    }

    @Override
    public boolean executeSideEffects()
    {
        event.readMetadata(metadata);

        final boolean success = channelWriter
            .channelId(channelId)
            .topicName(logStreamTopicName)
            .partitionId(logStreamPartitionId)
            .eventType(metadata.getEventType())
            .key(event.getKey())
            .position(event.getPosition())
            .subscriberKey(subscriberKey)
            .subscriptionType(SubscriptionType.TOPIC_SUBSCRIPTION)
            .event(event.getValueBuffer(), event.getValueOffset(), event.getValueLength())
            .tryWriteMessage();

        if (success && recordsPendingEvents())
        {
            final boolean elementAdded = pendingEvents.addElementToHead(event.getPosition());
            if (!elementAdded)
            {
                throw new RuntimeException("Cannot record pending event " + elementAdded);
            }
        }

        return success;
    }

    @Override
    public boolean isSuspended()
    {
        if (!enabled.get())
        {
            return true;
        }

        if (recordsPendingEvents())
        {
            // first, process any ACKs if there are any pending
            pendingAcks.consume((ackedPosition) -> pendingEvents.consumeAscendingUntilInclusive(ackedPosition));
            return pendingEvents.isSaturated();
        }
        else
        {
            return false;
        }
    }

    public int getChannelId()
    {
        return channelId;
    }

    public SubscribedEventWriter getChannelWriter()
    {
        return channelWriter;
    }

    public String getNameAsString()
    {
        return nameString;
    }

    public void onAck(long eventPosition)
    {
        if (recordsPendingEvents())
        {
            final boolean elementAdded = pendingAcks.addElementToHead(eventPosition);

            if (!elementAdded)
            {
                throw new RuntimeException("Could not acknowledge event at position " + eventPosition + "; ACK capacity saturated");
            }
        }
    }

    /**
     * @return true if this subscription requires throttling
     */
    protected boolean recordsPendingEvents()
    {
        return pendingEvents != null;
    }

    public static MetadataFilter eventFilter()
    {
        // don't push subscription or subscriber events;
        // this may lead to infinite loops of pushing events that in turn trigger creation of more such events (e.g. ACKs)
        return (m) -> m.getEventType() != EventType.SUBSCRIPTION_EVENT && m.getEventType() != EventType.SUBSCRIBER_EVENT;
    }

    public DirectBuffer getName()
    {
        return name;
    }

    public long getSubscriptionId()
    {
        return subscriberKey;
    }

    public void enable()
    {
        this.enabled.set(true);
    }
}
